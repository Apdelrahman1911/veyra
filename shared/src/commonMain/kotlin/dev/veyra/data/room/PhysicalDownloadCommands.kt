package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.downloads.DownloadClaimDemand
import dev.veyra.downloads.summarizeDownloadClaims
import dev.veyra.model.*

internal fun <T> AcquisitionGraph.physicalStale(): AcquisitionMutation<PhysicalDownloadCommit<T>> =
    AcquisitionMutation(this) { PhysicalDownloadCommit.Stale }

internal fun <T> AcquisitionGraph.physicalCommitted(project: (AcquisitionGraph) -> T): AcquisitionMutation<PhysicalDownloadCommit<T>> =
    AcquisitionMutation(this) { PhysicalDownloadCommit.Committed(it.revision, project(it)) }

internal fun AcquisitionGraph.physicalJoin(id: String): PhysicalDownloadJoin? {
    val state = state()
    val job = state.physicalDownloads[id] ?: return null
    val attempts = state.physicalAttempts.values.filter { it.downloadId == id }.sortedBy { it.number }
    val active = job.activeAttemptId?.let { state.physicalAttempts.getValue(it) }
    val intent = state.physicalFinalizations[id]
    val asset = job.assetId?.let { state.materializations.getValue(it) }
    val claims = state.claims.values.filter { it.physicalJobId == id }.sortedBy { it.id.value }
    val summary = summarizeDownloadClaims(id, claims)
    val reservation = if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP && job.leaseOwner != null) {
        PhysicalDownloadCleanupReservation(physicalFence(job, active),
            setOfNotNull(job.stagingPath, intent?.stagingPath) + attempts.mapNotNull { it.stagingPath }, intent, asset,
            removeCompletedAsset = summary.demand == DownloadClaimDemand.REMOVE_CANDIDATE,
            removeJobWhenClean = job.removeWhenClean)
    } else null
    return PhysicalDownloadJoin(revision, job, active, intent, asset, claims,
        (job.producer as? PhysicalDownloadProducer.Typed)?.let { state.executionContext(it.bindingId) }, attempts, reservation)
}

internal fun physicalFence(job: PhysicalDownloadJob, attempt: PhysicalDownloadAttempt?): PhysicalDownloadFence =
    PhysicalDownloadFence(job.id, job.producer, job.ownershipGeneration, requireNotNull(job.leaseOwner), attempt?.id, attempt?.number)

internal fun PhysicalDownloadJoin.matches(fence: PhysicalDownloadFence, now: Long? = null): Boolean {
    if (job.id != fence.downloadId || job.producer != fence.producer || job.ownershipGeneration != fence.ownershipGeneration ||
        job.leaseOwner != fence.leaseOwner || (now != null && (now < job.updatedEpochMs || (job.leaseExpiresEpochMs ?: 0) <= now))) return false
    val expectedAttemptId = if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION) finalization?.attemptId
        else job.activeAttemptId
    if (expectedAttemptId != fence.attemptId) return false
    return fence.attemptId == null || attempts.singleOrNull { it.id == fence.attemptId }?.let {
        it.number == fence.attemptNumber && it.downloadId == job.id && it.producer == job.producer &&
            (job.maintenanceKind != null || it.ownershipGeneration == fence.ownershipGeneration)
    } == true
}

internal fun PhysicalDownloadJoin.matchesClaims(expected: List<DownloadClaim>): Boolean =
    claims.size == expected.size && expected.map { it.id }.toSet().size == expected.size && claims.toSet() == expected.toSet()

internal fun PhysicalDownloadJoin.demand(): DownloadClaimDemand = summarizeDownloadClaims(job.id, claims).demand
internal fun PhysicalDownloadJoin.committedBy(graph: AcquisitionGraph): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> =
    graph.physicalCommitted { requireNotNull(it.physicalJoin(job.id)) }

internal fun AcquisitionGraph.refreshBatchDownloadStates(id: String, now: Long): AcquisitionGraph {
    // The intermediate graph can legitimately contain an AVAILABLE member while this same
    // transaction changes the shared job away from COMPLETE. Project from the row graph without
    // the committed-graph readback guard; Room/LibraryStore still validate the final graph.
    val state = stateForBatchProjection()
    val job = state.physicalDownloads.getValue(id)
    var graph = this
    state.batches.values.filter { batch -> batch.members.any { member -> member.claimId?.let { state.claims[it]?.physicalJobId == id } == true } }
        .forEach { batch ->
            val members = batch.members.map { member ->
                val claim = member.claimId?.let(state.claims::get) ?: return@map member
                if (claim.physicalJobId != id) return@map member
                val available = job.status == DownloadStatus.COMPLETE && job.assetId != null && state.assetUses.any {
                    it.materializationAssetId == job.assetId && it.requestedBindingId == member.requiredBindingId
                }
                val projected = when {
                    claim.intent == DownloadBatchIntent.CANCELLED && (!claim.retainCompletedAsset || !available) -> DownloadBatchMemberState.CANCELLED
                    available -> DownloadBatchMemberState.AVAILABLE
                    claim.intent == DownloadBatchIntent.PAUSED -> DownloadBatchMemberState.PAUSED
                    job.status == DownloadStatus.COMPLETE || job.status == DownloadStatus.VERIFYING -> DownloadBatchMemberState.VERIFYING
                    job.status == DownloadStatus.RESOLVING || job.status == DownloadStatus.RUNNING -> DownloadBatchMemberState.TRANSFERRING
                    job.status == DownloadStatus.FAILED -> DownloadBatchMemberState.FAILED
                    job.status == DownloadStatus.CANCELLED -> DownloadBatchMemberState.CANCELLED
                    job.status == DownloadStatus.PAUSED || job.status == DownloadStatus.NEEDS_FOREGROUND -> DownloadBatchMemberState.PAUSED
                    else -> DownloadBatchMemberState.WAITING_FOR_JOB
                }
                member.copy(state = projected, failure = job.failure)
            }
            if (members != batch.members) graph = graph.withBatch(batch.copy(members = members, updatedEpochMs = maxOf(batch.updatedEpochMs, now)))
        }
    return graph
}

/** Durable aggregate intent only. An expired lease/this result is never a native/file drain receipt. */
internal fun AcquisitionGraph.reconcileJob(join: PhysicalDownloadJoin, now: Long, allowHostResume: Boolean = false): AcquisitionGraph {
    require(now >= join.job.updatedEpochMs)
    var job = join.job
    var graph = this
    if (job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) {
        // A cleanup ownership interrupted by the host keeps its reservation but is admitted again
        // only after an explicit foreground announcement. This is a retry of the SAME cleanup,
        // never a status shortcut or an inferred completion.
        if (!allowHostResume || !job.cleanupPending ||
            job.leaseOwner != null && (job.leaseExpiresEpochMs ?: 0) > now ||
            job.status != DownloadStatus.NEEDS_FOREGROUND && job.failure?.code != FailureCode.BACKGROUND_RESTRICTED)
            return this
        val hostOnly = job.failure == null || job.failure?.code == FailureCode.BACKGROUND_RESTRICTED
        val recovered = job.copy(status = DownloadStatus.CANCELLED, leaseOwner = null, leaseExpiresEpochMs = null,
            failure = if (hostOnly) null else job.failure,
            nextRetryEpochMs = if (hostOnly) null else job.nextRetryEpochMs, updatedEpochMs = now)
        return graph.withJob(recovered).refreshBatchDownloadStates(job.id, now)
    }
    val expired = job.leaseOwner != null && (job.leaseExpiresEpochMs ?: 0) <= now
    if (expired || job.leaseOwner == null && job.status in setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.VERIFYING) && join.finalization == null) {
        join.activeAttempt?.takeIf { it.finishedEpochMs == null }?.let { attempt ->
            graph = graph.withAttempt(attempt.copy(status = DownloadAttemptStatus.PAUSED, finishedEpochMs = maxOf(now, attempt.startedEpochMs)))
        }
        job = job.copy(leaseOwner = null, leaseExpiresEpochMs = null, activeAttemptId = null, maintenanceKind = null,
            status = if (join.finalization == null && job.assetId == null && job.status in setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.VERIFYING)) DownloadStatus.QUEUED else job.status)
    }
    val blocked = job.status == DownloadStatus.FAILED || job.status == DownloadStatus.NEEDS_FOREGROUND || job.failure?.code == FailureCode.BACKGROUND_RESTRICTED
    // A complete asset's denied/interrupted recheck is allowed to be rechecked only after a real
    // foreground return. The marker is set by the validation finalizer; a retained local failure is
    // kept stopped rather than converted to a fake COMPLETE or a dead QUEUED transfer.
    val completedCheckResume = job.assetId != null && join.finalization == null &&
        job.status == DownloadStatus.NEEDS_FOREGROUND && job.failure?.code == FailureCode.BACKGROUND_RESTRICTED &&
        job.failure?.detail == "download_completed_check_needs_foreground"
    val retainedFailureResume = job.assetId != null && join.finalization == null &&
        (job.status == DownloadStatus.FAILED || job.status == DownloadStatus.NEEDS_FOREGROUND) && !completedCheckResume
    job = when (join.demand()) {
        DownloadClaimDemand.CONTINUE -> if (job.cleanupPending) job else job.copy(userPaused = false, removeWhenClean = false,
            status = when {
                job.status == DownloadStatus.COMPLETE -> DownloadStatus.COMPLETE
                completedCheckResume && allowHostResume -> DownloadStatus.COMPLETE
                retainedFailureResume && allowHostResume -> DownloadStatus.FAILED
                blocked && !allowHostResume -> job.status
                join.finalization != null -> DownloadStatus.VERIFYING
                job.leaseOwner != null -> job.status
                job.status in setOf(DownloadStatus.PAUSED, DownloadStatus.CANCELLED) || allowHostResume -> DownloadStatus.QUEUED
                else -> job.status
            }, failure = when {
                completedCheckResume && allowHostResume -> null
                retainedFailureResume && allowHostResume -> job.failure?.takeUnless {
                    it.code == FailureCode.BACKGROUND_RESTRICTED
                } ?: AppFailure(FailureCode.RECOVERY_REQUIRED, "download_local_validation_required")
                allowHostResume && blocked -> null
                else -> job.failure
            }, nextRetryEpochMs = when {
                completedCheckResume && allowHostResume -> null
                retainedFailureResume && allowHostResume -> job.nextRetryEpochMs
                allowHostResume -> null
                else -> job.nextRetryEpochMs
            })
        DownloadClaimDemand.PAUSE_AND_RETAIN -> job.copy(userPaused = true,
            status = if (job.status == DownloadStatus.COMPLETE) job.status else DownloadStatus.PAUSED)
        DownloadClaimDemand.CANCEL_UNFINISHED_KEEP_COMPLETE -> job.copy(userPaused = true,
            status = if (job.assetId != null) DownloadStatus.COMPLETE else DownloadStatus.CANCELLED,
            cleanupPending = job.stagingPath != null || join.finalization != null || job.cleanupPending,
            removeWhenClean = false)
        DownloadClaimDemand.REMOVE_CANDIDATE -> job.copy(userPaused = true, status = DownloadStatus.CANCELLED,
            cleanupPending = job.assetId != null || job.stagingPath != null || join.finalization != null || job.cleanupPending ||
                join.attempts.any { it.finishedEpochMs == null || !job.removeWhenClean && it.stagingPath != null }, removeWhenClean = true)
        DownloadClaimDemand.UNOWNED_UNKNOWN -> job.copy(userPaused = true,
            failure = AppFailure(FailureCode.RECOVERY_REQUIRED, "download_claim_join_missing"))
    }
    if (job != join.job) graph = graph.withJob(job.copy(updatedEpochMs = now))
    return graph.refreshBatchDownloadStates(job.id, now)
}

internal fun AcquisitionGraph.applyClaimAttempt(command: ClaimPhysicalDownloadAttempt): AcquisitionMutation<PhysicalDownloadCommit<ClaimedPhysicalDownload>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    val job = join.job
    if (job.producer != command.expectedProducer || job.ownershipGeneration != command.expectedOwnershipGeneration ||
        job.leaseOwner != null || job.activeAttemptId != null || job.cleanupPending || job.maintenanceKind != null ||
        job.userPaused || join.finalization != null || job.assetId != null || (job.nextRetryEpochMs ?: 0) > command.nowEpochMs ||
        job.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.WAITING_NETWORK) || join.demand() != DownloadClaimDemand.CONTINUE ||
        job.producer.recordingId == null || job.producer.bindingId == null || job.failure?.code == FailureCode.BACKGROUND_RESTRICTED) return physicalStale()
    require(command.nowEpochMs >= job.updatedEpochMs && command.leaseDurationMs in 1_000..600_000 && command.owner.isNotBlank())
    require(command.nowEpochMs <= Long.MAX_VALUE - command.leaseDurationMs && job.ownershipGeneration < Long.MAX_VALUE && job.attemptCount < Int.MAX_VALUE)
    require(command.attemptId.isNotBlank() && command.attemptId !in state().physicalAttempts && command.stagingPath.isNotBlank())
    // A new path never inherits counters/validators for unknown bytes. Saved same-C key may persist.
    val samePath = command.stagingPath == job.stagingPath
    if (samePath) require(!job.representationId.isNullOrBlank())
    val changed = job.copy(status = DownloadStatus.RESOLVING, ownershipGeneration = job.ownershipGeneration + 1,
        attemptCount = job.attemptCount + 1, activeAttemptId = command.attemptId, leaseOwner = command.owner,
        leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs, stagingPath = command.stagingPath,
        bytesDownloaded = if (samePath) job.bytesDownloaded else 0, totalBytes = if (samePath) job.totalBytes else null,
        etag = if (samePath) job.etag else null, lastModified = if (samePath) job.lastModified else null,
        failure = null, nextRetryEpochMs = null, updatedEpochMs = command.nowEpochMs)
    val attempt = PhysicalDownloadAttempt(command.attemptId, job.id, job.producer, changed.attemptCount, changed.ownershipGeneration,
        startedEpochMs = command.nowEpochMs, bytesDownloaded = changed.bytesDownloaded, totalBytes = changed.totalBytes,
        stagingPath = changed.stagingPath, representationId = changed.representationId, etag = changed.etag,
        lastModified = changed.lastModified, validatorGeneration = changed.validatorGeneration, backgroundTaskId = command.backgroundTaskId)
    val graph = withJob(changed).withAttempt(attempt).refreshBatchDownloadStates(job.id, command.nowEpochMs)
    return graph.physicalCommitted { actual ->
        val actualJoin = requireNotNull(actual.physicalJoin(job.id))
        ClaimedPhysicalDownload(physicalFence(actualJoin.job, actualJoin.activeAttempt), actualJoin)
    }
}

internal fun AcquisitionGraph.applyPhysicalHeartbeat(command: HeartbeatPhysicalDownload): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence, command.nowEpochMs) || join.job.maintenanceKind == null && join.demand() != DownloadClaimDemand.CONTINUE) return physicalStale()
    require(command.leaseDurationMs in 1_000..600_000 && command.nowEpochMs <= Long.MAX_VALUE - command.leaseDurationMs)
    require(command.bytesDownloaded >= join.job.bytesDownloaded && (command.totalBytes == null || command.totalBytes >= command.bytesDownloaded))
    if (join.job.maintenanceKind != null) require(command.bytesDownloaded == join.job.bytesDownloaded &&
        command.totalBytes == join.job.totalBytes) { "Maintenance heartbeat cannot change transfer counters" }
    val job = join.job.copy(bytesDownloaded = command.bytesDownloaded, totalBytes = command.totalBytes,
        lastProgressEpochMs = command.nowEpochMs, updatedEpochMs = command.nowEpochMs,
        leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs)
    var graph = withJob(job)
    if (join.job.maintenanceKind == null) {
        val attempt = requireNotNull(join.activeAttempt)
        require(attempt.finishedEpochMs == null)
        graph = graph.withAttempt(attempt.copy(bytesDownloaded = command.bytesDownloaded, totalBytes = command.totalBytes))
    }
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applySelectRepresentation(command: SelectPhysicalDownloadRepresentation): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence, command.nowEpochMs) || join.job.maintenanceKind != null || join.demand() != DownloadClaimDemand.CONTINUE ||
        join.job.status !in setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING)) return physicalStale()
    require(command.representationId.isNotBlank() && command.representationId.length <= 2_048 && command.validatorGeneration >= join.job.validatorGeneration)
    require(join.job.representationId == null || join.job.representationId == command.representationId)
    require(command.etag?.none { it.code < 32 || it.code == 127 } != false && command.lastModified?.none { it.code < 32 || it.code == 127 } != false)
    val attempt = requireNotNull(join.activeAttempt)
    require(attempt.finishedEpochMs == null)
    val graph = withJob(join.job.copy(status = DownloadStatus.RUNNING, representationId = command.representationId,
        validatorGeneration = command.validatorGeneration, etag = command.etag, lastModified = command.lastModified,
        updatedEpochMs = command.nowEpochMs)).withAttempt(attempt.copy(representationId = command.representationId,
        validatorGeneration = command.validatorGeneration, etag = command.etag, lastModified = command.lastModified))
        .refreshBatchDownloadStates(join.job.id, command.nowEpochMs)
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applyBeginValidation(command: BeginPhysicalDownloadValidation): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence, command.nowEpochMs) || join.job.status != DownloadStatus.RUNNING ||
        join.job.maintenanceKind != null || join.demand() != DownloadClaimDemand.CONTINUE) return physicalStale()
    require(!join.job.representationId.isNullOrBlank() && join.job.bytesDownloaded > 0)
    val attempt = requireNotNull(join.activeAttempt)
    require(attempt.finishedEpochMs == null)
    val graph = withJob(join.job.copy(status = DownloadStatus.VERIFYING, updatedEpochMs = command.nowEpochMs))
        .withAttempt(attempt.copy(status = DownloadAttemptStatus.VALIDATING)).refreshBatchDownloadStates(join.job.id, command.nowEpochMs)
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applyReconcileIntent(command: ReconcilePhysicalDownloadIntent): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    if (join.job.producer != command.expectedProducer || join.job.ownershipGeneration != command.expectedOwnershipGeneration ||
        !join.matchesClaims(command.expectedClaims)) return physicalStale()
    // Automatic reconciliation never clears FAILED. Explicit host resume only lifts a host hold.
    val allow = command.allowHostResume && join.job.status != DownloadStatus.FAILED
    return join.committedBy(reconcileJob(join, command.nowEpochMs, allow))
}
