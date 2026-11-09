package dev.veyra.downloads

import dev.veyra.model.*

/** Shared by the real claim transactions and their metadata-only scheduling projection. */
internal object DownloadClaimRules {
    val busy = setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.VERIFYING)
    val queued = setOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK)

    fun backgroundBlocked(record: DownloadRecord): Boolean =
        record.status == DownloadStatus.NEEDS_FOREGROUND || record.failure?.code == FailureCode.BACKGROUND_RESTRICTED

    fun acquisitionIntent(snapshot: AppSnapshot, record: DownloadRecord): Boolean =
        !record.cleanupPending && !record.userPaused && record.status in queued &&
            record.id !in snapshot.downloadFinalizations && record.asset == null && record.mediaId in snapshot.media

    fun acquisitionReady(snapshot: AppSnapshot, record: DownloadRecord, now: Long,
        network: DownloadNetworkState, suspended: Boolean, deferredUntil: Long?): Boolean =
        acquisitionIntent(snapshot, record) && !suspended && !backgroundBlocked(record) &&
            !snapshot.settings.offlineOnly && network == DownloadNetworkState.CONNECTED &&
            !record.liveDownloadLease(now) && record.retryDue(now) && (deferredUntil ?: 0) <= now &&
            liveTransferLeases(snapshot, now).size < snapshot.settings.downloadParallelism.coerceIn(1, 4)

    fun liveTransferLeases(snapshot: AppSnapshot, now: Long): List<DownloadRecord> =
        snapshot.downloads.values.filter { it.liveDownloadLease(now) && it.status in busy && !it.cleanupPending }

    fun maintenanceIntent(snapshot: AppSnapshot, record: DownloadRecord, kind: DownloadMaintenanceKind,
        assetChecked: Boolean): Boolean = when (kind) {
        DownloadMaintenanceKind.CLEANUP -> record.cleanupPending
        DownloadMaintenanceKind.FINALIZATION -> {
            val intent = snapshot.downloadFinalizations[record.id]
            !record.cleanupPending && !record.userPaused && intent != null &&
                (record.activeAttemptId == null || record.activeAttemptId == intent.attemptId)
        }
        DownloadMaintenanceKind.CHECK_ASSET -> !record.cleanupPending && record.status == DownloadStatus.COMPLETE &&
            record.asset != null && !assetChecked
    }

    fun maintenanceReady(snapshot: AppSnapshot, record: DownloadRecord, kind: DownloadMaintenanceKind,
        assetChecked: Boolean, now: Long, suspended: Boolean, deferredUntil: Long?): Boolean =
        maintenanceIntent(snapshot, record, kind, assetChecked) && !suspended && !backgroundBlocked(record) &&
            !record.liveDownloadLease(now) && record.retryDue(now) && (deferredUntil ?: 0) <= now

    fun expiredRecoveryIntent(snapshot: AppSnapshot, record: DownloadRecord): Boolean =
        !record.cleanupPending && record.id !in snapshot.downloadFinalizations &&
            (record.leaseOwner != null || record.status in busy)

    fun expiredRecoveryReady(snapshot: AppSnapshot, record: DownloadRecord, now: Long,
        deferredUntil: Long? = null): Boolean =
        expiredRecoveryIntent(snapshot, record) && !record.liveDownloadLease(now) && (deferredUntil ?: 0) <= now
}

internal enum class DownloadMaintenanceKind { CLEANUP, FINALIZATION, CHECK_ASSET }
internal enum class DownloadClaimKind { ACQUISITION, CLEANUP, FINALIZATION, CHECK_ASSET, EXPIRED_RECOVERY }
internal fun DownloadMaintenanceKind.claimKind(): DownloadClaimKind = when (this) {
    DownloadMaintenanceKind.CLEANUP -> DownloadClaimKind.CLEANUP
    DownloadMaintenanceKind.FINALIZATION -> DownloadClaimKind.FINALIZATION
    DownloadMaintenanceKind.CHECK_ASSET -> DownloadClaimKind.CHECK_ASSET
}

internal fun DownloadRecord.liveDownloadLease(now: Long): Boolean =
    leaseOwner != null && (leaseExpiresEpochMs ?: 0) > now
private fun DownloadRecord.retryDue(now: Long): Boolean = (nextRetryEpochMs ?: 0) <= now

/** Drop only progress/presentation timestamps; keep every claim/reference/policy input. */
internal fun DownloadRecord.schedulingRecord(): DownloadRecord = copy(
    bytesDownloaded = 0, totalBytes = null, updatedEpochMs = 0, lastProgressEpochMs = null,
)

internal data class DownloadClaimKey(val id: String, val kind: DownloadClaimKind)
internal data class DownloadClaimDeferral(
    val record: DownloadRecord,
    val untilEpochMs: Long,
    val refusals: Int,
)

internal fun claimDeferredUntil(record: DownloadRecord, kind: DownloadClaimKind,
    deferrals: Map<DownloadClaimKey, DownloadClaimDeferral>): Long? =
    deferrals[DownloadClaimKey(record.id, kind)]?.takeIf { it.record == record.schedulingRecord() }?.untilEpochMs

internal data class DownloadSchedulingContext(
    val nowEpochMs: Long,
    val suspended: Boolean,
    val checksActivated: Boolean,
    val network: DownloadNetworkState,
    val checkedIds: Set<String>,
    val deferrals: Map<DownloadClaimKey, DownloadClaimDeferral>,
)

/**
 * Pure metadata projection. It cannot attest native file-lock ownership or allocate a lease.
 * A refused physical claim or failed admission transaction records a bounded LOCAL recheck
 * instead of repeatedly admitting an empty foreground worker. Its actual next claim uses the
 * same not-before check above. This transient deferral never acknowledges durable work as done.
 */
internal fun projectDownloadWakePlan(snapshot: AppSnapshot, context: DownloadSchedulingContext,
    key: DownloadSchedulingKey): DownloadWakePlan {
    val now = context.nowEpochMs
    var local: Long? = null
    var network: Long? = null
    var due = false
    var blocked: AppFailure? = null
    fun localWake(at: Long) { local = local?.let { minOf(it, at) } ?: at; if (at <= now) due = true }
    fun networkWake(at: Long) {
        network = network?.let { minOf(it, at) } ?: at
        if (at <= now && context.network == DownloadNetworkState.CONNECTED) due = true
    }
    fun hostBlocked(record: DownloadRecord): Boolean {
        if (!context.suspended && !DownloadClaimRules.backgroundBlocked(record)) return false
        blocked = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        return true
    }
    val occupied = DownloadClaimRules.liveTransferLeases(snapshot, now)
    val capacityAvailable = occupied.size < snapshot.settings.downloadParallelism.coerceIn(1, 4)
    for (record in snapshot.downloads.values) {
        val checked = record.id in context.checkedIds
        if (record.status == DownloadStatus.NEEDS_FOREGROUND)
            blocked = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        // Match the reconciler's ordering: a paused/future finalization cannot be
        // sidestepped by treating that same record as an ordinary expired transfer.
        val kind = when {
            record.cleanupPending -> DownloadMaintenanceKind.CLEANUP
            record.id in snapshot.downloadFinalizations -> DownloadMaintenanceKind.FINALIZATION
            context.checksActivated && record.status == DownloadStatus.COMPLETE && !checked -> DownloadMaintenanceKind.CHECK_ASSET
            else -> null
        }
        if (kind != null) {
            if (DownloadClaimRules.maintenanceIntent(snapshot, record, kind, checked) && !hostBlocked(record)) {
                val at = maxOf(0, record.nextRetryEpochMs ?: 0,
                    if (record.leaseOwner != null) record.leaseExpiresEpochMs ?: 0 else 0,
                    claimDeferredUntil(record, kind.claimKind(), context.deferrals) ?: 0)
                localWake(at)
            }
            continue
        }
        if (DownloadClaimRules.expiredRecoveryIntent(snapshot, record)) {
            if (!context.suspended) localWake(maxOf(0,
                if (record.leaseOwner == null) 0 else record.leaseExpiresEpochMs ?: 0,
                claimDeferredUntil(record, DownloadClaimKind.EXPIRED_RECOVERY, context.deferrals) ?: 0))
            else blocked = AppFailure(FailureCode.BACKGROUND_RESTRICTED)
        }
        if (!DownloadClaimRules.acquisitionIntent(snapshot, record) || hostBlocked(record)) continue
        if (snapshot.settings.offlineOnly) {
            if (blocked == null) blocked = AppFailure(FailureCode.OFFLINE_POLICY)
            continue
        }
        val at = maxOf(0, record.nextRetryEpochMs ?: 0,
            if (record.leaseOwner != null) record.leaseExpiresEpochMs ?: 0 else 0)
        val refusedUntil = claimDeferredUntil(record, DownloadClaimKind.ACQUISITION, context.deferrals)
        if (refusedUntil != null && refusedUntil > now) {
            // A file/slot refusal is a LOCAL ownership recheck, not an Internet failure.
            localWake(maxOf(at, refusedUntil))
        } else if (!capacityAvailable) {
            // A logically occupied slot is not locally owned work. Revisit at the
            // earliest foreign lease expiry; an OS file lock still fences takeover.
            occupied.mapNotNull { it.leaseExpiresEpochMs }.minOrNull()?.let { localWake(maxOf(at, it)) }
        } else networkWake(at)
    }
    if (blocked == null && network != null && context.network != DownloadNetworkState.CONNECTED)
        blocked = AppFailure(FailureCode.NETWORK_UNAVAILABLE)
    return DownloadWakePlan(key, local, network, due, blocked)
}
