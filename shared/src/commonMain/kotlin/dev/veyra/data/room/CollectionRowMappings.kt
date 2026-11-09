package dev.veyra.data.room

import dev.veyra.data.CollectionFrontier
import dev.veyra.data.CollectionRun
import dev.veyra.data.CollectionRunStopReason
import dev.veyra.model.*

internal fun collectionProblem(code: CollectionProblemCode?, source: SourceProblemCode?, http: Int?): CollectionProblem? {
    require(source != null || http == null)
    require(code != null || source == null)
    return code?.let { CollectionProblem(it, source?.let { SourceProblem(it, http) }) }
}

internal fun AcquisitionRows.collectionRuns(): Map<CollectionObservationId, CollectionRun> {
    val runs = collectionRunRows.rowIndex { it.id }
    val observations = collectionObservationRows.rowIndex { it.runId }
    collectionIdentityEvidenceRows.rowIndex { it.runId to it.evidence }
    val pages = collectionPageCommitRows.rowIndex { it.runId to it.pageIndex }
    collectionPageFailureRows.rowIndex { it.id }
    val members = collectionMemberRows.rowIndex { it.runId to it.ordinal }
    require(observations.keys.all { it in runs })
    require(collectionIdentityEvidenceRows.all { it.runId in observations })
    require(collectionPageCommitRows.all { it.runId in runs })
    require(collectionPageFailureRows.all { it.runId in runs })
    require(collectionMemberRows.all { it.runId in observations && it.runId to it.pageIndex in pages })
    requireRowPositions(collectionPageCommitRows, { it.runId }, { it.pageIndex })
    requireRowPositions(collectionMemberRows, { it.runId }, { it.ordinal })
    val evidence = collectionIdentityEvidenceRows.groupBy { it.runId }
    val byRun = collectionMemberRows.groupBy { it.runId }
    val pagesByRun = collectionPageCommitRows.groupBy { it.runId }
    return runs.values.associate { row ->
        val id = CollectionObservationId(row.id)
        val identity = CollectionIdentity(row.collectionKind, row.collectionId, row.collectionCanonicalUrl)
        val observed = observations[row.id]?.let {
            require(it.parentRequestId == row.parentRequestId && it.collectionKind == row.collectionKind &&
                it.collectionId == row.collectionId && it.collectionCanonicalUrl == row.collectionCanonicalUrl && it.scope == row.scope)
            CollectionObservation(id, AcquisitionRequestId(it.parentRequestId), identity, it.scope, it.title,
                it.titleOrigin, evidence[row.id].orEmpty().map { it.evidence }.toSet(), it.sourceUrl, it.consistency)
        }
        val related = byRun[row.id].orEmpty().sortedBy { it.ordinal }
        val committed = pagesByRun[row.id].orEmpty().sortedBy { it.pageIndex }
        require(row.nextPageIndex == committed.size && row.nextOrdinal == related.size)
        var count = 0
        var holes = 0
        committed.forEach { page ->
            require(page.startOrdinal == count && page.memberCount >= 0)
            val own = related.filter { it.pageIndex == page.pageIndex }
            require(own.size == page.memberCount && own.map { it.ordinal } == (count until count + page.memberCount).toList())
            own.forEach { require(it.entry.ordinal == it.ordinal) }
            count += own.size
            holes += own.count { !it.toMember(row).isVideoCandidate }
            require(page.observedMemberCount == count && page.nonPlayableMemberCount == holes)
            require(page.pageIndex == committed.lastIndex || page.traversal == CollectionTraversal.MORE_AVAILABLE)
            collectionProblem(page.problemCode, page.sourceProblemCode, page.sourceHttpStatus)
        }
        require(row.knownHoleCount == holes && row.observedMemberCount == count && row.nonPlayableMemberCount == holes)
        require(row.retryableFailuresAtFrontier in 0..3 && row.startedEpochMs >= 0 && row.updatedEpochMs >= row.startedEpochMs)
        collectionPageFailureRows.filter { it.runId == row.id }.forEach { failure ->
            require(failure.pageIndex in 0..row.nextPageIndex && failure.startOrdinal in 0..row.nextOrdinal)
            require(failure.observedMemberCount == failure.startOrdinal && failure.nonPlayableMemberCount in 0..failure.startOrdinal)
            require(failure.problemCode != null && failure.traversal == CollectionTraversal.INCOMPLETE)
            collectionProblem(failure.problemCode, failure.sourceProblemCode, failure.sourceHttpStatus)
        }
        id to CollectionRun(id, AcquisitionRequestId(row.parentRequestId), FirstResultReceiptId(row.parentReceiptId),
            identity, row.scope, observed, CollectionFrontier(row.nextPageIndex, row.nextOrdinal, row.knownHoleCount),
            CollectionExtent(row.traversal, row.observedMemberCount, row.nonPlayableMemberCount,
                collectionProblem(row.problemCode, row.sourceProblemCode, row.sourceHttpStatus)),
            row.retryableFailuresAtFrontier, row.stoppedReason?.let(CollectionRunStopReason::valueOf),
            row.startedEpochMs, row.updatedEpochMs)
    }
}

internal fun CollectionMemberEntity.toMember(run: CollectionRunEntity): CollectionMember = CollectionMember(
    CollectionMemberReference(CollectionObservationId(run.id), AcquisitionRequestId(run.parentRequestId),
        CollectionIdentity(run.collectionKind, run.collectionId, run.collectionCanonicalUrl), run.scope, ordinal),
    entry.copy(), display?.copy(),
)

internal fun AcquisitionRows.collectionSnapshots(runs: Map<CollectionObservationId, CollectionRun>): Map<CollectionSnapshotId, CollectionSnapshot> {
    val snapshots = collectionSnapshotRows.rowIndex { it.id }
    collectionSnapshotMemberRows.rowIndex { it.snapshotId to it.ordinal }
    require(collectionSnapshotMemberRows.all { it.snapshotId in snapshots })
    requireRowPositions(collectionSnapshotMemberRows, { it.snapshotId }, { it.ordinal })
    val members = collectionMemberRows.rowIndex { it.runId to it.ordinal }
    val runRows = collectionRunRows.rowIndex { it.id }
    val pages = collectionPageCommitRows.rowIndex { it.runId to it.pageIndex }
    val refs = collectionSnapshotMemberRows.groupBy { it.snapshotId }
    return snapshots.values.associate { row ->
        val run = runs.getValue(CollectionObservationId(row.runId))
        val selected = refs[row.id].orEmpty().sortedBy { it.ordinal }.map { ref ->
            require(ref.runId == row.runId && ref.ordinal == ref.sourceOrdinal)
            members.getValue(ref.runId to ref.sourceOrdinal).toMember(runRows.getValue(row.runId))
        }
        val last = row.lastCommittedPageIndex?.let { pages.getValue(row.runId to it) }
        require(selected.size == (last?.observedMemberCount ?: 0))
        require(row.observedMemberCount == selected.size && row.nonPlayableMemberCount == selected.count { !it.isVideoCandidate })
        require(row.observationCaptured || selected.isEmpty())
        val observation = if (row.observationCaptured) requireNotNull(run.observation) else null
        val id = CollectionSnapshotId(row.id)
        id to CollectionSnapshot(id, run.id, run.parentRequestId, run.parentReceiptId, observation,
            row.lastCommittedPageIndex, selected, CollectionExtent(row.traversal, row.observedMemberCount,
                row.nonPlayableMemberCount, collectionProblem(row.problemCode, row.sourceProblemCode, row.sourceHttpStatus)),
            row.sealedEpochMs)
    }
}

internal fun CollectionRun.toEntity() = CollectionRunEntity(id.value, parentRequestId.value, parentReceiptId.value,
    identity.kind, identity.id, identity.canonicalUrl, scope, frontier.nextPageIndex, frontier.nextOrdinal,
    frontier.knownHoleCount, extent.traversal, extent.observedMemberCount, extent.nonPlayableMemberCount,
    extent.problem?.code, extent.problem?.sourceProblem?.code, extent.problem?.sourceProblem?.httpStatus,
    retryableFailuresAtFrontier, stoppedReason?.name, startedEpochMs, updatedEpochMs)

internal fun CollectionObservation.toEntity() = CollectionObservationEntity(id.value, parentRequestId.value,
    identity.kind, identity.id, identity.canonicalUrl, scope, title, titleOrigin, sourceUrl, consistency)

internal fun CollectionSnapshot.toEntity() = CollectionSnapshotEntity(
    id = id.value, runId = observationId.value, observationCaptured = observation != null,
    lastCommittedPageIndex = lastCommittedPageIndex, traversal = extent.traversal,
    observedMemberCount = extent.observedMemberCount, nonPlayableMemberCount = extent.nonPlayableMemberCount,
    problemCode = extent.problem?.code, sourceProblemCode = extent.problem?.sourceProblem?.code,
    sourceHttpStatus = extent.problem?.sourceProblem?.httpStatus, sealedEpochMs = sealedEpochMs,
)
