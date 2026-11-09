package dev.veyra.data.room

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext

private fun AcquisitionGraph.requestedContext(request: AcquisitionRequestId, binding: ExecutionBindingId): AcquisitionExecutionContext =
    state().executionContext(binding).also { require(it.request.id == request) }

/** SAME subject AND accepted C; neither completion nor an empty old path waives conflicts. */
private fun AcquisitionGraph.admitPhysicalJob(
    producer: PhysicalDownloadProducer, proposedId: String, now: Long,
): Pair<AcquisitionGraph, String> {
    require(producer.recordingId != null && producer.bindingId != null && proposedId.isNotBlank() && now >= 0)
    val current = state()
    val existing = current.physicalDownloads.values.singleOrNull { it.producer.subjectId == producer.subjectId }
    if (existing != null) {
        if (existing.producer.recordingId == null) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required"))
        if (existing.producer.recordingId != producer.recordingId) acquisitionInvalid("download_has_different_recording")
        if (existing.cleanupPending || existing.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) {
            acquisitionInvalid("download_cleanup_reserved")
        }
        return this to existing.id
    }
    require(proposedId !in current.physicalDownloads)
    val job = PhysicalDownloadJob(proposedId, producer, createdEpochMs = now, updatedEpochMs = now)
    return withJob(job) to job.id
}

private fun AcquisitionGraph.admitStandaloneClaim(
    jobId: String, requestedBindingId: ExecutionBindingId, proposedId: DownloadClaimId, now: Long, schema2: Boolean,
): Pair<AcquisitionGraph, DownloadClaimId> {
    val state = state()
    val existing = state.claims.values.singleOrNull { it.physicalJobId == jobId && it.owner !is DownloadClaimOwner.BatchMember }
    if (existing != null) return this to existing.id // Identity/intent is retained; resume is an explicit generation-CAS command.
    require(proposedId !in state.claims)
    val claim = DownloadClaim(proposedId,
        if (schema2) DownloadClaimOwner.Schema2Standalone(jobId) else DownloadClaimOwner.Standalone(jobId),
        jobId, requestedBindingId, DownloadBatchIntent.ACTIVE, true, now)
    return withClaim(claim) to claim.id
}

internal fun AcquisitionGraph.applyAdmitStandalone(command: AdmitStandalonePhysicalDownload): AcquisitionMutation<PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission>> {
    val context = requestedContext(command.requestId, command.requiredBindingId)
    require(command.nowEpochMs >= context.receipt.observedEpochMs)
    val producer = PhysicalDownloadProducer.Typed(context.subject.id, context.binding.id, context.recording.id)
    val (jobGraph, jobId) = admitPhysicalJob(producer, command.proposedDownloadId, command.nowEpochMs)
    val (next, claimId) = jobGraph.admitStandaloneClaim(jobId, command.requiredBindingId, command.proposedClaimId, command.nowEpochMs, false)
    return next.physicalCommitted { actual -> StandalonePhysicalDownloadAdmission(
        actual.requestedContext(command.requestId, command.requiredBindingId), actual.state().claims.getValue(claimId), requireNotNull(actual.physicalJoin(jobId)),
    ) }
}

internal fun AcquisitionGraph.applyAdmitSchema2Standalone(command: AdmitSchema2StandalonePhysicalDownload): AcquisitionMutation<PhysicalDownloadCommit<Schema2StandalonePhysicalDownloadAdmission>> {
    val state = state()
    val binding = state.bindings.getValue(command.requiredBindingId)
    require(binding.origin == ExecutionBindingOrigin.SCHEMA2_EXECUTABLE && binding.subjectId.value == command.mediaId)
    val original = snapshot().playbackBindings.getValue(command.requiredBindingId.value)
    require(original.mediaId == command.mediaId && original.selectedVideoId != null && command.nowEpochMs >= original.resolvedEpochMs)
    val producer = PhysicalDownloadProducer.Schema2(command.mediaId, binding.id, requireNotNull(binding.recordingId))
    val (jobGraph, jobId) = admitPhysicalJob(producer, command.proposedDownloadId, command.nowEpochMs)
    val (next, claimId) = jobGraph.admitStandaloneClaim(jobId, binding.id, command.proposedClaimId, command.nowEpochMs, true)
    return next.physicalCommitted { actual -> Schema2StandalonePhysicalDownloadAdmission(
        actual.snapshot().playbackBindings.getValue(command.requiredBindingId.value), actual.state().claims.getValue(claimId), requireNotNull(actual.physicalJoin(jobId)),
    ) }
}

internal fun AcquisitionGraph.batchMember(expected: BatchDownloadMemberExpectation): Pair<DownloadBatch, DownloadBatchMember>? {
    val state = state()
    val batch = state.batches[expected.batchId] ?: return null
    val member = batch.members.singleOrNull { it.position == expected.memberPosition } ?: return null
    if (batch.intentRevision != expected.intentRevision || member.occurrenceId != expected.occurrenceId ||
        member.requestId != expected.requestId || member.requiredBindingId != expected.requestedBindingId ||
        member.claimId != expected.previouslyAttachedClaimId) return null
    val occurrence = state.occurrences.getValue(member.occurrenceId)
    if (occurrence.bindingId != member.requiredBindingId || occurrence.selectionState != OccurrenceSelectionState.BOUND ||
        occurrence.scopeMember != AdmissionScopeMemberRef(batch.scopeId, member.scopeMemberPosition)) return null
    requestedContext(member.requestId, expected.requestedBindingId)
    return batch to member
}

internal fun AcquisitionGraph.applyAdmitBatchPhysical(command: AdmitBatchPhysicalDownload): AcquisitionMutation<PhysicalDownloadCommit<BatchPhysicalDownloadAdmission>> {
    val (batch, member) = batchMember(command.member) ?: return physicalStale()
    if (batch.intent != DownloadBatchIntent.ACTIVE) return physicalStale()
    val context = requestedContext(member.requestId, command.member.requestedBindingId)
    val (jobGraph, jobId) = admitPhysicalJob(PhysicalDownloadProducer.Typed(context.subject.id, context.binding.id, context.recording.id),
        command.proposedDownloadId, command.nowEpochMs)
    var next = jobGraph
    val claimId = member.claimId ?: command.proposedClaimId.also { id ->
        require(id !in next.state().claims)
        val claim = DownloadClaim(id, DownloadClaimOwner.BatchMember(batch.id, member.position), jobId,
            context.binding.id, batch.intent, true, command.nowEpochMs, ownerIntentRevision = batch.intentRevision)
        next = next.withClaim(claim).withBatch(batch.copy(members = batch.members.map {
            if (it.position == member.position) it.copy(claimId = id) else it
        }))
    }
    val claim = next.state().claims.getValue(claimId)
    require(claim.owner == DownloadClaimOwner.BatchMember(batch.id, member.position) && claim.physicalJobId == jobId &&
        claim.requestedBindingId == context.binding.id && claim.intent == batch.intent && claim.ownerIntentRevision == batch.intentRevision)
    next = next.refreshBatchDownloadStates(jobId, command.nowEpochMs)
    return next.physicalCommitted { actual -> BatchPhysicalDownloadAdmission(command.member,
        actual.state().claims.getValue(claimId), actual.requestedContext(member.requestId, context.binding.id), requireNotNull(actual.physicalJoin(jobId))) }
}

internal fun AcquisitionGraph.applyReconcileClaim(command: ReconcilePhysicalDownloadClaim): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val (batch, member) = batchMember(command.member) ?: return physicalStale()
    val claim = state().claims[command.claimId] ?: return physicalStale()
    if (member.claimId != claim.id || claim.generation != command.expectedClaimGeneration) return physicalStale()
    require(claim.intent == batch.intent && claim.ownerIntentRevision == batch.intentRevision)
    val join = requireNotNull(physicalJoin(claim.physicalJobId))
    return join.committedBy(reconcileJob(join, command.nowEpochMs))
}

internal fun AcquisitionGraph.applyStandaloneIntent(command: UpdateStandaloneDownloadIntent): AcquisitionMutation<PhysicalDownloadCommit<PhysicalDownloadJoin>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    val claim = join.claims.singleOrNull { it.id == command.claimId } ?: return physicalStale()
    if (join.job.producer != command.expectedProducer || join.job.ownershipGeneration != command.expectedOwnershipGeneration ||
        claim.generation != command.expectedClaimGeneration || claim.ownerIntentRevision != command.expectedOwnerIntentRevision ||
        claim.owner is DownloadClaimOwner.BatchMember) return physicalStale()
    if (command.intent == DownloadBatchIntent.ACTIVE && join.job.producer.recordingId == null) {
        throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "download_legacy_acquisition_required"))
    }
    require(command.nowEpochMs >= maxOf(join.job.updatedEpochMs, claim.updatedEpochMs) &&
        claim.generation < Long.MAX_VALUE && claim.ownerIntentRevision < Long.MAX_VALUE)
    if ((join.job.cleanupPending || join.job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP) &&
        (command.intent != DownloadBatchIntent.CANCELLED || command.retainCompletedAsset && !claim.retainCompletedAsset)) return physicalStale()
    require(!command.allowRetryFailure || command.intent == DownloadBatchIntent.ACTIVE)
    var next = this
    if (claim.intent != command.intent || claim.retainCompletedAsset != command.retainCompletedAsset || command.allowRetryFailure) {
        next = next.withClaim(claim.copy(intent = command.intent, retainCompletedAsset = command.retainCompletedAsset,
            generation = claim.generation + 1, ownerIntentRevision = claim.ownerIntentRevision + 1, updatedEpochMs = command.nowEpochMs))
    }
    val updated = requireNotNull(next.physicalJoin(join.job.id))
    next = next.reconcileJob(updated, command.nowEpochMs, command.allowRetryFailure)
    return join.committedBy(next)
}

internal fun AcquisitionGraph.applyAdoptSchema2(command: AdoptSchema2PhysicalDownload, owner: LibraryTransactionOwner): AcquisitionMutation<PhysicalDownloadCommit<StandalonePhysicalDownloadAdmission>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    val job = join.job
    val claim = join.claims.singleOrNull { it.id == command.claimId } ?: return physicalStale()
    if (job.producer != command.expectedProducer || job.ownershipGeneration != command.expectedOwnershipGeneration ||
        claim.generation != command.expectedClaimGeneration || claim.ownerIntentRevision != command.expectedOwnerIntentRevision) return physicalStale()
    require(job.producer is PhysicalDownloadProducer.Schema2 && job.producer.recordingId == null &&
        claim.owner == DownloadClaimOwner.Schema2Standalone(job.id) && claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset)
    require(join.claims.all { it.intent == DownloadBatchIntent.CANCELLED && !it.retainCompletedAsset })
    require(job.stagingPath == null && job.assetId == null && join.finalization == null && job.leaseOwner == null &&
        job.leaseExpiresEpochMs == null && job.activeAttemptId == null && !job.cleanupPending && job.maintenanceKind == null &&
        job.bytesDownloaded == 0L && job.totalBytes == null && join.attempts.all { it.finishedEpochMs != null })
    val context = requestedContext(command.requestId, command.requiredBindingId)
    require(context.subject == ExecutableSubject.CatalogMedia(job.producer.subjectId, command.expectedProducer.mediaId))
    require(command.nowEpochMs >= maxOf(job.updatedEpochMs, claim.updatedEpochMs, context.receipt.observedEpochMs) &&
        job.ownershipGeneration < Long.MAX_VALUE && claim.generation < Long.MAX_VALUE && claim.ownerIntentRevision < Long.MAX_VALUE)
    command.drainReceipt.consume(owner, job, join.claims)
    val generation = job.ownershipGeneration + 1
    val adoption = PhysicalDownloadAdoptionEntity(job.id, generation, job.ownershipGeneration, command.expectedProducer.mediaId,
        command.expectedProducer.bindingId?.value, null, context.request.id.value, context.binding.id.value,
        context.recording.id.value, context.subject.id.value, command.nowEpochMs)
    val next = copy(rows = rows.copy(physicalDownloadAdoptionRows = rows.physicalDownloadAdoptionRows.withImmutableRow(adoption) {
        it.jobId to it.newOwnershipGeneration
    })).withJob(job.copy(producer = PhysicalDownloadProducer.Typed(context.subject.id, context.binding.id, context.recording.id),
        status = DownloadStatus.QUEUED, bytesDownloaded = 0, totalBytes = null, representationId = null, etag = null, lastModified = null,
        validatorGeneration = 0, failure = null, nextRetryEpochMs = null, userPaused = false, removeWhenClean = false,
        automaticRetryCount = 0, lastProgressEpochMs = null, updatedEpochMs = command.nowEpochMs, ownershipGeneration = generation))
        .withClaim(claim.copy(intent = DownloadBatchIntent.ACTIVE, retainCompletedAsset = true, generation = claim.generation + 1,
            ownerIntentRevision = claim.ownerIntentRevision + 1, updatedEpochMs = command.nowEpochMs))
    return next.physicalCommitted { actual -> StandalonePhysicalDownloadAdmission(actual.requestedContext(command.requestId, command.requiredBindingId),
        actual.state().claims.getValue(claim.id), requireNotNull(actual.physicalJoin(job.id))) }
}

internal fun AcquisitionGraph.addAssetUse(
    join: PhysicalDownloadJoin, bindingId: ExecutionBindingId, expectedAsset: MaterializationAsset, policy: Int, now: Long,
): AcquisitionGraph {
    require(policy == 1 && now >= expectedAsset.completedEpochMs && join.job.status == DownloadStatus.COMPLETE &&
        join.asset == expectedAsset && join.job.assetId == expectedAsset.id && !join.job.cleanupPending &&
        join.job.maintenanceKind != PhysicalDownloadMaintenanceKind.CLEANUP && join.finalization == null)
    val binding = state().bindings.getValue(bindingId)
    require(binding.subjectId == join.job.producer.subjectId && binding.recordingId != null && binding.recordingId == join.job.producer.recordingId)
    require(!expectedAsset.representationId.isNullOrBlank() && expectedAsset.representationId == join.job.representationId &&
        expectedAsset.validatorGeneration == join.job.validatorGeneration && expectedAsset.sha256?.let(::validHash) == true &&
        state().materializationReceipts[expectedAsset.id] == expectedAsset.receipt())
    val prior = state().assetUses.singleOrNull { it.requestedBindingId == bindingId && it.materializationAssetId == expectedAsset.id }
    // Revalidation never rewrites when/how this exact historical compatibility assertion was recorded.
    val use = prior ?: AssetUse(bindingId, expectedAsset.id, policy, now)
    return copy(rows = rows.copy(assetUseRows = rows.assetUseRows.withImmutableRow(use.toEntity()) {
        it.requestedBindingId to it.materializationAssetId
    })).refreshBatchDownloadStates(join.job.id, now)
}

internal fun AcquisitionGraph.applyMaterializationUse(command: CommitMaterializationUse): AcquisitionMutation<PhysicalDownloadCommit<AssetUse>> {
    val (_, member) = batchMember(command.member) ?: return physicalStale()
    val claim = state().claims[command.claimId] ?: return physicalStale()
    if (member.claimId != claim.id || claim.generation != command.expectedClaimGeneration ||
        claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset) return physicalStale()
    val join = requireNotNull(physicalJoin(claim.physicalJobId))
    if (join.job.ownershipGeneration != command.expectedOwnershipGeneration) return physicalStale()
    val binding = command.member.requestedBindingId
    val next = addAssetUse(join, binding, command.expectedAsset, command.compatibilityPolicyVersion, command.nowEpochMs)
    return next.physicalCommitted { actual -> actual.state().assetUses.single {
        it.requestedBindingId == binding && it.materializationAssetId == command.expectedAsset.id
    } }
}

internal fun AcquisitionGraph.applyStandaloneUse(command: CommitStandaloneMaterializationUse): AcquisitionMutation<PhysicalDownloadCommit<AssetUse>> {
    val join = physicalJoin(command.downloadId) ?: return physicalStale()
    val claim = join.claims.singleOrNull { it.id == command.claimId } ?: return physicalStale()
    if (claim.owner is DownloadClaimOwner.BatchMember || claim.generation != command.expectedClaimGeneration ||
        join.job.ownershipGeneration != command.expectedOwnershipGeneration || claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset) return physicalStale()
    val next = addAssetUse(join, command.requestedBindingId, command.expectedAsset, command.compatibilityPolicyVersion, command.nowEpochMs)
    return next.physicalCommitted { actual -> actual.state().assetUses.single {
        it.requestedBindingId == command.requestedBindingId && it.materializationAssetId == command.expectedAsset.id
    } }
}
