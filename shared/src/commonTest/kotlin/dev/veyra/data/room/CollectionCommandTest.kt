package dev.veyra.data.room

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/** Authored pure collection-row tests; no network traversal, provider snapshot or Room commit. */
class CollectionCommandTest {
    @Test fun pagesPreserveDuplicateVideoOccurrencesAndUnavailableHolesWithoutCatalogRows() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val members = listOf(authoredMember(observation, 0), authoredMember(observation, 1, hole = true), authoredMember(observation, 2))
        val data = page(selection, observation, members = members, holes = 1)
        val result = fixture.project { it.applyAppendPage(append(request, data)) }
        assertEquals(CollectionAppendDisposition.COMMITTED_DELTA, result.disposition)
        assertEquals(CollectionFrontier(1, 3, 1), result.run.frontier)
        val snapshot = fixture.project { it.applySealSnapshot(SealCollectionSnapshot(CollectionSnapshotId("with-holes"), observation.id,
            result.run.frontier, 123_040)) }
        assertEquals(members, snapshot.members)
        assertEquals(snapshot.members[0].entry.id, snapshot.members[2].entry.id)
        assertNotEquals(snapshot.members[0].reference, snapshot.members[2].reference)
        assertFalse(snapshot.members[1].isVideoCandidate)
        assertTrue(snapshot.extent.hasKnownHoles)
        assertTrue(fixture.graph.snapshot().media.isEmpty())
        assertTrue(fixture.graph.snapshot().shows.isEmpty())
    }

    @Test fun exactEarlierPageReplayIsIdempotentAfterFrontierAdvancesButDifferentRawValueFails() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val first = page(selection, observation, members = listOf(authoredMember(observation, 0)), traversal = CollectionTraversal.MORE_AVAILABLE)
        val initial = fixture.project { it.applyAppendPage(append(request, first)) }
        val second = page(selection, observation, pageIndex = 1, start = 1, members = listOf(authoredMember(observation, 1)))
        fixture.project { it.applyAppendPage(append(request, second, initial.run.frontier, 123_040)) }
        val before = fixture.graph
        val replay = fixture.project { it.applyAppendPage(append(request, first, null, 123_050)) }
        assertEquals(CollectionAppendDisposition.IDENTICAL_REPLAY, replay.disposition)
        assertSame(before, fixture.graph)
        val original = first.members.single()
        val changed = original.copy(entry = original.entry.copy(title = "Different B"), display = original.display!!.copy(title = "Different B"))
        assertFailsWith<IllegalArgumentException> {
            fixture.project { it.applyAppendPage(append(request, first.copy(members = listOf(changed)), null, 123_050)) }; Unit
        }
        assertSame(before, fixture.graph)
    }

    @Test fun headerlessFailureSnapshotStaysHeaderlessAfterLaterVerifiedObservation() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val failure = failedPage(selection, observation.id)
        val failed = fixture.project { it.applyAppendPage(append(request, failure, failureId = "failure-1")) }
        assertEquals(CollectionFrontier(0, 0, 0), failed.run.frontier)
        assertNull(failed.run.observation)
        val cut = fixture.project { it.applySealSnapshot(SealCollectionSnapshot(CollectionSnapshotId("before-header"), observation.id,
            failed.run.frontier, 123_040)) }
        val observed = page(selection, observation, members = listOf(authoredMember(observation, 0)))
        fixture.project { it.applyAppendPage(append(request, observed, failed.run.frontier, 123_050)) }
        val restoredCut = fixture.graph.state().snapshots.getValue(cut.id)
        assertEquals(cut, restoredCut)
        assertNull(restoredCut.observation)
        assertTrue(restoredCut.members.isEmpty())
        assertEquals(CollectionTraversal.INCOMPLETE, restoredCut.extent.traversal)
        assertFalse(fixture.graph.rows.collectionSnapshotRows.single().observationCaptured)
        assertEquals(observation, fixture.graph.state().runs.getValue(observation.id).observation)
    }

    @Test fun immutablePrefixDoesNotAcquireLaterMembersOrAnInventedCompleteExtent() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val first = fixture.project { it.applyAppendPage(append(request,
            page(selection, observation, members = listOf(authoredMember(observation, 0)), traversal = CollectionTraversal.MORE_AVAILABLE))) }
        val cut = fixture.project { it.applySealSnapshot(SealCollectionSnapshot(CollectionSnapshotId("prefix"), observation.id, first.run.frontier, 123_040)) }
        fixture.project { it.applyAppendPage(append(request,
            page(selection, observation, pageIndex = 1, start = 1, members = listOf(authoredMember(observation, 1))), first.run.frontier, 123_050)) }
        assertEquals(cut, fixture.graph.state().snapshots.getValue(cut.id))
        assertEquals(1, cut.members.size)
        assertEquals(CollectionTraversal.MORE_AVAILABLE, cut.extent.traversal)
        assertEquals(CollectionTraversal.OBSERVED_TO_END, fixture.graph.state().runs.getValue(observation.id).extent.traversal)
    }

    @Test fun failedAttemptsDoNotBecomeEmptyPagesOrAdvanceAndTheFourthFailureIsRejected() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val data = failedPage(selection, observation.id)
        var frontier: CollectionFrontier? = null
        for (attempt in 1..3) {
            val result = fixture.project { it.applyAppendPage(append(request, data, frontier, 123_029L + attempt, "failure-$attempt")) }
            assertEquals(CollectionAppendDisposition.RECORDED_FAILURE, result.disposition)
            assertEquals(CollectionFrontier(0, 0, 0), result.run.frontier)
            assertEquals(attempt, result.run.retryableFailuresAtFrontier)
            frontier = result.run.frontier
        }
        val before = fixture.graph
        assertEquals(CollectionAppendDisposition.STALE_FRONTIER,
            fixture.project { it.applyAppendPage(append(request, data, frontier, 123_040, "failure-4")) }.disposition)
        assertSame(before, fixture.graph)
        assertTrue(fixture.graph.rows.collectionPageCommitRows.isEmpty())
        assertTrue(fixture.graph.rows.collectionMemberRows.isEmpty())
        assertEquals(3, fixture.graph.rows.collectionPageFailureRows.size)
        assertEquals(CollectionAppendDisposition.IDENTICAL_REPLAY,
            fixture.project { it.applyAppendPage(append(request, data, null, 123_050, "failure-1")) }.disposition)
    }

    @Test fun mismatchedParentReceiptIdentityOrNoncontiguousOrdinalCannotAppend() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val data = page(selection, observation, members = listOf(authoredMember(observation, 0)))
        assertFailsWith<IllegalArgumentException> {
            fixture.project { it.applyAppendPage(append(request, data).copy(parentReceiptId = FirstResultReceiptId("not-the-accepted-parent"))) }; Unit
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.project { it.applyAppendPage(append(request, data.copy(startOrdinal = 1))) }; Unit
        }
        val wrongIdentity = observation.copy(identity = CollectionIdentity(AcquisitionEntryKind.PLAYLIST, "PLother", "https://www.youtube.com/playlist?list=PLother"))
        assertFailsWith<IllegalArgumentException> {
            fixture.project { it.applyAppendPage(append(request, data.copy(observation = wrongIdentity))) }; Unit
        }
        assertTrue(fixture.graph.state().runs.isEmpty())
    }

    @Test fun childCollectionFirstResultStaysVisibleUnplayableWithoutRecursionOrLaterResult() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val result = fixture.project { it.applyAppendPage(append(request,
            page(selection, observation, members = listOf(authoredMember(observation, 0))))) }
        val snapshot = fixture.project { it.applySealSnapshot(SealCollectionSnapshot(CollectionSnapshotId("parent-cut"), observation.id, result.run.frontier, 123_040)) }
        val metadata = CollectionMemberCapture(snapshot.id, snapshot.members.single())
        val child = AcquisitionRequest(AcquisitionRequestId("child-B"), metadata.completeTitle, metadata, 123_100)
        val record = fixture.accept(child, authoredCollection(child))
        val outcome = assertIs<AcquisitionOutcome.Unplayable>(record.outcome)
        assertEquals(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE, outcome.problem.code)
        assertNotNull(outcome.receiptId)
        assertEquals(2, fixture.graph.state().receipts.size)
        assertTrue(fixture.graph.state().bindings.isEmpty())
        assertEquals(setOf(observation.id), fixture.graph.state().runs.keys)
        assertFailsWith<VeyraException> { fixture.graph.collectionSource(child.id) }
    }

    @Test fun availableScopeUsesActualCapturedEligibilityAndCannotSilentlyExcludeAnEligibleOccurrence() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val members = (0..2).map { authoredMember(observation, it, hole = it == 1) }
        val appended = fixture.project { it.applyAppendPage(append(request, page(selection, observation, members = members, holes = 1))) }
        val snapshot = fixture.project { it.applySealSnapshot(SealCollectionSnapshot(CollectionSnapshotId("available-cut"),
            observation.id, appended.run.frontier, 123_040)) }
        val clock = AcquisitionTestClock(123_100)
        val factory = AcquisitionAdmissionFactory(AcquisitionCaptureFactory(clock), clock)
        assertFailsWith<VeyraException> {
            factory.collection(snapshot, ObservedCollectionChoice.Available(false, false), PlaybackControlsMode.MUSIC)
        }
        val selected = factory.collection(snapshot, ObservedCollectionChoice.Selected(setOf(0), false), PlaybackControlsMode.MUSIC)
        val falselyAvailable = requireNotNull(selected.scope).copy(kind = AdmissionScopeKind.AVAILABLE_SUBSET)
        val before = fixture.graph
        assertFailsWith<IllegalArgumentException> { fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(
            QueueContext.MUSIC, 0, null, QueueAdmissionOperation.APPEND, selected.requests, falselyAvailable, selected.occurrences, null)) }; Unit }
        assertSame(before, fixture.graph)

        val prepared = factory.collection(snapshot, ObservedCollectionChoice.Available(true, false), PlaybackControlsMode.MUSIC)
        val scope = requireNotNull(prepared.scope)
        fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(QueueContext.MUSIC, 0, null,
            QueueAdmissionOperation.APPEND, prepared.requests, scope, prepared.occurrences, null)) }
        val actual = fixture.graph.state()
        assertEquals(scope, actual.scopes.getValue(scope.id))
        assertEquals(listOf(AdmissionDisposition.INCLUDED, AdmissionDisposition.EXCLUDED, AdmissionDisposition.INCLUDED),
            actual.scopes.getValue(scope.id).members.map { it.disposition })
        assertEquals(listOf(0, 2), prepared.occurrences.map { it.snapshotMember?.ordinal })
        assertNull(actual.scopes.getValue(scope.id).members[1].requestId)
        assertEquals(members, actual.snapshots.getValue(snapshot.id).members, "The hole and duplicate source occurrences remain immutable")
        assertTrue(actual.bindings.isEmpty(), "Metadata admission is not first-result resolution")
        assertTrue(fixture.graph.snapshot().media.isEmpty())
    }

    @Test fun malformedVideoCandidatesAlreadyFailAtCanonicalPageAppendBeforeAnyAvailableScope() {
        val (fixture, request, selection) = parent()
        val observation = authoredObservation(request)
        val valid = authoredMember(observation, 0)
        val invalidMembers = listOf(
            valid.copy(entry = valid.entry.copy(title = " "), display = valid.display!!.copy(title = " ")),
            valid.copy(display = valid.display!!.copy(title = "Different display title")),
            valid.copy(entry = valid.entry.copy(title = "invalid\u0000title"), display = valid.display!!.copy(title = "invalid\u0000title")),
        )
        val before = fixture.graph
        invalidMembers.forEach { member ->
            assertTrue(member.isVideoCandidate, "Raw candidate is deliberately weaker than complete capture eligibility")
            assertFails { fixture.project { it.applyAppendPage(append(request, page(selection, observation, members = listOf(member)))) }; Unit }
            assertSame(before, fixture.graph)
        }
        assertTrue(fixture.graph.state().runs.isEmpty())
    }

    private fun parent(): Triple<AcquisitionCommandFixture, AcquisitionRequest, SourceAcquisitionResult.Collection> {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest("parent-A", AlbumCapture(Album("album-A", "  Complete requested release — الإصدار  ")))
        val selection = authoredCollection(request)
        fixture.accept(request, selection)
        return Triple(fixture, request, selection)
    }

    private fun page(selection: SourceAcquisitionResult.Collection, observation: CollectionObservation, pageIndex: Int = 0,
        start: Int = 0, members: List<CollectionMember>, holes: Int = 0, traversal: CollectionTraversal = CollectionTraversal.OBSERVED_TO_END) =
        CollectionPageData(selection, observation.id, observation, CollectionPageDisposition.OBSERVED_DELTA, pageIndex, start,
            members, CollectionExtent(traversal, start + members.size, holes))

    private fun failedPage(selection: SourceAcquisitionResult.Collection, observationId: CollectionObservationId) =
        CollectionPageData(selection, observationId, null, CollectionPageDisposition.FAILED_ATTEMPT, 0, 0, emptyList(),
            CollectionExtent(CollectionTraversal.INCOMPLETE, 0, 0,
                CollectionProblem(CollectionProblemCode.SOURCE_FAILURE, SourceProblem(SourceProblemCode.NETWORK))))

    private fun append(request: AcquisitionRequest, data: CollectionPageData, frontier: CollectionFrontier? = null,
        now: Long = 123_030, failureId: String? = null) =
        AppendCollectionPage(FirstResultReceiptId("receipt-${request.id.value}"), data, frontier, failureId, now)
}
