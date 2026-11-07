package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.Clock
import dev.veyra.playback.SystemClock
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.execution.AcquisitionGateway
import dev.veyra.source.execution.AcquisitionInspection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selection results are not playback plans, download leases or native handoff acknowledgements. */
sealed interface AcquisitionResolution {
    val record: AcquisitionRecord

    data class Accepted(
        override val record: AcquisitionRecord,
        val attachedOccurrences: Set<ExecutionOccurrenceId>,
        val rejectedAttachments: Set<ExecutionOccurrenceId>,
    ) : AcquisitionResolution

    data class InProgress(override val record: AcquisitionRecord) : AcquisitionResolution
    data class OutcomeUnknown(override val record: AcquisitionRecord) : AcquisitionResolution
    data class Stale(override val record: AcquisitionRecord) : AcquisitionResolution
}

/** A joined, detached durable selection. It contains no media URI, headers or execution owner. */
data class BoundAcquisitionOccurrence(
    val occurrence: ExecutionOccurrence,
    val request: AcquisitionRequest,
    val receipt: FirstResultReceipt.Observed,
    val binding: ExecutionBinding,
    val subject: ExecutableSubject,
    val recording: SourceRecording,
    val selection: SourceAcquisitionResult.Single,
) {
    /** Pure detached projection, not current occurrence/job ownership or native permission. */
    fun executionContext() = AcquisitionExecutionContext(request, receipt, binding, subject, recording)

    override fun toString() = "BoundAcquisitionOccurrence(mode=${occurrence.controlsMode},metadata=redacted)"
}

/**
 * Once-only selection orchestration over the normalized repository and the app-owned gateway.
 * There is no selection loop, mutable media-global binding, source fallback or transfer engine.
 * A request is durable before the gateway is entered, and known results are committed before
 * cancellation can expose them to native/executor callers. A sent call without a durable result
 * remains unknown; only an explicit new request may perform another title search.
 */
class AcquisitionCoordinator(
    private val repository: AcquisitionRepository,
    private val gateway: AcquisitionGateway,
    private val captures: AcquisitionCaptureFactory = AcquisitionCaptureFactory(),
    private val clock: Clock = SystemClock(),
    private val offline: () -> Boolean,
    private val owner: String = newEntityId("acquisition-owner"),
    private val leaseDurationMs: Long = 60_000,
) {
    val transactionOwner: LibraryTransactionOwner get() = repository.transactionOwner
    init { require(owner.isNotBlank() && leaseDurationMs in 1_000..600_000) }

    /** Room-only restore. It never selects, inspects, renews artwork or starts playback/downloads. */
    suspend fun restore(): AcquisitionRepositoryState = repository.initialize()

    suspend fun capture(
        metadata: RequestedMetadata,
        expectedCatalogRevision: Long? = null,
        providerRevision: String? = null,
    ): AcquisitionRecord = repository.capture(CaptureAcquisition(
        captures.capture(metadata, expectedCatalogRevision, providerRevision), expectedCatalogRevision,
    ))

    /** This is an explicit new request, not an in-place retry of an unknown/accepted old request. */
    suspend fun resolveAgain(requestId: AcquisitionRequestId): AcquisitionRecord {
        val previous = repository.request(requestId) ?: invalid("acquisition_request_missing")
        return repository.capture(CaptureAcquisition(captures.resolveAgain(previous.request)))
    }

    suspend fun resolve(
        requestId: AcquisitionRequestId,
        attachments: List<OccurrenceAttachmentExpectation> = emptyList(),
    ): AcquisitionResolution {
        val expected = attachments.toList()
        if (expected.any { it.requestId != requestId } || expected.map { it.occurrenceId }.toSet().size != expected.size) {
            invalid("acquisition_attachment_mismatch")
        }
        currentCoroutineContext().ensureActive()
        val previous = repository.request(requestId) ?: invalid("acquisition_request_missing")
        // An accepted receipt can be attached/restored offline. This path is not a new claim.
        if (previous.state == AcquisitionJobState.COMMITTED) return attachAccepted(previous, expected)
        if (previous.state == AcquisitionJobState.OUTCOME_UNKNOWN) return AcquisitionResolution.OutcomeUnknown(previous)
        requireOnline()
        currentCoroutineContext().ensureActive()
        val claimCommand = ClaimAcquisition(
            requestId, AcquisitionAttemptToken(newEntityId("acquisition-attempt")), owner,
            clock.epochMs(), leaseDurationMs,
        )
        var claimed: AcquisitionClaimResult? = null
        try {
            // A cancellation at a short durable-claim return must not strand a provably
            // unsent live claim merely because the coroutine could not receive its value.
            withContext(NonCancellable) { claimed = repository.claim(claimCommand) }
        } catch (failure: Throwable) {
            val acquired = claimed as? AcquisitionClaimResult.Claimed
            if (acquired != null) withContext(NonCancellable) {
                try { repository.releaseUnsentClaim(acquired.lease, clock.epochMs()) } catch (_: Throwable) { /* expiry/restart stays unknown */ }
            }
            if (failure is CancellationException || failure is VeyraException) throw failure
            throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "acquisition_claim_unconfirmed"))
        }
        return when (val claim = checkNotNull(claimed)) {
            is AcquisitionClaimResult.AlreadyCommitted -> attachAccepted(claim.record, expected)
            is AcquisitionClaimResult.Busy -> AcquisitionResolution.InProgress(claim.record)
            is AcquisitionClaimResult.OutcomeUnknown -> AcquisitionResolution.OutcomeUnknown(claim.record)
            is AcquisitionClaimResult.Claimed -> acquireClaimed(claim, expected)
        }
    }

    private suspend fun acquireClaimed(
        claim: AcquisitionClaimResult.Claimed,
        attachments: List<OccurrenceAttachmentExpectation>,
    ): AcquisitionResolution {
        var gatewayEntered = false
        var durableResultReturned = false
        try {
            currentCoroutineContext().ensureActive()
            requireOnline()
            // Everything before this line is provably unsent. From here onward a thrown or
            // cancelled call is conservatively uncertain, even when no response was observed.
            gatewayEntered = true
            val result = gateway.acquire(claim.request)
            val committed = withContext(NonCancellable) {
                repository.commit(CommitAcquisition(
                    lease = claim.lease,
                    result = result,
                    receiptId = result.receiptOrNull()?.let { FirstResultReceiptId(newEntityId("first-receipt")) },
                    bindingAllocation = if (result is SourceAcquisitionResult.Single) ExecutionBindingAllocation(
                        ExecutionBindingId(newEntityId("execution-binding")),
                        SourceRecordingId(newEntityId("source-recording")),
                        ExecutableSubjectId(newEntityId("source-subject")),
                    ) else null,
                    failureStage = result.failureStage(),
                    attachments = attachments,
                    nowEpochMs = clock.epochMs(),
                )).also { durableResultReturned = true }
            }
            currentCoroutineContext().ensureActive()
            return when (committed) {
                is AcquisitionCommitResult.Committed -> AcquisitionResolution.Accepted(
                    committed.record, committed.attachedOccurrences, committed.rejectedAttachments,
                )
                is AcquisitionCommitResult.Stale -> AcquisitionResolution.Stale(committed.record)
            }
        } catch (failure: Throwable) {
            if (!durableResultReturned) {
                val recorded = withContext(NonCancellable) {
                    try {
                        if (gatewayEntered) {
                            repository.markOutcomeUnknown(MarkAcquisitionOutcomeUnknown(claim.lease, clock.epochMs()))
                            true
                        } else repository.releaseUnsentClaim(claim.lease, clock.epochMs())
                    } catch (_: Throwable) { null }
                }
                if (failure is CancellationException) throw failure
                if (recorded == null) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "acquisition_settlement_unconfirmed"))
                // A rejected CAS is not proof that the live claim returned to READY. Leave
                // expired/foreign ownership untouched; only Room's normal recovery may settle it.
                if (!recorded) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "acquisition_unsent_claim_not_released"))
                if (gatewayEntered) throw VeyraException(AppFailure(FailureCode.RECOVERY_REQUIRED, "acquisition_outcome_unknown"))
            }
            if (failure is CancellationException) throw failure
            if (failure is VeyraException) throw failure
            // The original source exception may contain a signed locator or response body.
            throw VeyraException(AppFailure(FailureCode.UNEXPECTED, "acquisition_operation_failed"))
        }
    }

    private suspend fun attachAccepted(
        record: AcquisitionRecord,
        attachments: List<OccurrenceAttachmentExpectation>,
    ): AcquisitionResolution.Accepted {
        if (record.outcome == null) invalid("acquisition_committed_outcome_missing")
        val attached = mutableSetOf<ExecutionOccurrenceId>()
        val rejected = mutableSetOf<ExecutionOccurrenceId>()
        for (expected in attachments) {
            if (repository.attachOccurrence(AttachAcquisitionOccurrence(expected))) attached += expected.occurrenceId
            else rejected += expected.occurrenceId
        }
        return AcquisitionResolution.Accepted(record, attached, rejected)
    }

    /**
     * Read a pinned occurrence, never the latest media-global resolution. Pure gateway restore
     * validates public structure only; the consistent Room state supplies historical authority.
     * Legacy schema2 receipts intentionally use a separate migration/legacy execution path.
     */
    fun boundOccurrence(
        occurrenceId: ExecutionOccurrenceId,
        expectedBindingId: ExecutionBindingId,
        state: AcquisitionRepositoryState = repository.state.value,
    ): BoundAcquisitionOccurrence {
        val occurrence = state.occurrences[occurrenceId] ?: invalid("execution_occurrence_missing")
        if (occurrence.origin != AcquisitionOrigin.TYPED || occurrence.selectionState != OccurrenceSelectionState.BOUND ||
            occurrence.bindingId != expectedBindingId) invalid("execution_occurrence_not_bound")
        val requestId = occurrence.requestId ?: invalid("execution_typed_request_missing")
        val record = state.requests[requestId] ?: invalid("acquisition_request_missing")
        val outcome = record.outcome as? AcquisitionOutcome.Single ?: invalid("execution_single_outcome_missing")
        val binding = state.bindings[expectedBindingId] ?: invalid("execution_binding_missing")
        val receipt = state.receipts[outcome.receiptId] as? FirstResultReceipt.Observed
            ?: invalid("execution_typed_receipt_missing")
        val subject = state.subjects[binding.subjectId] ?: invalid("execution_subject_missing")
        val recordingId = binding.recordingId ?: invalid("execution_recording_missing")
        val recording = state.recordings[recordingId] ?: invalid("execution_recording_missing")
        if (record.state != AcquisitionJobState.COMMITTED || record.request.id != occurrence.requestId ||
            outcome.requestId != record.request.id || outcome.bindingId != expectedBindingId ||
            binding.id != expectedBindingId || binding.origin != ExecutionBindingOrigin.TYPED ||
            binding.firstReceiptId != receipt.id || binding.subjectId != occurrence.subjectId || subject.id != binding.subjectId ||
            recording.id != recordingId ||
            receipt.observation.requestId != record.request.id || receipt.observation.queryTitle != record.request.completeTitle) {
            invalid("execution_binding_lineage_mismatch")
        }
        when (val metadata = record.request.metadata) {
            is MediaCapture -> if (subject !is ExecutableSubject.CatalogMedia || subject.mediaId != metadata.media.id) {
                invalid("execution_catalog_subject_mismatch")
            }
            else -> if (subject !is ExecutableSubject.SourceRecordingSubject || subject.recordingId != recording.id) {
                invalid("execution_neutral_subject_mismatch")
            }
        }
        val selected = gateway.restore(record.request, receipt.observation) as? SourceAcquisitionResult.Single
            ?: invalid("execution_receipt_not_single")
        if (selected.requestId != record.request.id || selected.receipt != receipt.observation ||
            selected.recording.id != recording.canonicalId || selected.recording.publicUrl != recording.canonicalUrl) {
            invalid("execution_recording_lineage_mismatch")
        }
        return BoundAcquisitionOccurrence(occurrence, record.request, receipt, binding, subject, recording, selected)
    }

    /** Same-C inspection only. An inspection report is not a playable/Ready/native capability. */
    suspend fun inspect(occurrenceId: ExecutionOccurrenceId, expectedBindingId: ExecutionBindingId): AcquisitionInspection {
        val bound = boundOccurrence(occurrenceId, expectedBindingId)
        requireOnline()
        currentCoroutineContext().ensureActive()
        return gateway.inspect(bound.request, bound.selection).also {
            if (it.selection != bound.selection) invalid("execution_inspection_selection_mismatch")
        }
    }

    private fun requireOnline() {
        if (offline()) throw VeyraException(AppFailure(FailureCode.OFFLINE_POLICY, "acquisition_offline"))
    }

    private fun invalid(detail: String): Nothing = throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))
}

internal fun SourceAcquisitionResult.receiptOrNull(): FirstResultObservation? = when (this) {
    is SourceAcquisitionResult.Single -> receipt
    is SourceAcquisitionResult.Collection -> receipt
    is SourceAcquisitionResult.Unplayable -> receipt
}

private fun SourceAcquisitionResult.failureStage(): AcquisitionFailureStage = when {
    this is SourceAcquisitionResult.Unplayable && (problem.code == AcquisitionProblemCode.INVALID_REQUEST ||
        receipt == null && problem.code == AcquisitionProblemCode.MEMBER_NOT_PLAYABLE) -> AcquisitionFailureStage.ADMISSION
    receiptOrNull() == null -> AcquisitionFailureStage.SEARCH
    else -> AcquisitionFailureStage.SELECTION
}
