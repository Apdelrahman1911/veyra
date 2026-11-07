package dev.veyra.data.room

import dev.veyra.acquisition.authoredMedia
import dev.veyra.acquisition.authoredRequest
import dev.veyra.acquisition.authoredSingle
import dev.veyra.data.*
import dev.veyra.model.*

/** Authored pure-row fixtures. No SQLite transaction, filesystem, source or native execution. */
internal fun commandGraph(snapshot: AppSnapshot = AppSnapshot()): AcquisitionGraph {
    val library = snapshot.toRows()
    return AcquisitionGraph(library, seedAcquisitionRows(library))
}

/**
 * Runs pure command/projection/transition rules with an explicit test-supplied revision.
 * This is NOT a persistence backend or evidence that a Room commit/readback succeeded.
 */
internal class AcquisitionCommandFixture(initial: AcquisitionGraph = commandGraph()) {
    var graph: AcquisitionGraph = initial
        private set

    fun <T> project(transform: (AcquisitionGraph) -> AcquisitionMutation<T>): T {
        val before = graph
        val mutation = transform(before)
        require(mutation.graph.revision == before.revision)
        val next = if (before.sameRelations(mutation.graph)) before else mutation.graph.withRevision(before.revision + 1)
        next.snapshot()
        next.state()
        next.releaseCatalogState()
        if (next !== before) validateAcquisitionTransition(before, next)
        val result = mutation.result(next)
        graph = next
        return result
    }

    fun claim(request: AcquisitionRequest, now: Long = request.capturedEpochMs + 10): AcquisitionLease {
        project { it.applyCapture(CaptureAcquisition(request)) }
        val claim = project { it.applyClaim(ClaimAcquisition(request.id,
            AcquisitionAttemptToken("lease-${request.id.value}"), "authored-owner", now, 1_000)) }
        return (claim as AcquisitionClaimResult.Claimed).lease
    }

    fun accept(
        request: AcquisitionRequest,
        result: SourceAcquisitionResult = authoredSingle(request),
        attachments: List<OccurrenceAttachmentExpectation> = emptyList(),
    ): AcquisitionRecord {
        val lease = claim(request)
        val committed = project { it.applyCommit(commandResult(request, lease, result, attachments = attachments)) }
        return (committed as AcquisitionCommitResult.Committed).record
    }

    fun catalogBatch(request: AcquisitionRequest = authoredRequest(metadata = MediaCapture(authoredMedia())), id: String = "authored-batch"): DownloadBatch {
        require(request.kind == AcquisitionKind.MEDIA)
        val scope = AdmissionScope(AdmissionScopeId("scope-$id"), AdmissionScopeKind.CATALOG_LEAVES, null, null,
            PlaybackControlsMode.MUSIC, listOf(AdmissionScopeMember(0, null, request.id, AdmissionDisposition.INCLUDED, null)), false, 123_000)
        val occurrence = ExecutionOccurrence(ExecutionOccurrenceId("occurrence-$id"), AcquisitionOrigin.TYPED, request.id,
            null, AdmissionScopeMemberRef(scope.id, 0), null, null, scope.controlsMode, OccurrenceSelectionState.PENDING, 123_000)
        val batch = DownloadBatch(DownloadBatchId(id), scope.id, DownloadBatchIntent.ACTIVE, 0,
            listOf(DownloadBatchMember(0, 0, occurrence.id, request.id, null, null, DownloadBatchMemberState.PENDING_SELECTION)), 123_000, 123_000)
        return project { it.applyAdmitBatch(BatchAcquisitionAdmission(null, listOf(request), scope, listOf(occurrence), batch)) }
    }
}

internal fun commandResult(
    request: AcquisitionRequest,
    lease: AcquisitionLease,
    result: SourceAcquisitionResult = authoredSingle(request),
    now: Long = lease.claimedEpochMs + 10,
    attachments: List<OccurrenceAttachmentExpectation> = emptyList(),
): CommitAcquisition {
    val hasReceipt = when (result) {
        is SourceAcquisitionResult.Single, is SourceAcquisitionResult.Collection -> true
        is SourceAcquisitionResult.Unplayable -> result.receipt != null
    }
    return CommitAcquisition(lease, result, if (hasReceipt) FirstResultReceiptId("receipt-${request.id.value}") else null,
        if (result is SourceAcquisitionResult.Single) ExecutionBindingAllocation(ExecutionBindingId("binding-${request.id.value}"),
            SourceRecordingId("recording-${request.id.value}"), ExecutableSubjectId("neutral-${request.id.value}")) else null,
        AcquisitionFailureStage.SELECTION, attachments, now)
}

internal fun pendingOccurrence(request: AcquisitionRequest, id: String = "queue-${request.id.value}", mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC) =
    ExecutionOccurrence(ExecutionOccurrenceId(id), AcquisitionOrigin.TYPED, request.id, null, null, null, null,
        mode, OccurrenceSelectionState.PENDING, request.capturedEpochMs)

internal fun batchExpectation(batch: DownloadBatch): BatchDownloadMemberExpectation {
    val member = batch.members.single()
    return BatchDownloadMemberExpectation(batch.id, member.position, batch.intentRevision, member.occurrenceId,
        member.requestId, requireNotNull(member.requiredBindingId), member.claimId)
}

internal fun <T> PhysicalDownloadCommit<T>.projectedValue(): T = (this as PhysicalDownloadCommit.Committed<T>).value
