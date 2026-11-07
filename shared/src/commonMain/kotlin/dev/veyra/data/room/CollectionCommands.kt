package dev.veyra.data.room

import dev.veyra.acquisition.AcquisitionInputRules
import dev.veyra.data.*
import dev.veyra.model.*

internal fun AcquisitionGraph.applyAppendPage(command: AppendCollectionPage): AcquisitionMutation<CollectionAppendResult> {
    val data = command.data.copy(members = command.data.members.map { it.copy(reference = it.reference.copy(), entry = it.entry.copy(), display = it.display?.copy()) },
        observation = command.data.observation?.let { it.copy(identityEvidence = it.identityEvidence.toSet()) })
    val source = collectionSource(data.selection.requestId)
    require(command.parentReceiptId == source.receipt.id && data.selection.receipt == source.receipt.observation &&
        data.selection.identity == source.outcome.identity && data.selection.scope == source.outcome.scope)
    require(command.receivedEpochMs >= source.receipt.observedEpochMs && data.members.size <= 500 &&
        data.pageIndex in 0 until 100 && data.extent.observedMemberCount <= 5_000)
    val prior = state().runs[data.observationId]
    val frontier = prior?.frontier ?: CollectionFrontier(0, 0, 0)
    if (prior != null) require(prior.parentRequestId == source.request.id && prior.parentReceiptId == source.receipt.id &&
        prior.identity == data.selection.identity && prior.scope == data.selection.scope)
    data.observation?.let {
        require(it.id == data.observationId && it.parentRequestId == source.request.id && it.identity == source.outcome.identity &&
            it.scope == source.outcome.scope && it.identityEvidence.isNotEmpty())
        AcquisitionInputRules.completeTitle(it.title)
        if (prior?.observation != null) require(prior.observation == it) { "Collection observation changed" }
    }
    require(data.members.withIndex().all { (offset, member) ->
        member.reference.observationId == data.observationId && member.reference.parentRequestId == source.request.id &&
            member.reference.identity == source.outcome.identity && member.reference.scope == source.outcome.scope &&
            member.reference.ordinal == data.startOrdinal + offset && member.entry.ordinal == member.reference.ordinal
    })
    data.members.forEach { member ->
        member.display?.let { display ->
            require(display.id == member.entry.id && display.title == member.entry.title && display.publicUrl == member.entry.publicUrl &&
                display.kind == member.entry.kind)
        }
        if (member.isVideoCandidate) {
            AcquisitionInputRules.completeTitle(requireNotNull(member.entry.title))
            requireVideoIdentity(requireNotNull(member.entry.id), requireNotNull(member.entry.publicUrl))
        }
    }
    val problem = data.extent.problem
    require(problem?.sourceProblem?.httpStatus == null || problem.sourceProblem.httpStatus in 100..599)
    fun result(graph: AcquisitionGraph, disposition: CollectionAppendDisposition): AcquisitionMutation<CollectionAppendResult> =
        AcquisitionMutation(graph) { CollectionAppendResult(disposition, it.state().runs.getValue(data.observationId)) }
    if (data.disposition == CollectionPageDisposition.OBSERVED_DELTA) {
        require(command.failureAttemptId == null && data.observation != null)
        val page = CollectionPageCommitEntity(data.observationId.value, data.pageIndex, data.startOrdinal, data.members.size,
            data.extent.traversal, data.extent.observedMemberCount, data.extent.nonPlayableMemberCount,
            problem?.code, problem?.sourceProblem?.code, problem?.sourceProblem?.httpStatus, command.receivedEpochMs)
        val memberRows = data.members.map { CollectionMemberEntity(data.observationId.value, it.reference.ordinal, data.pageIndex, it.entry, it.display) }
        val existing = rows.collectionPageCommitRows.singleOrNull { it.runId == page.runId && it.pageIndex == page.pageIndex }
        if (existing != null) {
            require(page.copy(receivedEpochMs = existing.receivedEpochMs) == existing &&
                sameRowRelation(memberRows, rows.collectionMemberRows.filter { it.runId == page.runId && it.pageIndex == page.pageIndex })) {
                "Differing collection page replay"
            }
            require(prior?.observation == data.observation)
            return result(this, CollectionAppendDisposition.IDENTICAL_REPLAY)
        }
        if (prior != null && (command.expectedFrontier != frontier || prior.stoppedReason != null ||
                prior.extent.traversal == CollectionTraversal.OBSERVED_TO_END)) return result(this, CollectionAppendDisposition.STALE_FRONTIER)
        require(prior != null || command.expectedFrontier == null)
        require(data.pageIndex == frontier.nextPageIndex && data.startOrdinal == frontier.nextOrdinal)
        require(data.extent.observedMemberCount == frontier.nextOrdinal + data.members.size &&
            data.extent.nonPlayableMemberCount == frontier.knownHoleCount + data.members.count { !it.isVideoCandidate })
        require(data.extent.traversal != CollectionTraversal.OBSERVED_TO_END || problem == null)
        val run = CollectionRun(data.observationId, source.request.id, source.receipt.id, source.outcome.identity,
            source.outcome.scope, data.observation, CollectionFrontier(data.pageIndex + 1, data.extent.observedMemberCount,
                data.extent.nonPlayableMemberCount), data.extent, 0, null, prior?.startedEpochMs ?: command.receivedEpochMs, command.receivedEpochMs)
        require(prior == null || run.updatedEpochMs >= prior.updatedEpochMs)
        var nextRows = rows.withObservation(data.observation).copy(
            collectionRunRows = rows.collectionRunRows.withRow(run.toEntity()) { it.id },
            collectionPageCommitRows = rows.collectionPageCommitRows.withImmutableRow(page) { it.runId to it.pageIndex },
            collectionMemberRows = rows.collectionMemberRows + memberRows,
        )
        return result(copy(rows = nextRows), CollectionAppendDisposition.COMMITTED_DELTA)
    }
    require(data.members.isEmpty() && data.extent.traversal == CollectionTraversal.INCOMPLETE && problem != null)
    val attemptId = requireNotNull(command.failureAttemptId)
    require(attemptId.isNotBlank() && attemptId.length <= 256)
    val failure = CollectionPageFailureEntity(attemptId, data.observationId.value, data.pageIndex, data.startOrdinal,
        data.extent.traversal, data.extent.observedMemberCount, data.extent.nonPlayableMemberCount,
        problem.code, problem.sourceProblem?.code, problem.sourceProblem?.httpStatus, command.receivedEpochMs)
    val existing = rows.collectionPageFailureRows.singleOrNull { it.id == attemptId }
    if (existing != null) {
        require(failure.copy(receivedEpochMs = existing.receivedEpochMs) == existing)
        return result(this, CollectionAppendDisposition.IDENTICAL_REPLAY)
    }
    if (prior != null && (command.expectedFrontier != frontier || prior.stoppedReason != null ||
            prior.extent.traversal == CollectionTraversal.OBSERVED_TO_END || prior.retryableFailuresAtFrontier >= 3)) {
        return result(this, CollectionAppendDisposition.STALE_FRONTIER)
    }
    require(prior != null || command.expectedFrontier == null)
    require(data.pageIndex == frontier.nextPageIndex && data.startOrdinal == frontier.nextOrdinal &&
        data.extent.observedMemberCount == frontier.nextOrdinal && data.extent.nonPlayableMemberCount == frontier.knownHoleCount)
    val run = CollectionRun(data.observationId, source.request.id, source.receipt.id, source.outcome.identity, source.outcome.scope,
        prior?.observation ?: data.observation, frontier, data.extent, (prior?.retryableFailuresAtFrontier ?: 0) + 1,
        null, prior?.startedEpochMs ?: command.receivedEpochMs, command.receivedEpochMs)
    require(prior == null || run.updatedEpochMs >= prior.updatedEpochMs)
    val nextRows = (data.observation?.let(rows::withObservation) ?: rows).copy(
        collectionRunRows = rows.collectionRunRows.withRow(run.toEntity()) { it.id },
        collectionPageFailureRows = rows.collectionPageFailureRows.withImmutableRow(failure) { it.id },
    )
    return result(copy(rows = nextRows), CollectionAppendDisposition.RECORDED_FAILURE)
}

private fun AcquisitionRows.withObservation(observation: CollectionObservation): AcquisitionRows = copy(
    collectionObservationRows = collectionObservationRows.withImmutableRow(observation.toEntity()) { it.runId },
    collectionIdentityEvidenceRows = observation.identityEvidence.fold(collectionIdentityEvidenceRows) { values, evidence ->
        values.withImmutableRow(CollectionIdentityEvidenceEntity(observation.id.value, evidence)) { it.runId to it.evidence }
    },
)

internal fun AcquisitionGraph.applySealSnapshot(command: SealCollectionSnapshot): AcquisitionMutation<CollectionSnapshot> {
    val state = state()
    val run = state.runs[command.observationId] ?: acquisitionInvalid("collection_observation_missing")
    if (run.frontier != command.expectedFrontier) acquisitionInvalid("collection_frontier_changed")
    require(command.sealedEpochMs >= run.updatedEpochMs)
    var extent = run.extent
    if (command.stopRun != null && extent.traversal != CollectionTraversal.OBSERVED_TO_END) {
        extent = extent.copy(traversal = CollectionTraversal.INCOMPLETE, problem = extent.problem ?: CollectionProblem(
            if (command.stopRun == CollectionRunStopReason.CURSOR_EXPIRED) CollectionProblemCode.EXPIRED_CONTINUATION
            else CollectionProblemCode.CONTINUATION_UNAVAILABLE,
        ))
    }
    val runRow = rows.collectionRunRows.single { it.id == run.id.value }
    val members = rows.collectionMemberRows.filter { it.runId == run.id.value }.sortedBy { it.ordinal }.map { it.toMember(runRow) }
    val snapshot = CollectionSnapshot(command.id, run.id, run.parentRequestId, run.parentReceiptId, run.observation,
        (run.frontier.nextPageIndex - 1).takeIf { it >= 0 }, members, extent, command.sealedEpochMs)
    val existing = state.snapshots[command.id]
    if (existing != null) {
        require(existing == snapshot) { "Immutable collection snapshot changed" }
        return AcquisitionMutation(this) { it.state().snapshots.getValue(command.id) }
    }
    val nextRun = if (command.stopRun == null || run.extent.traversal == CollectionTraversal.OBSERVED_TO_END) run
        else run.copy(extent = extent, stoppedReason = run.stoppedReason ?: command.stopRun, updatedEpochMs = command.sealedEpochMs)
    val next = copy(rows = rows.copy(
        collectionRunRows = rows.collectionRunRows.withRow(nextRun.toEntity()) { it.id },
        collectionSnapshotRows = rows.collectionSnapshotRows.withImmutableRow(snapshot.toEntity()) { it.id },
        collectionSnapshotMemberRows = rows.collectionSnapshotMemberRows + members.map { CollectionSnapshotMemberEntity(
            snapshot.id.value, it.reference.ordinal, run.id.value, it.reference.ordinal,
        ) },
    ))
    return AcquisitionMutation(next) { it.state().snapshots.getValue(command.id) }
}
