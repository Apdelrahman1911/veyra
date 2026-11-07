package dev.veyra.data.room

import dev.veyra.acquisition.AcquisitionCaptureFactory
import dev.veyra.acquisition.AcquisitionInputRules
import dev.veyra.data.*
import dev.veyra.model.*

internal fun acquisitionInvalid(detail: String): Nothing =
    throw VeyraException(AppFailure(FailureCode.INVALID_STATE, detail))

internal fun AcquisitionGraph.requireRevision(expected: Long?) {
    if (expected != null && expected != revision) acquisitionInvalid("acquisition_stale_library_revision")
}

/** Freeze and compare the WHOLE request before touching any normalized child relation. */
internal fun AcquisitionGraph.captureRequest(request: AcquisitionRequest): AcquisitionGraph {
    AcquisitionInputRules.completeTitle(request.completeTitle)
    AcquisitionInputRules.providerRevision(request.providerRevision)
    val frozen = request.copy(metadata = AcquisitionCaptureFactory().freeze(request.metadata))
    val old = state().requests[request.id]
    if (old != null) {
        if (old.request != frozen) acquisitionInvalid("acquisition_capture_identity_changed")
        return this
    }
    val graph = ensureCapturedCatalog(frozen)
    return graph.copy(rows = graph.rows.withCapturedRequest(frozen).let { rows -> rows.copy(
        acquisitionJobRows = rows.acquisitionJobRows.withImmutableRow(AcquisitionRecord(
            frozen, AcquisitionJobState.READY, updatedEpochMs = frozen.capturedEpochMs,
        ).toEntity()) { it.requestId },
    ) })
}

internal fun AcquisitionGraph.applyCapture(command: CaptureAcquisition): AcquisitionMutation<AcquisitionRecord> {
    requireRevision(command.expectedCatalogRevision)
    if (command.expectedCatalogRevision != null && command.request.catalogRevision != command.expectedCatalogRevision) {
        acquisitionInvalid("acquisition_capture_revision_mismatch")
    }
    return AcquisitionMutation(captureRequest(command.request)) { it.state().requests.getValue(command.request.id) }
}

private fun AcquisitionGraph.withRecord(record: AcquisitionRecord): AcquisitionGraph = copy(rows = rows.copy(
    acquisitionJobRows = rows.acquisitionJobRows.withRow(record.toEntity()) { it.requestId },
))

internal fun AcquisitionGraph.applyClaim(command: ClaimAcquisition): AcquisitionMutation<AcquisitionClaimResult> {
    val record = state().requests[command.requestId] ?: acquisitionInvalid("acquisition_request_missing")
    require(command.nowEpochMs >= record.updatedEpochMs)
    require(command.nowEpochMs <= Long.MAX_VALUE - command.leaseDurationMs)
    val next = when (record.state) {
        AcquisitionJobState.READY -> withRecord(record.copy(state = AcquisitionJobState.CLAIMED,
            lease = AcquisitionLease(record.request.id, command.token, command.owner, command.nowEpochMs,
                command.nowEpochMs + command.leaseDurationMs), updatedEpochMs = command.nowEpochMs))
        AcquisitionJobState.CLAIMED -> if (requireNotNull(record.lease).expiresEpochMs <= command.nowEpochMs) {
            withRecord(record.copy(state = AcquisitionJobState.OUTCOME_UNKNOWN, updatedEpochMs = command.nowEpochMs))
        } else this
        AcquisitionJobState.COMMITTED, AcquisitionJobState.OUTCOME_UNKNOWN -> this
    }
    return AcquisitionMutation(next) { graph ->
        val actual = graph.state().requests.getValue(command.requestId)
        when {
            record.state == AcquisitionJobState.READY -> AcquisitionClaimResult.Claimed(actual.request, requireNotNull(actual.lease))
            actual.state == AcquisitionJobState.COMMITTED -> AcquisitionClaimResult.AlreadyCommitted(actual)
            actual.state == AcquisitionJobState.OUTCOME_UNKNOWN -> AcquisitionClaimResult.OutcomeUnknown(actual)
            else -> AcquisitionClaimResult.Busy(actual)
        }
    }
}

internal fun AcquisitionGraph.applyReleaseUnsent(lease: AcquisitionLease, nowEpochMs: Long): AcquisitionMutation<Boolean> {
    val record = state().requests[lease.requestId] ?: return AcquisitionMutation(this) { false }
    val matches = record.state == AcquisitionJobState.CLAIMED && record.lease == lease && record.outcome == null &&
        nowEpochMs >= record.updatedEpochMs && nowEpochMs < lease.expiresEpochMs &&
        rows.firstResultReceiptRows.none { it.requestId == lease.requestId.value }
    val next = if (matches) withRecord(record.copy(state = AcquisitionJobState.READY, lease = null, updatedEpochMs = nowEpochMs)) else this
    return AcquisitionMutation(next) { matches }
}

internal fun AcquisitionGraph.applyOutcomeUnknown(command: MarkAcquisitionOutcomeUnknown): AcquisitionMutation<AcquisitionRecord> {
    val record = state().requests[command.lease.requestId] ?: acquisitionInvalid("acquisition_request_missing")
    val next = if (record.state == AcquisitionJobState.CLAIMED && record.lease == command.lease) {
        require(command.nowEpochMs >= record.updatedEpochMs)
        withRecord(record.copy(state = AcquisitionJobState.OUTCOME_UNKNOWN, updatedEpochMs = command.nowEpochMs))
    } else this
    return AcquisitionMutation(next) { it.state().requests.getValue(record.request.id) }
}

internal fun AcquisitionGraph.applyCommit(command: CommitAcquisition): AcquisitionMutation<AcquisitionCommitResult> {
    val state = state()
    val record = state.requests[command.lease.requestId] ?: acquisitionInvalid("acquisition_request_missing")
    validateSourceResult(record.request, command.result)
    val raw = when (val result = command.result) {
        is SourceAcquisitionResult.Single -> result.receipt
        is SourceAcquisitionResult.Collection -> result.receipt
        is SourceAcquisitionResult.Unplayable -> result.receipt
    }
    require((raw == null) == (command.receiptId == null))
    require((command.result is SourceAcquisitionResult.Single) == (command.bindingAllocation != null))
    require(command.attachments.map { it.occurrenceId }.toSet().size == command.attachments.size &&
        command.attachments.all { it.requestId == record.request.id })
    require(command.nowEpochMs >= record.request.capturedEpochMs)
    // A foreign/no-longer-sent lease cannot create an accepted execution capability. A known
    // late response of the exact retained lease may be kept as evidence, never silently queried again.
    if (record.lease != command.lease || record.state == AcquisitionJobState.COMMITTED || record.state == AcquisitionJobState.READY) {
        return AcquisitionMutation(this) { AcquisitionCommitResult.Stale(it.state().requests.getValue(record.request.id), false) }
    }
    require(command.nowEpochMs >= record.updatedEpochMs)
    val receipt = raw?.let { FirstResultReceipt.Observed(requireNotNull(command.receiptId), it, command.nowEpochMs) }
    var next = if (receipt == null) this else copy(rows = rows.copy(
        firstResultReceiptRows = rows.firstResultReceiptRows.withImmutableRow(receipt.toEntity()) { it.id },
    ))
    if (record.state != AcquisitionJobState.CLAIMED || command.nowEpochMs >= command.lease.expiresEpochMs) {
        next = next.withRecord(record.copy(state = AcquisitionJobState.OUTCOME_UNKNOWN, updatedEpochMs = command.nowEpochMs))
        return AcquisitionMutation(next) { AcquisitionCommitResult.Stale(it.state().requests.getValue(record.request.id), receipt != null) }
    }
    val outcome = when (val result = command.result) {
        is SourceAcquisitionResult.Single -> {
            val allocation = requireNotNull(command.bindingAllocation)
            val existingRecording = state.recordings.values.singleOrNull {
                it.provider == SourceProvider.YOUTUBE && it.canonicalId == result.recording.id
            }
            val recording = existingRecording ?: SourceRecording(allocation.newRecordingId,
                result.recording.id, result.recording.publicUrl)
            require(recording.canonicalUrl == result.recording.publicUrl)
            val subject: ExecutableSubject = (record.request.metadata as? MediaCapture)?.let {
                ExecutableSubject.CatalogMedia(ExecutableSubjectId(it.media.id), it.media.id)
            } ?: state.subjects.values.filterIsInstance<ExecutableSubject.SourceRecordingSubject>()
                .singleOrNull { it.recordingId == recording.id }
                ?: ExecutableSubject.SourceRecordingSubject(allocation.newNeutralSubjectId, recording.id)
            val binding = ExecutionBinding(allocation.bindingId, subject.id, requireNotNull(receipt).id,
                recording.id, ExecutionBindingOrigin.TYPED)
            next = next.copy(rows = next.rows.copy(
                sourceRecordingRows = next.rows.sourceRecordingRows.withImmutableRow(recording.toEntity()) { it.id },
                executableSubjectRows = next.rows.executableSubjectRows.withImmutableRow(subject.toEntity()) { it.id },
                executionBindingRows = next.rows.executionBindingRows.withImmutableRow(binding.toEntity()) { it.id },
            ))
            AcquisitionOutcome.Single(record.request.id, receipt.id, binding.id)
        }
        is SourceAcquisitionResult.Collection -> {
            // Returned child collections are visible failures, never recursively traversed as a leaf.
            if (record.request.kind == AcquisitionKind.COLLECTION_MEMBER) AcquisitionOutcome.Unplayable(record.request.id,
                requireNotNull(receipt).id, AcquisitionFailureStage.SELECTION, AcquisitionProblem(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE))
            else AcquisitionOutcome.Collection(record.request.id, requireNotNull(receipt).id, result.identity, result.scope)
        }
        is SourceAcquisitionResult.Unplayable -> AcquisitionOutcome.Unplayable(record.request.id,
            receipt?.id, command.failureStage, result.problem)
    }
    next = next.copy(rows = next.rows.copy(acquisitionOutcomeRows =
        next.rows.acquisitionOutcomeRows.withImmutableRow(outcome.toEntity()) { it.requestId })).withRecord(
        record.copy(state = AcquisitionJobState.COMMITTED, outcome = outcome, updatedEpochMs = command.nowEpochMs),
    )
    val attached = mutableSetOf<ExecutionOccurrenceId>()
    val rejected = mutableSetOf<ExecutionOccurrenceId>()
    command.attachments.forEach { expected ->
        val attachment = next.attachAcceptedOccurrence(expected)
        next = attachment.first
        if (attachment.second) attached += expected.occurrenceId else rejected += expected.occurrenceId
    }
    return AcquisitionMutation(next) { actual -> AcquisitionCommitResult.Committed(
        actual.state().requests.getValue(record.request.id), attached.toSet(), rejected.toSet(),
    ) }
}

/** Exact membership CAS, not a media-global selection update. Removed slots cannot receive C. */
internal fun AcquisitionGraph.attachAcceptedOccurrence(expected: OccurrenceAttachmentExpectation): Pair<AcquisitionGraph, Boolean> {
    require((expected.context == null) == (expected.expectedQueueRevision == null))
    require((expected.batchId == null) == (expected.expectedBatchIntentRevision == null))
    require((expected.context == null) != (expected.batchId == null)) { "One occurrence owner expectation required" }
    val state = state()
    val occurrence = state.occurrences[expected.occurrenceId] ?: return this to false
    if (occurrence.requestId != expected.requestId) return this to false
    if (expected.context != null) {
        val queue = state.queues.getValue(expected.context)
        if (queue.editRevision != expected.expectedQueueRevision || occurrence.id !in queue.displayedOrder) return this to false
    }
    val batch = expected.batchId?.let { state.batches[it] ?: return this to false }
    val member = batch?.members?.singleOrNull { it.occurrenceId == occurrence.id }
    if (batch != null && (member == null || member.requestId != expected.requestId ||
            batch.intentRevision != expected.expectedBatchIntentRevision || batch.intent != DownloadBatchIntent.ACTIVE)) return this to false
    val outcome = state.requests.getValue(expected.requestId).outcome ?: return this to false
    val target = when (outcome) {
        is AcquisitionOutcome.Single -> occurrence.copy(subjectId = state.bindings.getValue(outcome.bindingId).subjectId,
            bindingId = outcome.bindingId, selectionState = OccurrenceSelectionState.BOUND)
        is AcquisitionOutcome.Collection -> occurrence.copy(selectionState = OccurrenceSelectionState.NEEDS_COLLECTION_ACTION)
        is AcquisitionOutcome.Unplayable -> occurrence.copy(selectionState = OccurrenceSelectionState.UNPLAYABLE)
    }
    if (occurrence.selectionState != OccurrenceSelectionState.PENDING) return this to (occurrence == target)
    var next = copy(rows = rows.copy(executionOccurrenceRows = rows.executionOccurrenceRows.withRow(target.toEntity()) { it.id }))
    if (batch != null && member != null) {
        val changed = member.copy(requiredBindingId = target.bindingId, state = when (target.selectionState) {
            OccurrenceSelectionState.BOUND -> DownloadBatchMemberState.WAITING_FOR_JOB
            OccurrenceSelectionState.NEEDS_COLLECTION_ACTION -> DownloadBatchMemberState.NEEDS_COLLECTION_ACTION
            else -> DownloadBatchMemberState.UNPLAYABLE
        })
        next = next.withBatch(batch.copy(members = batch.members.map { if (it.position == member.position) changed else it }))
    }
    return next to true
}

internal fun AcquisitionGraph.applyAttachOccurrence(command: AttachAcquisitionOccurrence): AcquisitionMutation<Boolean> {
    val (next, attached) = attachAcceptedOccurrence(command.expected)
    return AcquisitionMutation(next) { attached }
}

internal fun AcquisitionGraph.collectionSource(id: AcquisitionRequestId): CollectionSource {
    val state = state()
    val record = state.requests[id] ?: acquisitionInvalid("acquisition_request_missing")
    val outcome = record.outcome as? AcquisitionOutcome.Collection ?: acquisitionInvalid("acquisition_not_collection")
    val receipt = state.receipts.getValue(outcome.receiptId) as FirstResultReceipt.Observed
    return CollectionSource(record.request, receipt, outcome)
}
