package dev.veyra.acquisition

import dev.veyra.data.AcquisitionRepository
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Exact-identity orchestration of the existing executor port. No file, downloader, MediaItem
 * stand-in, hidden selection, automatic retry or claim-after-enqueue transaction exists here.
 * The executor still owns transactional revalidation and every physical resource on all paths.
 */
class AcquisitionDownloadAdapter(
    private val repository: AcquisitionRepository,
    private val acquisition: AcquisitionCoordinator,
    private val executor: AcquisitionDownloadExecutor,
) {
    init {
        require(repository.transactionOwner === executor.transactionOwner &&
            repository.transactionOwner === acquisition.transactionOwner) {
            "Acquisition adapter, selection and download executor must share one Room owner"
        }
    }

    suspend fun admitMember(
        batchId: DownloadBatchId,
        memberPosition: Int,
        expectedIntentRevision: Long,
    ): AcquisitionDownloadAdmission {
        currentCoroutineContext().ensureActive()
        val before = member(batchId, memberPosition, expectedIntentRevision, repository.state.value)
        if (before.batch.intent != DownloadBatchIntent.ACTIVE) invalid("download_batch_action_stale")
        val result = executorCall {
            executor.admitMember(AdmitAcquisitionDownload(before.key, before.bound, before.member.claimId))
        }
        currentCoroutineContext().ensureActive()
        val state = repository.state.value
        val after = member(batchId, memberPosition, expectedIntentRevision, state)
        requireSameMember(before, after)
        if (after.batch.intent != DownloadBatchIntent.ACTIVE) invalid("download_batch_action_stale")
        validateFence(result.fence, after, state)
        requireCommittedRevision(result.committedLibraryRevision, state)
        // This is a durable owner admission, not a native start or available asset receipt.
        return result
    }

    /**
     * Exact explicit Retry, independent of batch Resume. Compare the displayed full claim/job
     * before invoking the same executor; the executor and Room independently repeat the CAS.
     * A queued successor may execute after commit, so validate the returned committed reset
     * witness rather than falsely requiring the latest physical status to remain QUEUED.
     */
    suspend fun retryMember(command: RetryAcquisitionDownload): AcquisitionDownloadRetryResult = try {
        currentCoroutineContext().ensureActive()
        val key = command.member
        val initial = repository.state.value
        val before = member(key.batchId, key.memberPosition, key.intentRevision, initial)
        if (before.key != key || before.batch.intent != DownloadBatchIntent.ACTIVE ||
            before.claim != command.expectedClaim || command.expectedClaim.intent != DownloadBatchIntent.ACTIVE ||
            command.expectedClaim.physicalJobId != command.expectedJob.id ||
            initial.physicalDownloads[command.expectedJob.id] != command.expectedJob ||
            command.expectedClaim.generation == Long.MAX_VALUE || !isExplicitMemberRetryCandidate(command.expectedJob)) {
            invalid("download_member_retry_stale")
        }
        val result = executorCall { executor.retryMember(command) }
        currentCoroutineContext().ensureActive()
        when (result) {
            is AcquisitionDownloadRetryResult.Unavailable -> result
            is AcquisitionDownloadRetryResult.Retried -> {
                currentCoroutineContext().ensureActive()
                val state = repository.state.value
                val after = member(key.batchId, key.memberPosition, key.intentRevision, state)
                requireSameMember(before, after)
                val now = result.job.updatedEpochMs
                if (after.batch.intent != DownloadBatchIntent.ACTIVE ||
                    now < maxOf(command.expectedClaim.updatedEpochMs, command.expectedJob.updatedEpochMs) ||
                    result.job != command.expectedJob.afterExplicitMemberRetry(now) ||
                    after.claim != command.expectedClaim.copy(generation = command.expectedClaim.generation + 1, updatedEpochMs = now)) {
                    invalid("download_member_retry_not_committed")
                }
                val job = validateFence(result.admission.fence, after, state)
                if (job.id != result.job.id || job.producer != result.job.producer) invalid("download_member_retry_fence_mismatch")
                // A consumed local epoch may retain its historical attempt identity. The
                // existing fence validator checks that immutable attempt, not an active owner.
                requireCommittedRevision(result.admission.committedLibraryRevision, state)
                result
            }
        }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: VeyraException) { AcquisitionDownloadRetryResult.Unavailable(failure.failure) }
    catch (_: Throwable) { AcquisitionDownloadRetryResult.Unavailable(AppFailure(FailureCode.UNEXPECTED, "download_member_retry_failed")) }

    /** Call after setIntent; physical quiescence and durable settlement stay separate results. */
    suspend fun reconcileMember(
        batchId: DownloadBatchId,
        memberPosition: Int,
        expectedIntentRevision: Long,
    ): AcquisitionDownloadReconciliation {
        currentCoroutineContext().ensureActive()
        val before = member(batchId, memberPosition, expectedIntentRevision, repository.state.value)
        val claim = before.claim ?: invalid("download_member_claim_missing")
        val result = executorCall { executor.reconcileClaim(ReconcileAcquisitionDownload(before.key, claim)) }
        currentCoroutineContext().ensureActive()
        val state = repository.state.value
        val after = member(batchId, memberPosition, expectedIntentRevision, state)
        requireSameMember(before, after)
        validateFence(result.fence, after, state)
        when (val durable = result.durable) {
            is AcquisitionDownloadDurableSettlement.Confirmed -> requireCommittedRevision(durable.committedLibraryRevision, state)
            is AcquisitionDownloadDurableSettlement.Unconfirmed, AcquisitionDownloadDurableSettlement.Stale -> Unit
        }
        val quiescent = result.physical as? AcquisitionDownloadPhysicalSettlement.Quiescent
        if (quiescent != null && quiescent.acknowledgement.fence !== result.fence) {
            invalid("download_quiescence_generation_mismatch")
        }
        return result
    }

    /** Explicit local-only validation; never called by Room-only cold restore or batch admission. */
    suspend fun validateMemberAsset(
        batchId: DownloadBatchId,
        memberPosition: Int,
        expectedIntentRevision: Long,
        materializationAssetId: MaterializationAssetId,
    ): AcquisitionDownloadAssetCheck {
        currentCoroutineContext().ensureActive()
        val before = member(batchId, memberPosition, expectedIntentRevision, repository.state.value)
        val claim = before.claim ?: invalid("download_member_claim_missing")
        if (claim.intent == DownloadBatchIntent.CANCELLED && !claim.retainCompletedAsset) {
            invalid("download_member_removal_pending")
        }
        val result = executorCall { executor.validateAssetUse(ValidateAcquisitionDownloadAsset(before.key, claim, materializationAssetId)) }
        currentCoroutineContext().ensureActive()
        // Preserve the executor's exact rejection receipt through later intent/member changes.
        // It is historical negative evidence, not a current claim/grant/operation ACK. Cancellation
        // still propagates above; successful validations retain every existing post-CAS guard.
        if (result is AcquisitionDownloadAssetCheck.Unavailable) return result
        val state = repository.state.value
        val after = member(batchId, memberPosition, expectedIntentRevision, state)
        requireSameMember(before, after)
        if (after.claim != claim) invalid("download_member_claim_changed")
        if (result is AcquisitionDownloadAssetCheck.Validated) {
            val proof = result.validation
            val job = validateFence(proof.fence, after, state)
            requireCommittedRevision(proof.committedLibraryRevision, state)
            if (proof.use.requestedBindingId != before.key.requestedBindingId ||
                proof.use.materializationAssetId != materializationAssetId || proof.use.compatibilityPolicyVersion < 1 ||
                proof.use.createdEpochMs < 0 || proof.use !in state.assetUses) {
                invalid("download_asset_use_not_committed")
            }
            validateMaterialization(proof.use, job, state)
        }
        return result
    }

    private data class Member(
        val batch: DownloadBatch,
        val member: DownloadBatchMember,
        val bound: BoundAcquisitionOccurrence,
        val key: AcquisitionDownloadMemberKey,
        val claim: DownloadClaim?,
    )

    private fun member(
        batchId: DownloadBatchId,
        position: Int,
        intentRevision: Long,
        state: AcquisitionRepositoryState,
    ): Member {
        val batch = state.batches[batchId] ?: invalid("download_batch_missing")
        if (batch.id != batchId || batch.intentRevision != intentRevision) invalid("download_batch_action_stale")
        val member = batch.members.singleOrNull { it.position == position } ?: invalid("download_batch_member_missing")
        val bindingId = member.requiredBindingId ?: invalid("download_member_not_bound")
        val bound = acquisition.boundOccurrence(member.occurrenceId, bindingId, state)
        val scope = state.scopes[batch.scopeId] ?: invalid("download_batch_scope_missing")
        val scopeMember = scope.members.singleOrNull { it.position == member.scopeMemberPosition }
            ?: invalid("download_batch_scope_member_missing")
        if (bound.request.id != member.requestId || bound.occurrence.scopeMember != AdmissionScopeMemberRef(batch.scopeId, member.scopeMemberPosition) ||
            scope.id != batch.scopeId || scope.controlsMode != bound.occurrence.controlsMode ||
            scopeMember.disposition != AdmissionDisposition.INCLUDED || scopeMember.requestId != member.requestId ||
            scopeMember.snapshotMember != bound.occurrence.snapshotMember) {
            invalid("download_batch_member_lineage_mismatch")
        }
        val claim = member.claimId?.let { state.claims[it] ?: invalid("download_member_claim_missing") }
        if (claim != null && (claim.id != member.claimId || claim.owner != DownloadClaimOwner.BatchMember(batch.id, member.position) ||
                claim.requestedBindingId != bindingId || claim.intent != batch.intent ||
                claim.ownerIntentRevision != batch.intentRevision)) {
            invalid("download_member_claim_mismatch")
        }
        return Member(batch, member, bound, AcquisitionDownloadMemberKey(batch.id, member.position, intentRevision,
            member.occurrenceId, member.requestId, bindingId), claim)
    }

    private fun requireSameMember(before: Member, after: Member) {
        if (before.key != after.key || before.bound != after.bound ||
            before.member.claimId?.let { it != after.member.claimId } == true) {
            invalid("download_batch_member_changed")
        }
    }

    private fun validateFence(
        fence: AcquisitionDownloadFence,
        member: Member,
        state: AcquisitionRepositoryState,
    ): PhysicalDownloadJob {
        if (fence.issuer !== executor || fence.member != member.key || member.claim == null ||
            fence.claim != member.claim || fence.claim.physicalJobId != fence.job.physicalJobId ||
            fence.job.subjectId != member.bound.subject.id || fence.job.recordingId != member.bound.recording.id) {
            invalid("download_executor_fence_mismatch")
        }
        // Same C is necessary but not an AssetUse or Ready proof. Keep R2 on the occurrence/claim
        // and validate the job's original producer R1 separately, including legacy executable R1.
        val producer = state.bindings[fence.job.producerBindingId] ?: invalid("download_producer_binding_missing")
        if (producer.id != fence.job.producerBindingId || producer.subjectId != member.bound.subject.id ||
            producer.recordingId != member.bound.recording.id || producer.origin == ExecutionBindingOrigin.SCHEMA2_NON_EXECUTABLE) {
            invalid("download_has_different_recording")
        }
        val job = state.physicalDownloads[fence.job.physicalJobId] ?: invalid("download_physical_job_missing")
        if (job.id != fence.job.physicalJobId || job.producer.subjectId != fence.job.subjectId ||
            job.producer.bindingId != fence.job.producerBindingId || job.producer.recordingId != fence.job.recordingId ||
            when (val physicalProducer = job.producer) {
                is PhysicalDownloadProducer.Typed -> producer.origin != ExecutionBindingOrigin.TYPED
                is PhysicalDownloadProducer.Schema2 -> producer.origin != ExecutionBindingOrigin.SCHEMA2_EXECUTABLE ||
                    (member.bound.subject as? ExecutableSubject.CatalogMedia)?.mediaId != physicalProducer.mediaId
            }) {
            invalid("download_physical_job_mismatch")
        }
        // A scheduler may already have admitted a successor. These are historical named-epoch
        // observations, not authority over the current active attempt. Publication can also
        // clear activeAttemptId before physical cleanup returns. Check the retained immutable
        // attempt when one was actually named; never infer an ACK from its absence/status.
        fence.generation.attemptId?.let { id ->
            val attempt = state.physicalAttempts[id] ?: invalid("download_physical_attempt_missing")
            if (attempt.id != id || attempt.downloadId != job.id || attempt.producer != job.producer ||
                attempt.number != fence.generation.attemptNumber || attempt.number > job.attemptCount ||
                attempt.ownershipGeneration > job.ownershipGeneration) {
                invalid("download_physical_attempt_mismatch")
            }
        }
        val receipt = state.receipts[producer.firstReceiptId] ?: invalid("download_producer_receipt_missing")
        val recording = member.bound.recording
        if (receipt.id != producer.firstReceiptId || recording.canonicalId.length != 11 ||
            recording.canonicalId.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' && it != '-' } ||
            recording.canonicalUrl != "https://www.youtube.com/watch?v=${recording.canonicalId}") {
            invalid("download_producer_receipt_mismatch")
        }
        val matches = when (receipt) {
            is FirstResultReceipt.Observed -> {
                val observation = receipt.observation
                val record = state.requests[observation.requestId]
                val outcome = record?.outcome as? AcquisitionOutcome.Single
                producer.origin == ExecutionBindingOrigin.TYPED && record != null && outcome != null &&
                    record.state == AcquisitionJobState.COMMITTED &&
                    record.request.id == observation.requestId && record.request.completeTitle == observation.queryTitle &&
                    outcome.requestId == record.request.id && outcome.bindingId == producer.id && outcome.receiptId == receipt.id &&
                    observation.entry.ordinal == 0 && observation.entry.kind == AcquisitionEntryKind.VIDEO &&
                    observation.entry.issue == null && observation.entry.id == recording.canonicalId &&
                    observation.entry.publicUrl == recording.canonicalUrl && observation.canonicalUrl == recording.canonicalUrl &&
                    hasCompleteTitle(observation.queryTitle) && hasCompleteTitle(observation.entry.title)
            }
            is FirstResultReceipt.Schema2 -> producer.origin == ExecutionBindingOrigin.SCHEMA2_EXECUTABLE &&
                member.bound.subject is ExecutableSubject.CatalogMedia &&
                receipt.mediaId == producer.subjectId.value && receipt.selectionOrdinal == 0 &&
                receipt.firstResultKind == FirstResultKind.VIDEO && receipt.firstResultId == recording.canonicalId &&
                receipt.selectedVideoId == recording.canonicalId &&
                hasCompleteTitle(receipt.queryTitle) && hasCompleteTitle(receipt.firstResultTitle) &&
                // A historical NULL locator is absence, not a different C or invented raw URL.
                (receipt.canonicalUrl == null || receipt.canonicalUrl == recording.canonicalUrl)
        }
        if (!matches) invalid("download_producer_receipt_mismatch")
        return job
    }

    private fun validateMaterialization(use: AssetUse, job: PhysicalDownloadJob, state: AcquisitionRepositoryState) {
        val asset = state.materializations[use.materializationAssetId] ?: invalid("download_live_materialization_missing")
        val receipt = state.materializationReceipts[use.materializationAssetId]
            ?: invalid("download_materialization_receipt_missing")
        if (asset.id != use.materializationAssetId || asset.receipt() != receipt || job.assetId != asset.id ||
            asset.producer != job.producer || asset.ownershipGeneration > job.ownershipGeneration ||
            asset.materializerDownloadId?.let { it != job.id } == true || asset.uri.isBlank() || asset.sizeBytes <= 0 ||
            asset.sha256?.let { hash -> hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' } } != true ||
            use.createdEpochMs < asset.completedEpochMs) {
            invalid("download_materialization_mismatch")
        }
        if (job.cleanupPending || job.removeWhenClean || job.maintenanceKind == PhysicalDownloadMaintenanceKind.CLEANUP ||
            job.id in state.physicalFinalizations) {
            invalid("download_materialization_removal_or_finalization_pending")
        }
        // The validation/maintenance epoch can be newer and have NO attempt. Keep original
        // materializer history instead of relabelling it to the current check's generation.
        if (asset.producer is PhysicalDownloadProducer.Typed &&
            (asset.materializerDownloadId == null || asset.materializerAttemptId == null ||
                asset.ownershipGeneration == 0L || asset.representationId.isNullOrBlank())) {
            invalid("download_materializer_lineage_missing")
        }
        asset.materializerAttemptId?.let { id ->
            val attempt = state.physicalAttempts[id] ?: invalid("download_materializer_attempt_missing")
            if (attempt.id != id || attempt.downloadId != job.id || attempt.producer != asset.producer ||
                attempt.ownershipGeneration != asset.ownershipGeneration || attempt.representationId != asset.representationId ||
                attempt.validatorGeneration != asset.validatorGeneration) {
                invalid("download_materializer_attempt_mismatch")
            }
        }
    }

    private fun hasCompleteTitle(value: String?): Boolean {
        if (value == null) return false
        return try { AcquisitionInputRules.completeTitle(value); true }
        catch (_: VeyraException) { false }
    }

    private fun requireCommittedRevision(revision: Long, state: AcquisitionRepositoryState) {
        if (revision < 0 || revision > state.libraryRevision) invalid("download_executor_commit_not_observed")
    }

    private suspend fun <T> executorCall(block: suspend () -> T): T = try { block() }
    catch (failure: CancellationException) { throw failure }
    catch (failure: VeyraException) { throw failure }
    catch (_: Throwable) { throw VeyraException(AppFailure(FailureCode.UNEXPECTED, "download_executor_operation_failed")) }

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}
