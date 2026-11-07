package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.source.execution.SourceCollectionPage
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Fake-port orchestration, not Room persistence, a terminated process or live pagination proof. */
class CollectionTraversalCoordinatorTest {
    private class Rig(maxOpen: Int = 16) {
        val repository = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val clock = AcquisitionTestClock()
        var offline = false
        val acquisition = AcquisitionCoordinator(repository, gateway, clock = clock, offline = { offline })
        val traversal = CollectionTraversalCoordinator(repository, gateway, clock, { offline }, maxOpen)
        val request = authoredRequest("parent", AlbumCapture(Album("edition", "Complete parent title")))
        val selection = authoredCollection(request)
        val observation = authoredObservation(request)
        val cursor = AuthoredContinuation()

        suspend fun initialize() {
            repository.capture(CaptureAcquisition(request))
            gateway.acquireResult = { authoredCollection(it) }
            acquisition.resolve(request.id)
            gateway.collectionResult = { _, _ -> first() }
        }

        fun first(holes: Set<Int> = emptySet(), continuation: AuthoredContinuation? = cursor,
            traversal: CollectionTraversal = CollectionTraversal.MORE_AVAILABLE): SourceCollectionPage {
            val members = (0..2).map { authoredMember(observation, it, hole = it in holes) }
            return SourceCollectionPage(CollectionPageData(selection, observation.id, observation,
                CollectionPageDisposition.OBSERVED_DELTA, 0, 0, members, CollectionExtent(traversal, 3, holes.size)), continuation)
        }

        fun next(holesBefore: Int = 0, continuation: AuthoredContinuation? = null,
            traversal: CollectionTraversal = CollectionTraversal.OBSERVED_TO_END) = SourceCollectionPage(
            CollectionPageData(selection, observation.id, observation, CollectionPageDisposition.OBSERVED_DELTA,
                1, 3, listOf(authoredMember(observation, 3)), CollectionExtent(traversal, 4, holesBefore)), continuation)

        fun retryFailure(holesBefore: Int = 0) = SourceCollectionPage(CollectionPageData(selection, observation.id, observation,
            CollectionPageDisposition.FAILED_ATTEMPT, 1, 3, emptyList(), CollectionExtent(CollectionTraversal.INCOMPLETE, 3, holesBefore,
                CollectionProblem(CollectionProblemCode.SOURCE_FAILURE, SourceProblem(SourceProblemCode.HTTP_ERROR, 503)))), cursor)
    }

    @Test fun pagesAppendDeltasWithDuplicatePositionsAndEarlierHolesWhileOldSnapshotStaysImmutable() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.collectionResult = { _, _ -> rig.first(holes = setOf(1)) }
        rig.gateway.moreResult = { rig.next(holesBefore = 1) }
        val first = rig.traversal.open(rig.request.id)
        val next = rig.traversal.more(assertNotNull(first.more))
        assertNotEquals(first.snapshot.id, next.snapshot.id)
        assertEquals(listOf(0, 1, 2), first.snapshot.members.map { it.reference.ordinal })
        assertEquals(listOf(0, 1, 2, 3), next.snapshot.members.map { it.reference.ordinal })
        assertEquals(1, next.snapshot.extent.nonPlayableMemberCount)
        assertNull(next.snapshot.members[1].display)
        assertEquals(listOf("ABCDEFGHIJK", "ABCDEFGHIJK", "ABCDEFGHIJK", "ABCDEFGHIJK"), next.snapshot.members.map { it.entry.id })
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertEquals(CollectionTraversal.OBSERVED_TO_END, next.snapshot.extent.traversal)
        assertNull(next.more)
        assertEquals(listOf(3, 1), rig.repository.appended.map { it.data.members.size })
        assertEquals(1, rig.gateway.acquisitions.size, "No parent title is re-searched during traversal")
    }

    @Test fun anEmptyObservedTerminalPageIsCommittedButAFailedInitialAttemptIsNotAnEmptyPageCommit() = runTest {
        for (failed in listOf(false, true)) {
            val rig = Rig(); rig.initialize()
            rig.gateway.collectionResult = { _, _ -> SourceCollectionPage(CollectionPageData(rig.selection, rig.observation.id,
                if (failed) null else rig.observation,
                if (failed) CollectionPageDisposition.FAILED_ATTEMPT else CollectionPageDisposition.OBSERVED_DELTA,
                0, 0, emptyList(), CollectionExtent(if (failed) CollectionTraversal.INCOMPLETE else CollectionTraversal.OBSERVED_TO_END,
                    0, 0, if (failed) CollectionProblem(CollectionProblemCode.IDENTITY_UNVERIFIED) else null)), null) }
            val result = rig.traversal.open(rig.request.id)
            assertTrue(result.snapshot.members.isEmpty())
            assertNull(result.more)
            if (failed) {
                assertNull(result.snapshot.observation)
                assertNull(result.snapshot.lastCommittedPageIndex)
                assertTrue(rig.repository.pages.isEmpty())
                assertEquals(CollectionFrontier(0, 0, 0), result.run.frontier)
            } else {
                assertNotNull(result.snapshot.observation)
                assertEquals(0, result.snapshot.lastCommittedPageIndex)
                assertEquals(CollectionTraversal.OBSERVED_TO_END, result.snapshot.extent.traversal)
            }
        }
    }

    @Test fun nullCursorNeverProvesTheEndOfAListing() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.collectionResult = { _, _ -> rig.first(continuation = null) }
        val result = rig.traversal.open(rig.request.id)
        assertNull(result.more)
        assertEquals(CollectionRunStopReason.CURSOR_LOST, result.run.stoppedReason)
        assertEquals(CollectionTraversal.INCOMPLETE, result.snapshot.extent.traversal)
        assertEquals(3, result.snapshot.members.size)
    }

    @Test fun sameFrontierRetryHasANewExplicitActionAndThreeFailuresExhaustOnlyThatTraversal() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.collectionResult = { _, _ -> rig.first(holes = setOf(1)) }
        rig.gateway.moreResult = { rig.retryFailure(holesBefore = 1) }
        var update = rig.traversal.open(rig.request.id)
        repeat(3) { index ->
            val previous = assertNotNull(update.more)
            update = rig.traversal.more(previous)
            assertEquals(CollectionFrontier(1, 3, 1), update.run.frontier)
            assertEquals(index + 1, update.run.retryableFailuresAtFrontier)
            assertEquals(1, rig.repository.pages.getValue(rig.observation.id).size)
            assertEquals(3, update.snapshot.members.size)
            if (index < 2) assertNotSame(previous, update.more)
            assertFailsWith<VeyraException> { rig.traversal.more(previous) }
        }
        assertNull(update.more)
        assertEquals(CollectionRunStopReason.RETRY_EXHAUSTED, update.run.stoppedReason)
        assertEquals(3, rig.gateway.continuations.size)
        assertTrue(rig.repository.appended.drop(1).all { it.failureAttemptId != null })
        assertEquals(3, rig.repository.appended.drop(1).map { it.failureAttemptId }.toSet().size)
    }

    @Test fun concurrentDuplicateMoreCannotConsumeTheCurrentOrSubsequentPageTwice() = runTest {
        val rig = Rig(); rig.initialize()
        val entered = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        rig.gateway.moreResult = { entered.complete(Unit); respond.await(); rig.next() }
        val first = rig.traversal.open(rig.request.id)
        val ticket = assertNotNull(first.more)
        val more = async { rig.traversal.more(ticket) }
        entered.await()
        assertEquals("collection_more_action_stale", assertFailsWith<VeyraException> { rig.traversal.more(ticket) }.failure.detail)
        assertEquals(1, rig.gateway.continuations.size)
        respond.complete(Unit)
        assertEquals(CollectionTraversal.OBSERVED_TO_END, more.await().snapshot.extent.traversal)
        assertFailsWith<VeyraException> { rig.traversal.more(ticket) }
        assertEquals(1, rig.gateway.continuations.size)
    }

    @Test fun idleRouteCleanupIsOfflineRoomOnlyAndDuplicateOrAbsentTicketIsAnAcknowledgedNoOp() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        val ticket = assertNotNull(first.more)
        rig.offline = true
        val stopped = assertNotNull(rig.traversal.abandonIfIdle(ticket))
        assertEquals(first.snapshot.members, stopped.members)
        assertEquals(CollectionTraversal.INCOMPLETE, stopped.extent.traversal)
        assertEquals(CollectionRunStopReason.CANCELLED, rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
        val seals = rig.repository.sealed.size
        assertNull(rig.traversal.abandonIfIdle(ticket))
        assertNull(rig.traversal.abandonIfIdle(CollectionMoreTicket(CollectionObservationId("absent"), ticket.expectedFrontier)))
        assertEquals(seals, rig.repository.sealed.size)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(1, rig.gateway.collections.size)
        assertTrue(rig.gateway.continuations.isEmpty())
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
    }

    @Test fun consumedTicketCleanupCannotReleaseANewerTicketEvenAtTheSameFrontier() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        val consumed = assertNotNull(first.more)
        rig.gateway.moreResult = { rig.retryFailure() }
        val retry = rig.traversal.more(consumed)
        val current = assertNotNull(retry.more)
        assertNotSame(consumed, current)
        assertEquals(consumed.expectedFrontier, current.expectedFrontier)
        val seals = rig.repository.sealed.size
        assertNull(rig.traversal.abandonIfIdle(consumed))
        assertNull(rig.traversal.abandonIfIdle(CollectionMoreTicket(current.observationId, current.expectedFrontier)))
        assertEquals(seals, rig.repository.sealed.size)
        assertNull(rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
        rig.gateway.moreResult = { rig.next() }
        val terminal = rig.traversal.more(current)
        assertNull(terminal.more)
        assertNull(rig.traversal.abandonIfIdle(current))
        assertEquals(CollectionTraversal.OBSERVED_TO_END, terminal.snapshot.extent.traversal)
        assertEquals(2, rig.gateway.continuations.size)
    }

    @Test fun matchingInFlightCleanupFailsWithoutReleasingTheCurrentOrItsLaterTicket() = runTest {
        val rig = Rig(); rig.initialize()
        val entered = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        rig.gateway.moreResult = {
            entered.complete(Unit); respond.await()
            rig.next(continuation = AuthoredContinuation(), traversal = CollectionTraversal.MORE_AVAILABLE)
        }
        val ticket = assertNotNull(rig.traversal.open(rig.request.id).more)
        val action = async { rig.traversal.more(ticket) }
        entered.await()
        val seals = rig.repository.sealed.size
        assertEquals("collection_more_action_stale", assertFailsWith<VeyraException> {
            rig.traversal.abandonIfIdle(ticket)
        }.failure.detail)
        assertEquals(seals, rig.repository.sealed.size)
        assertEquals(1, rig.gateway.continuations.size)
        respond.complete(Unit)
        val current = assertNotNull(action.await().more)
        assertNull(rig.traversal.abandonIfIdle(ticket))
        assertNull(rig.repository.state.value.runs.getValue(ticket.observationId).stoppedReason)
        assertNotNull(rig.traversal.abandonIfIdle(current))
    }

    @Test fun cleanupStillCommitsAfterItsCallingRouteIsCancelled() = runTest {
        val rig = Rig(); rig.initialize()
        val ticket = assertNotNull(rig.traversal.open(rig.request.id).more)
        val entered = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        rig.repository.beforeSeal = { entered.complete(Unit); commit.await() }
        val cleanup = launch { rig.traversal.abandonIfIdle(ticket) }
        entered.await(); cleanup.cancel()
        assertFalse(cleanup.isCompleted, "The Room settlement remains owned until acknowledgement")
        commit.complete(Unit); cleanup.join()
        assertTrue(cleanup.isCancelled)
        assertEquals(CollectionRunStopReason.CANCELLED, rig.repository.state.value.runs.getValue(ticket.observationId).stoppedReason)
        assertNull(rig.traversal.abandonIfIdle(ticket))
        assertTrue(rig.gateway.continuations.isEmpty())
    }

    @Test fun failedCleanupRetainsOnlyTheExactRoomOnlyRetryAndNeverResurrectsItsCursor() = runTest {
        val rig = Rig(maxOpen = 1); rig.initialize()
        val ticket = assertNotNull(rig.traversal.open(rig.request.id).more)
        rig.repository.beforeSeal = { error("authored-private-storage-transcript") }
        repeat(2) {
            val failure = assertFailsWith<VeyraException> { rig.traversal.abandonIfIdle(ticket) }
            assertEquals(AppFailure(FailureCode.STORAGE_IO, "collection_settlement_unconfirmed"), failure.failure)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("private-"))
        }
        assertNull(rig.repository.state.value.runs.getValue(ticket.observationId).stoppedReason)
        assertEquals("collection_cursor_lost", assertFailsWith<VeyraException> { rig.traversal.more(ticket) }.failure.detail)
        assertEquals("collection_open_traversal_limit", assertFailsWith<VeyraException> {
            rig.traversal.open(rig.request.id)
        }.failure.detail)
        rig.repository.beforeSeal = {}
        rig.offline = true
        assertNotNull(rig.traversal.abandonIfIdle(ticket))
        assertNull(rig.traversal.abandonIfIdle(ticket))
        assertEquals(CollectionRunStopReason.CANCELLED, rig.repository.state.value.runs.getValue(ticket.observationId).stoppedReason)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(1, rig.gateway.collections.size)
        assertTrue(rig.gateway.continuations.isEmpty())
    }

    @Test fun cancellationDuringMoreDiscardsAnUncertainCursorAndSealsOnlyTheKnownPrefix() = runTest {
        val rig = Rig(); rig.initialize()
        val entered = CompletableDeferred<Unit>()
        rig.gateway.moreResult = { entered.complete(Unit); awaitCancellation() }
        val first = rig.traversal.open(rig.request.id)
        val ticket = assertNotNull(first.more)
        val more = launch { rig.traversal.more(ticket) }
        entered.await(); more.cancelAndJoin()
        val run = rig.repository.state.value.runs.getValue(rig.observation.id)
        assertEquals(CollectionRunStopReason.CANCELLED, run.stoppedReason)
        assertEquals(CollectionTraversal.INCOMPLETE, run.extent.traversal)
        assertEquals(1, rig.repository.appended.size)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertFailsWith<VeyraException> { rig.traversal.more(ticket) }
        assertEquals(1, rig.gateway.continuations.size)
    }

    @Test fun aFailedLostCursorSealReportsUnconfirmedStorageAndNeverReusesTheCursor() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        val ticket = assertNotNull(first.more)
        rig.gateway.moreResult = { error("authored-private-source-transcript") }
        rig.repository.beforeSeal = { error("authored-private-storage-transcript") }
        val failure = assertFailsWith<VeyraException> { rig.traversal.more(ticket) }
        assertEquals(AppFailure(FailureCode.STORAGE_IO, "collection_settlement_unconfirmed"), failure.failure)
        assertNull(failure.cause)
        assertTrue(failure.suppressedExceptions.isEmpty())
        assertFalse(failure.toString().contains("private-"))
        assertNull(rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertEquals("collection_cursor_lost", assertFailsWith<VeyraException> { rig.traversal.more(ticket) }.failure.detail)
        rig.repository.beforeSeal = {}
        rig.offline = true
        assertEquals(CollectionRunStopReason.CURSOR_LOST, rig.traversal.restoreLostCursors().runs.getValue(first.run.id).stoppedReason)
        assertEquals(1, rig.gateway.continuations.size)
        assertEquals(1, rig.gateway.collections.size)
        assertEquals(1, rig.gateway.acquisitions.size)
    }

    @Test fun cancellationStillPropagatesWhenLostCursorRepairCannotCommit() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        val entered = CompletableDeferred<Unit>()
        rig.gateway.moreResult = { entered.complete(Unit); awaitCancellation() }
        rig.repository.beforeSeal = { error("authored failed repair") }
        val action = launch { rig.traversal.more(assertNotNull(first.more)) }
        entered.await(); action.cancelAndJoin()
        assertTrue(action.isCancelled)
        assertNull(rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
        assertEquals("collection_cursor_lost", assertFailsWith<VeyraException> { rig.traversal.more(assertNotNull(first.more)) }.failure.detail)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertEquals(1, rig.gateway.continuations.size)
    }

    @Test fun aReturnedInitialPageWithUnconfirmedSealRemainsRoomOnlyRepairableNotAnAvailableAction() = runTest {
        val rig = Rig(); rig.initialize()
        rig.repository.beforeSeal = { error("authored failed initial seal") }
        val failure = assertFailsWith<VeyraException> { rig.traversal.open(rig.request.id) }
        assertEquals(AppFailure(FailureCode.STORAGE_IO, "collection_settlement_unconfirmed"), failure.failure)
        assertEquals(1, rig.repository.appended.size)
        assertEquals(3, rig.repository.state.value.runs.getValue(rig.observation.id).frontier.nextOrdinal)
        assertTrue(rig.repository.state.value.snapshots.isEmpty())
        rig.repository.beforeSeal = {}
        rig.offline = true
        val repaired = rig.traversal.restoreLostCursors()
        assertEquals(CollectionRunStopReason.CURSOR_LOST, repaired.runs.getValue(rig.observation.id).stoppedReason)
        assertEquals(3, repaired.snapshots.values.single().members.size)
        assertEquals(1, rig.gateway.collections.size)
        assertTrue(rig.gateway.continuations.isEmpty())
    }

    @Test fun knownPageReturnedIntoCancellationStillPersistsBeforeItsNewCursorIsDiscarded() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.moreResult = {
            currentCoroutineContext().job.cancel(CancellationException("authored return cancellation"))
            rig.next(continuation = AuthoredContinuation(), traversal = CollectionTraversal.MORE_AVAILABLE)
        }
        val first = rig.traversal.open(rig.request.id)
        val action = launch { rig.traversal.more(assertNotNull(first.more)) }
        action.join()
        assertTrue(action.isCancelled)
        assertEquals(2, rig.repository.appended.size)
        assertEquals(4, rig.repository.state.value.runs.getValue(rig.observation.id).frontier.nextOrdinal)
        assertEquals(CollectionRunStopReason.CANCELLED, rig.repository.state.value.runs.getValue(rig.observation.id).stoppedReason)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
    }

    @Test fun offlineMoreDoesNotConsumeTheExistingActionOrMakeAnySourceRequest() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.moreResult = { rig.next() }
        val ticket = assertNotNull(rig.traversal.open(rig.request.id).more)
        rig.offline = true
        assertEquals(FailureCode.OFFLINE_POLICY, assertFailsWith<VeyraException> { rig.traversal.more(ticket) }.failure.code)
        assertTrue(rig.gateway.continuations.isEmpty())
        rig.offline = false
        assertEquals(CollectionTraversal.OBSERVED_TO_END, rig.traversal.more(ticket).snapshot.extent.traversal)
        assertEquals(1, rig.gateway.continuations.size)
    }

    @Test fun aPageWithLostEarlierHolesIsRejectedWithoutChangingTheEarlierSnapshot() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.collectionResult = { _, _ -> rig.first(holes = setOf(1)) }
        rig.gateway.moreResult = { rig.next(holesBefore = 0) }
        val first = rig.traversal.open(rig.request.id)
        assertEquals("collection_delta_extent_mismatch", assertFailsWith<VeyraException> {
            rig.traversal.more(assertNotNull(first.more))
        }.failure.detail)
        assertEquals(1, rig.repository.appended.size)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertEquals(CollectionRunStopReason.CURSOR_LOST, rig.repository.state.value.runs.getValue(rig.observation.id).stoppedReason)
    }

    @Test fun foreignObservationOrChangedSourceOrdinalCannotBeAppendedToTheSavedPrefix() = runTest {
        for (foreign in listOf(true, false)) {
            val rig = Rig(); rig.initialize()
            rig.gateway.moreResult = {
                val page = rig.next()
                page.copy(data = if (foreign) page.data.copy(observationId = CollectionObservationId("foreign-observation"))
                    else page.data.copy(members = listOf(page.data.members.single().let { it.copy(entry = it.entry.copy(ordinal = 2)) })))
            }
            val first = rig.traversal.open(rig.request.id)
            assertFailsWith<VeyraException> { rig.traversal.more(assertNotNull(first.more)) }
            assertEquals(1, rig.repository.appended.size)
            assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        }
    }

    @Test fun explicitRefreshGetsANewObservationWithoutRewritingAnOldSnapshotOrResearchingTheParent() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        val fresh = authoredObservation(rig.request, "new-observation")
        rig.gateway.collectionResult = { _, _ -> SourceCollectionPage(CollectionPageData(rig.selection, fresh.id, fresh,
            CollectionPageDisposition.OBSERVED_DELTA, 0, 0, listOf(authoredMember(fresh, 0)),
            CollectionExtent(CollectionTraversal.OBSERVED_TO_END, 1, 0)), null) }
        val second = rig.traversal.open(rig.request.id)
        assertNotEquals(first.snapshot.observationId, second.snapshot.observationId)
        assertEquals(first.snapshot, rig.repository.state.value.snapshots[first.snapshot.id])
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(2, rig.gateway.collections.size)
    }

    @Test fun repeatedObservationIdOnRefreshCannotRetireTheStillOwnedOriginalCursor() = runTest {
        val rig = Rig(); rig.initialize()
        rig.gateway.moreResult = { rig.next() }
        val first = rig.traversal.open(rig.request.id)
        assertEquals("collection_observation_not_new", assertFailsWith<VeyraException> { rig.traversal.open(rig.request.id) }.failure.detail)
        assertNull(rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
        assertEquals(CollectionTraversal.OBSERVED_TO_END, rig.traversal.more(assertNotNull(first.more)).snapshot.extent.traversal)
    }

    @Test fun recreatedCoordinatorRestoresLostCursorsUsingOnlySavedRoomPortValues() = runTest {
        val rig = Rig(); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        rig.offline = true
        val newOwner = CollectionTraversalCoordinator(rig.repository, rig.gateway, rig.clock, { rig.offline })
        val restored = newOwner.restoreLostCursors()
        assertEquals(CollectionRunStopReason.CURSOR_LOST, restored.runs.getValue(first.run.id).stoppedReason)
        assertEquals(first.snapshot, restored.snapshots[first.snapshot.id])
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(1, rig.gateway.collections.size)
        assertTrue(rig.gateway.continuations.isEmpty())
        assertTrue(rig.gateway.inspections.isEmpty())
    }

    @Test fun openCursorCapacityIsBoundedWithoutSilentlyEvictingTheExistingObservation() = runTest {
        val rig = Rig(maxOpen = 1); rig.initialize()
        val first = rig.traversal.open(rig.request.id)
        assertEquals("collection_open_traversal_limit", assertFailsWith<VeyraException> { rig.traversal.open(rig.request.id) }.failure.detail)
        assertEquals(1, rig.gateway.collections.size)
        val stopped = rig.traversal.abandon(assertNotNull(first.more))
        assertEquals(CollectionTraversal.INCOMPLETE, stopped.extent.traversal)
        assertEquals(CollectionRunStopReason.CANCELLED, rig.repository.state.value.runs.getValue(first.run.id).stoppedReason)
    }
}
