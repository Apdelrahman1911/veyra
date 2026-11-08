package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.downloads.DownloadClaimDemand
import dev.veyra.model.*

internal fun AcquisitionGraph.applyPrepareFinalization(command: PreparePhysicalDownloadFinalization): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence, command.nowEpochMs) || join.job.maintenanceKind != null || join.job.cleanupPending ||
        join.demand() != DownloadClaimDemand.CONTINUE || join.job.status != DownloadStatus.VERIFYING) return physicalStale()
    val intent = command.finalization
    val attempt = requireNotNull(join.activeAttempt)
    require(attempt.finishedEpochMs == null && attempt.status in setOf(DownloadAttemptStatus.VALIDATING, DownloadAttemptStatus.FINALIZING))
    require(intent.downloadId == join.job.id && intent.producer == join.job.producer && intent.attemptId == attempt.id &&
        intent.ownershipGeneration == command.fence.ownershipGeneration && intent.stagingPath == join.job.stagingPath &&
        intent.stagingPath != intent.finalPath && intent.finalPath.isNotBlank() && intent.sizeBytes == join.job.bytesDownloaded &&
        intent.sizeBytes > 0 && validHash(intent.sha256) && !intent.representationId.isNullOrBlank() &&
        intent.representationId == join.job.representationId && intent.validatorGeneration == join.job.validatorGeneration)
    if (join.finalization != null) require(join.finalization == intent) { "Finalization identity changed" }
    val graph = withFinalization(intent).withAttempt(attempt.copy(status = DownloadAttemptStatus.FINALIZING))
        .withJob(join.job.copy(updatedEpochMs = command.nowEpochMs))
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applyPublishFinalization(command: PublishPhysicalDownloadFinalization): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence, command.nowEpochMs) || join.finalization != command.expectedFinalization || join.job.cleanupPending ||
        join.job.userPaused || join.demand() != DownloadClaimDemand.CONTINUE ||
        join.job.maintenanceKind !in setOf(null, PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION)) return physicalStale()
    val intent = requireNotNull(join.finalization)
    val asset = command.asset
    val expectedId = intent.assetId ?: if (intent.producer is PhysicalDownloadProducer.Schema2) {
        MaterializationAssetId("${intent.downloadId}-${intent.attemptId ?: intent.validatorGeneration}")
    } else acquisitionInvalid("download_finalization_asset_missing")
    require(asset.id == expectedId && asset.producer == intent.producer && asset.uri == intent.finalPath &&
        asset.sizeBytes == intent.sizeBytes && asset.kind == OfflineAssetKind.FILE && asset.sha256 == intent.sha256 &&
        asset.mimeType == intent.mimeType && asset.codecs == intent.codecs && asset.measuredDurationMs == intent.mediaDurationMs &&
        asset.representationId == intent.representationId && !asset.representationId.isNullOrBlank() &&
        asset.validatorGeneration == intent.validatorGeneration && asset.materializerDownloadId == intent.downloadId &&
        asset.materializerAttemptId == intent.attemptId && asset.ownershipGeneration == intent.ownershipGeneration &&
        asset.completedEpochMs in join.job.updatedEpochMs..command.nowEpochMs)
    require(join.job.representationId == intent.representationId && join.job.validatorGeneration == intent.validatorGeneration)
    val materialized = withAsset(asset)
    var graph = materialized.copy(library = materialized.library.copy(
        finalizations = library.finalizations.filterNot { it.downloadId == join.job.id },
    ))
    intent.attemptId?.let { id ->
        val attempt = join.attempts.single { it.id == id }
        require(attempt.producer == intent.producer && attempt.ownershipGeneration == intent.ownershipGeneration)
        // A recovery maintenance lease never rewrites a previously retired materializer record.
        if (attempt.finishedEpochMs == null) graph = graph.withAttempt(attempt.copy(status = DownloadAttemptStatus.SUCCEEDED,
            finishedEpochMs = command.nowEpochMs, bytesDownloaded = asset.sizeBytes, totalBytes = asset.sizeBytes))
    }
    graph = graph.withJob(join.job.copy(status = DownloadStatus.COMPLETE, bytesDownloaded = asset.sizeBytes,
        totalBytes = asset.sizeBytes, assetId = asset.id, stagingPath = null, activeAttemptId = null, leaseOwner = null,
        leaseExpiresEpochMs = null, maintenanceKind = null, failure = null, nextRetryEpochMs = null,
        cleanupPending = false, removeWhenClean = false, userPaused = false, updatedEpochMs = command.nowEpochMs))
    // The verified materializer R1 needs its own exact use in this SAME publication transaction.
    // Rejoin current claims, not a pre-validation caller snapshot: removal can win while another
    // R2 keeps the physical transfer alive. Same-C R2 claims never inherit an origin grant.
    // Unknown/adopted schema2 requested bindings remain historical facts, not invented R1 uses.
    val originBinding = intent.producer.bindingId
    graph = if (originBinding != null && intent.producer.recordingId != null && join.claims.any {
            it.requestedBindingId == originBinding && (it.intent != DownloadBatchIntent.CANCELLED || it.retainCompletedAsset)
        }) {
        graph.addAssetUse(requireNotNull(graph.physicalJoin(join.job.id)), originBinding, asset,
            policy = 1, now = command.nowEpochMs)
    } else graph.refreshBatchDownloadStates(join.job.id, command.nowEpochMs)
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applyFinishAttempt(command: FinishPhysicalDownloadAttempt): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence) || join.job.maintenanceKind != null) return physicalStale()
    require(command.nowEpochMs >= join.job.updatedEpochMs && command.automaticRetryCount >= join.job.automaticRetryCount &&
        (command.nextRetryEpochMs == null || command.nextRetryEpochMs >= command.nowEpochMs))
    require(command.status !in setOf(DownloadStatus.COMPLETE, DownloadStatus.RESOLVING, DownloadStatus.RUNNING))
    require(command.attemptStatus in setOf(DownloadAttemptStatus.FAILED, DownloadAttemptStatus.PAUSED, DownloadAttemptStatus.CANCELLED))
    val attempt = requireNotNull(join.activeAttempt)
    require(attempt.finishedEpochMs == null)
    val status = if (join.finalization != null && command.status == DownloadStatus.QUEUED) DownloadStatus.VERIFYING else command.status
    var graph = withAttempt(attempt.copy(status = command.attemptStatus, finishedEpochMs = command.nowEpochMs, failure = command.failure))
        .withJob(join.job.copy(status = status, activeAttemptId = null, leaseOwner = null, leaseExpiresEpochMs = null,
            failure = command.failure, nextRetryEpochMs = command.nextRetryEpochMs, automaticRetryCount = command.automaticRetryCount,
            updatedEpochMs = command.nowEpochMs))
    graph = graph.reconcileJob(requireNotNull(graph.physicalJoin(join.job.id)), command.nowEpochMs)
    return join.committedBy(graph)
}

internal fun AcquisitionGraph.applyClaimMaintenance(command: ClaimPhysicalDownloadMaintenance): AcquisitionMutation<PhysicalDownloadCommit<ClaimedPhysicalDownload>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    val job = join.job
    val explicitValidate = command.explicitLocalRevalidation
    if (job.producer != command.expectedProducer || job.ownershipGeneration != command.expectedOwnershipGeneration ||
        job.leaseOwner != null && (job.leaseExpiresEpochMs ?: 0) > command.nowEpochMs ||
        !explicitValidate && (job.nextRetryEpochMs ?: 0) > command.nowEpochMs) return physicalStale()
    require(!explicitValidate || command.kind == PhysicalDownloadMaintenanceKind.VALIDATE &&
        command.requestedBindingId != null)
    require(command.owner.isNotBlank() && command.leaseDurationMs in 1_000..600_000 && command.nowEpochMs >= job.updatedEpochMs &&
        command.nowEpochMs <= Long.MAX_VALUE - command.leaseDurationMs && job.ownershipGeneration < Long.MAX_VALUE)
    val eligible = when (command.kind) {
        PhysicalDownloadMaintenanceKind.CLEANUP -> job.cleanupPending
        PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION -> !job.cleanupPending && !job.userPaused && join.finalization != null &&
            join.demand() == DownloadClaimDemand.CONTINUE && job.producer.recordingId != null && job.failure?.code !in terminalLocalFailures
        PhysicalDownloadMaintenanceKind.VALIDATE -> !job.cleanupPending && join.asset != null && job.producer.recordingId != null &&
            (job.status == DownloadStatus.COMPLETE || explicitValidate && job.status == DownloadStatus.FAILED) &&
            (!explicitValidate || join.claims.any { claim ->
                (claim.requestedBindingId == command.requestedBindingId ||
                    claim.owner is DownloadClaimOwner.Standalone ||
                    claim.owner is DownloadClaimOwner.Schema2Standalone) &&
                    (claim.intent != DownloadBatchIntent.CANCELLED || claim.retainCompletedAsset)
            })
    }
    if (!eligible || command.kind != PhysicalDownloadMaintenanceKind.CLEANUP &&
        (job.status == DownloadStatus.NEEDS_FOREGROUND || job.failure?.code == FailureCode.BACKGROUND_RESTRICTED) ||
        job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP && command.kind != PhysicalDownloadMaintenanceKind.CLEANUP) return physicalStale()
    val changed = job.copy(ownershipGeneration = job.ownershipGeneration + 1, leaseOwner = command.owner,
        leaseExpiresEpochMs = command.nowEpochMs + command.leaseDurationMs, maintenanceKind = command.kind,
        status = if (command.kind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION) DownloadStatus.VERIFYING else job.status,
        updatedEpochMs = command.nowEpochMs)
    // Logical retirement of an abandoned writer is not a native/file drain acknowledgement.
    // Retain its identity for cleanup/validation fences; recovery instead uses the exact intent
    // attempt (including null). A successful recovery must not leave an orphan unfinished
    // attempt or rewrite already retired history as if that old writer completed the work.
    val abandoned = join.activeAttempt?.takeIf { it.finishedEpochMs == null }
    val next = (abandoned?.let { withAttempt(it.copy(status = DownloadAttemptStatus.PAUSED,
        finishedEpochMs = maxOf(command.nowEpochMs, it.startedEpochMs))) } ?: this).withJob(changed)
    return next.physicalCommitted { actual ->
        val rejoined = requireNotNull(actual.physicalJoin(job.id))
        val id = if (command.kind == PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION) rejoined.finalization?.attemptId else rejoined.job.activeAttemptId
        val attempt = id?.let { value -> rejoined.attempts.single { it.id == value } }
        ClaimedPhysicalDownload(physicalFence(rejoined.job, attempt), rejoined)
    }
}

internal fun AcquisitionGraph.applyFinishMaintenance(command: FinishPhysicalDownloadMaintenance): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.fence.downloadId) ?: return physicalStale()
    if (!join.matches(command.fence) || join.job.maintenanceKind != command.kind || !join.matchesClaims(command.expectedClaims)) return physicalStale()
    require(command.nowEpochMs >= join.job.updatedEpochMs && (command.nextRetryEpochMs == null || command.nextRetryEpochMs >= command.nowEpochMs))
    require(!command.cleanupCompleted || command.kind == PhysicalDownloadMaintenanceKind.CLEANUP && command.failure == null)
    command.retainedValidation?.let { retained ->
        require(command.kind == PhysicalDownloadMaintenanceKind.VALIDATE && command.failure == null &&
            !command.cleanupCompleted && command.status == null && command.nextRetryEpochMs == null)
        require(retained.compatibilityPolicyVersion >= 1)
        val asset = join.asset ?: return physicalStale()
        if (join.job.status != DownloadStatus.FAILED || join.job.cleanupPending || join.finalization != null ||
            join.job.assetId != retained.expectedAsset.id || asset != retained.expectedAsset ||
            join.claims.singleOrNull { it.id == retained.expectedClaim.id } != retained.expectedClaim ||
            retained.expectedClaim.intent == DownloadBatchIntent.CANCELLED && !retained.expectedClaim.retainCompletedAsset ||
            retained.expectedClaim.owner is DownloadClaimOwner.BatchMember &&
                retained.requestedBindingId != retained.expectedClaim.requestedBindingId)
            return physicalStale()
        val completed = join.job.copy(status = DownloadStatus.COMPLETE, failure = null, nextRetryEpochMs = null,
            maintenanceKind = null, leaseOwner = null, leaseExpiresEpochMs = null, updatedEpochMs = command.nowEpochMs)
        var graph = withJob(completed)
        graph = graph.addAssetUse(requireNotNull(graph.physicalJoin(join.job.id)), retained.requestedBindingId,
            retained.expectedAsset, retained.compatibilityPolicyVersion, command.nowEpochMs)
        return join.committedBy(graph)
    }
    var graph = this
    var job = join.job
    if (command.kind == PhysicalDownloadMaintenanceKind.CLEANUP && command.cleanupCompleted) {
        val reservation = requireNotNull(join.cleanupReservation)
        val removeAsset = reservation.removeCompletedAsset
        graph = copy(library = library.copy(
            finalizations = library.finalizations.filterNot { it.downloadId == job.id },
            assets = if (removeAsset) library.assets.filterNot { it.id == job.assetId?.value } else library.assets,
        ))
        // Tombstones and every original attempt path/producer are history, never row-deleted.
        join.activeAttempt?.takeIf { it.finishedEpochMs == null }?.let { attempt ->
            graph = graph.withAttempt(attempt.copy(status = DownloadAttemptStatus.CANCELLED, finishedEpochMs = command.nowEpochMs))
        }
        job = job.copy(stagingPath = null, assetId = if (removeAsset) null else job.assetId, activeAttemptId = null,
            bytesDownloaded = if (!removeAsset && join.asset != null) join.asset.sizeBytes else 0,
            totalBytes = if (!removeAsset && join.asset != null) join.asset.sizeBytes else null,
            etag = null, lastModified = null, cleanupPending = false, maintenanceKind = null,
            status = if (!removeAsset && join.asset != null) DownloadStatus.COMPLETE else DownloadStatus.CANCELLED,
            failure = null, nextRetryEpochMs = null)
    } else {
        // Failed/interrupted cleanup keeps its durable irreversible reservation even with no
        // live lease. A later actual owner may safely repeat idempotent deletion under locks.
        val keepReservation = command.kind == PhysicalDownloadMaintenanceKind.CLEANUP
        val status = command.status ?: when {
            command.failure?.code == FailureCode.BACKGROUND_RESTRICTED -> DownloadStatus.NEEDS_FOREGROUND
            command.failure != null && !keepReservation -> DownloadStatus.FAILED
            else -> job.status
        }
        require(status != DownloadStatus.COMPLETE || job.assetId != null)
        job = job.copy(maintenanceKind = if (keepReservation) PhysicalDownloadMaintenanceKind.CLEANUP else null,
            status = status, failure = command.failure ?: job.failure, nextRetryEpochMs = command.nextRetryEpochMs ?: job.nextRetryEpochMs)
    }
    job = job.copy(leaseOwner = null, leaseExpiresEpochMs = null, updatedEpochMs = command.nowEpochMs)
    graph = graph.withJob(job).refreshBatchDownloadStates(job.id, command.nowEpochMs)
    return join.committedBy(graph)
}

private val terminalLocalFailures = setOf(FailureCode.CORRUPT_MEDIA, FailureCode.UNSUPPORTED_CODEC,
    FailureCode.RECOVERY_REQUIRED, FailureCode.INVALID_STATE, FailureCode.NOT_FOUND)
