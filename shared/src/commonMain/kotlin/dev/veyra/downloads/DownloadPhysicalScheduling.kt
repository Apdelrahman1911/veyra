package dev.veyra.downloads

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*

/** Relevant immutable/canonical inputs only; no fabricated legacy DownloadRecord or MediaItem. */
internal data class DownloadPhysicalScheduleRecord(
    val job: PhysicalDownloadJob,
    val claims: List<DownloadClaim>,
    val finalization: PhysicalDownloadFinalization?,
    val asset: MaterializationAsset?,
    val stagingPaths: Set<String>,
)

internal data class DownloadPhysicalClaimDeferral(
    val record: DownloadPhysicalScheduleRecord,
    val untilEpochMs: Long,
    val refusals: Int,
)

/** Included in the existing Coordinator's coherent idle ticket and synchronous final recheck. */
internal data class DownloadPhysicalSchedulingInputs(
    val records: Map<String, DownloadPhysicalScheduleRecord>,
    val checkedIds: Set<String>,
    val deferrals: Map<DownloadClaimKey, DownloadPhysicalClaimDeferral>,
)

internal fun AcquisitionRepositoryState.physicalSchedulingRecords(): Map<String, DownloadPhysicalScheduleRecord> {
    val byJob = claims.values.groupBy { it.physicalJobId }
    val attempts = physicalAttempts.values.groupBy { it.downloadId }
    return physicalDownloads.mapValues { (id, job) ->
        DownloadPhysicalScheduleRecord(job.copy(bytesDownloaded = 0, totalBytes = null,
            updatedEpochMs = 0, lastProgressEpochMs = null),
            byJob[id].orEmpty().sortedBy { it.id.value }, physicalFinalizations[id], job.assetId?.let(materializations::get),
            setOfNotNull(job.stagingPath, physicalFinalizations[id]?.stagingPath) + attempts[id].orEmpty().mapNotNull { it.stagingPath })
    }
}

internal fun physicalClaimDeferredUntil(record: DownloadPhysicalScheduleRecord, kind: DownloadClaimKind,
    deferrals: Map<DownloadClaimKey, DownloadPhysicalClaimDeferral>): Long? =
    deferrals[DownloadClaimKey(record.job.id, kind)]?.takeIf { it.record == record }?.untilEpochMs

internal object DownloadPhysicalClaimRules {
    fun demand(record: DownloadPhysicalScheduleRecord): DownloadClaimDemand =
        summarizeDownloadClaims(record.job.id, record.claims).demand

    fun backgroundBlocked(job: PhysicalDownloadJob) = job.status == DownloadStatus.NEEDS_FOREGROUND ||
        job.failure?.code == FailureCode.BACKGROUND_RESTRICTED

    fun knownProducer(job: PhysicalDownloadJob) = job.producer.bindingId != null && job.producer.recordingId != null

    fun acquisitionIntent(record: DownloadPhysicalScheduleRecord): Boolean = with(record) {
        !job.cleanupPending && !job.userPaused && job.maintenanceKind == null && finalization == null &&
            job.assetId == null && job.status in DownloadClaimRules.queued && knownProducer(job) &&
            demand(record) == DownloadClaimDemand.CONTINUE
    }

    fun maintenanceIntent(record: DownloadPhysicalScheduleRecord, kind: PhysicalDownloadMaintenanceKind,
        checked: Boolean, explicitRetainedValidation: Boolean = false): Boolean = with(record) {
        when (kind) {
            PhysicalDownloadMaintenanceKind.CLEANUP -> job.cleanupPending
            PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION -> !job.cleanupPending && !job.userPaused &&
                finalization != null && knownProducer(job) && demand(record) == DownloadClaimDemand.CONTINUE &&
                job.failure?.code !in terminalLocalFailures
            PhysicalDownloadMaintenanceKind.VALIDATE -> !job.cleanupPending && asset != null && knownProducer(job) &&
                !checked && (job.status == DownloadStatus.COMPLETE ||
                    explicitRetainedValidation && job.status == DownloadStatus.FAILED)
        }
    }

    fun expiredRecoveryIntent(record: DownloadPhysicalScheduleRecord): Boolean = with(record) {
        !job.cleanupPending && finalization == null && (job.leaseOwner != null || job.status in DownloadClaimRules.busy)
    }

    /** A durable batch intent must survive losing the adapter call before physical reconciliation. */
    fun needsIntentReconciliation(record: DownloadPhysicalScheduleRecord): Boolean = with(record) {
        if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) return@with false
        when (demand(record)) {
            DownloadClaimDemand.CONTINUE -> job.userPaused && !backgroundBlocked(job)
            DownloadClaimDemand.PAUSE_AND_RETAIN -> !job.userPaused && job.status != DownloadStatus.COMPLETE
            DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE ->
                job.status != DownloadStatus.COMPLETE && (!job.userPaused || job.status != DownloadStatus.CANCELLED ||
                    !job.cleanupPending && (job.stagingPath != null || finalization != null))
            DownloadClaimDemand.REMOVE_CANDIDATE -> !job.removeWhenClean || !job.cleanupPending &&
                (job.status != DownloadStatus.CANCELLED || job.assetId != null || job.stagingPath != null || finalization != null)
            DownloadClaimDemand.UNOWNED_UNKNOWN -> false
        }
    }

    private val terminalLocalFailures = setOf(FailureCode.CORRUPT_MEDIA, FailureCode.UNSUPPORTED_CODEC,
        FailureCode.RECOVERY_REQUIRED, FailureCode.INVALID_STATE, FailureCode.NOT_FOUND)
}

internal fun PhysicalDownloadJob.livePhysicalLease(now: Long): Boolean = leaseOwner != null && (leaseExpiresEpochMs ?: 0) > now

internal fun PhysicalDownloadMaintenanceKind.physicalClaimKind(): DownloadClaimKind = when (this) {
    PhysicalDownloadMaintenanceKind.CLEANUP -> DownloadClaimKind.CLEANUP
    PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION -> DownloadClaimKind.FINALIZATION
    PhysicalDownloadMaintenanceKind.VALIDATE -> DownloadClaimKind.CHECK_ASSET
}

/** Pure extension of the existing two-lane durable wake protocol, NOT an additional scheduler. */
internal fun projectPhysicalDownloadWakePlan(inputs: DownloadPhysicalSchedulingInputs, host: DownloadPhysicalHostState,
    now: Long, key: DownloadSchedulingKey): DownloadWakePlan {
    var local: Long? = null
    var network: Long? = null
    var due = false
    var blocked: AppFailure? = host.failure
    fun localWake(at: Long) { local = local?.let { minOf(it, at) } ?: at; if (at <= now) due = true }
    fun networkWake(at: Long) {
        network = network?.let { minOf(it, at) } ?: at
        if (at <= now && host.network == DownloadNetworkState.CONNECTED) due = true
    }
    fun hostBlocked(job: PhysicalDownloadJob): Boolean {
        if (!host.closed && !host.suspended && host.drainsInFlight == 0 && host.failure == null &&
            !DownloadPhysicalClaimRules.backgroundBlocked(job)) return false
        blocked = host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        return true
    }
    val occupied = inputs.records.values.map { it.job }.filter {
        it.livePhysicalLease(now) && it.status in DownloadClaimRules.busy && !it.cleanupPending && it.maintenanceKind == null
    }
    val capacityAvailable = occupied.size < host.parallelism.coerceIn(1, 4)
    for ((id, record) in inputs.records) {
        val job = record.job
        if (DownloadPhysicalClaimRules.backgroundBlocked(job)) blocked = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        if (DownloadPhysicalClaimRules.needsIntentReconciliation(record)) {
            if (!host.closed && !host.suspended && host.drainsInFlight == 0 && host.failure == null) localWake(maxOf(0,
                physicalClaimDeferredUntil(record, DownloadClaimKind.EXPIRED_RECOVERY, inputs.deferrals) ?: 0))
            else blocked = host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED)
            // Resolve the complete durable owner set before exposing a different work lane.
            continue
        }
        val kind = when {
            job.cleanupPending -> PhysicalDownloadMaintenanceKind.CLEANUP
            record.finalization != null -> PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION
            host.checksActivated && job.status == DownloadStatus.COMPLETE && id !in inputs.checkedIds -> PhysicalDownloadMaintenanceKind.VALIDATE
            else -> null
        }
        if (kind != null) {
            if (DownloadPhysicalClaimRules.maintenanceIntent(record, kind, id in inputs.checkedIds) && !hostBlocked(job)) {
                localWake(maxOf(0, job.nextRetryEpochMs ?: 0,
                    if (job.leaseOwner != null) job.leaseExpiresEpochMs ?: 0 else 0,
                    physicalClaimDeferredUntil(record, kind.physicalClaimKind(), inputs.deferrals) ?: 0))
            } else if (!DownloadPhysicalClaimRules.knownProducer(job) && kind != PhysicalDownloadMaintenanceKind.CLEANUP && blocked == null)
                blocked = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required")
            else if (job.failure != null && blocked == null) blocked = job.failure
            continue
        }
        if (DownloadPhysicalClaimRules.expiredRecoveryIntent(record)) {
            if (!host.closed && !host.suspended && host.drainsInFlight == 0 && host.failure == null) localWake(maxOf(0,
                if (job.leaseOwner == null) 0 else job.leaseExpiresEpochMs ?: 0,
                physicalClaimDeferredUntil(record, DownloadClaimKind.EXPIRED_RECOVERY, inputs.deferrals) ?: 0))
            else blocked = host.failure ?: AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        }
        if (!DownloadPhysicalClaimRules.knownProducer(job) && !job.cleanupPending && blocked == null)
            blocked = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required")
        if (DownloadPhysicalClaimRules.demand(record) == DownloadClaimDemand.UNOWNED_UNKNOWN && blocked == null)
            blocked = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_claim_join_missing")
        if (!DownloadPhysicalClaimRules.acquisitionIntent(record) || hostBlocked(job)) continue
        if (host.offlineOnly) {
            if (blocked == null) blocked = AppFailure(FailureCode.OFFLINE_POLICY)
            continue
        }
        val at = maxOf(0, job.nextRetryEpochMs ?: 0, if (job.leaseOwner != null) job.leaseExpiresEpochMs ?: 0 else 0)
        val refusedUntil = physicalClaimDeferredUntil(record, DownloadClaimKind.ACQUISITION, inputs.deferrals)
        if (refusedUntil != null && refusedUntil > now) localWake(maxOf(at, refusedUntil))
        else if (!capacityAvailable) occupied.mapNotNull { it.leaseExpiresEpochMs }.minOrNull()?.let { localWake(maxOf(at, it)) }
        else networkWake(at)
    }
    if (blocked == null && network != null && host.network != DownloadNetworkState.CONNECTED)
        blocked = AppFailure(FailureCode.NETWORK_UNAVAILABLE)
    return DownloadWakePlan(key, local, network, due, blocked)
}
