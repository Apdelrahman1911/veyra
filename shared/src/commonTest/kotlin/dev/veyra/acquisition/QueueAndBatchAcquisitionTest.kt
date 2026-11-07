package dev.veyra.acquisition

import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Admission and source scheduling with explicit fake ports; no native/transfer/Room claim. */
class QueueAndBatchAcquisitionTest {
    private class Rig {
        val repository = AcquisitionTestRepository()
        val gateway = AcquisitionTestGateway()
        val clock = AcquisitionTestClock()
        val acquisition = AcquisitionCoordinator(repository, gateway, clock = clock, offline = { false })
        val factory = AcquisitionAdmissionFactory(clock = clock)
        val queue = QueueAcquisitionCoordinator(repository, acquisition)
        val batch = DownloadBatchCoordinator(repository, acquisition, clock)
        val prepared get() = factory.collection(authoredSnapshot(count = 5), ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.MUSIC)

        suspend fun admit(prepared: PreparedAcquisitionAdmission = this.prepared): ExecutionQueue {
            queue.admit(prepared, QueueContext.MUSIC, 0, QueueAdmissionOperation.REPLACE)
            return repository.state.value.queues.getValue(QueueContext.MUSIC)
        }
    }

    @Test fun admittingAWholeSourceListingCapturesIntentWithoutEagerlySelectingItsMembers() = runTest {
        val rig = Rig()
        val prepared = rig.prepared
        val queue = rig.admit(prepared)
        assertEquals(prepared.occurrences.map { it.id }, queue.displayedOrder)
        assertEquals(5, rig.repository.captured.size)
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.repository.claimed.isEmpty())
        assertTrue(rig.repository.state.value.bindings.isEmpty())
    }

    @Test fun currentThenAtMostOneMusicNeighbourAreSelectedWithoutSearchingTheRemainingQueue() = runTest {
        val rig = Rig()
        val queue = rig.admit()
        val current = assertNotNull(queue.currentOccurrenceId)
        rig.queue.resolveCurrent(queue.context, queue.editRevision, current)
        rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current)
        assertEquals(2, rig.gateway.acquisitions.size)
        assertEquals(2, rig.repository.state.value.occurrences.values.count { it.selectionState == OccurrenceSelectionState.BOUND })
        assertEquals(3, rig.repository.state.value.occurrences.values.count { it.selectionState == OccurrenceSelectionState.PENDING })
        rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current)
        assertEquals(2, rig.gateway.acquisitions.size, "A pinned prefetched occurrence never gets another query")
    }

    @Test fun podcastControlsSuppressPrefetchEvenInTheMusicQueueAndWithoutAnEpisodeIdentity() = runTest {
        val rig = Rig()
        val prepared = rig.factory.collection(authoredSnapshot(), ObservedCollectionChoice.WholeObservedListing, PlaybackControlsMode.PODCAST)
        val queue = rig.admit(prepared)
        val current = assertNotNull(queue.currentOccurrenceId)
        rig.queue.resolveCurrent(queue.context, queue.editRevision, current)
        assertNull(rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current))
        assertEquals(1, rig.gateway.acquisitions.size)
        assertTrue(rig.repository.state.value.subjects.values.all { it is ExecutableSubject.SourceRecordingSubject })
    }

    @Test fun concurrentPrefetchCallsDoNotStartParallelNeighbourQueries() = runTest {
        val rig = Rig()
        val queue = rig.admit()
        val current = assertNotNull(queue.currentOccurrenceId)
        rig.queue.resolveCurrent(queue.context, queue.editRevision, current)
        val entered = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        rig.gateway.acquireResult = { entered.complete(Unit); respond.await(); authoredSingle(it) }
        val first = async { rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current) }
        entered.await()
        assertNull(rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current))
        assertEquals(2, rig.gateway.acquisitions.size)
        respond.complete(Unit)
        assertIs<AcquisitionResolution.Accepted>(first.await())
    }

    @Test fun anUnplayableNextMemberNeverPromotesALaterQueuePosition() = runTest {
        val rig = Rig()
        val queue = rig.admit()
        val current = assertNotNull(queue.currentOccurrenceId)
        rig.queue.resolveCurrent(queue.context, queue.editRevision, current)
        rig.gateway.acquireResult = { request ->
            val first = authoredCollection(request)
            SourceAcquisitionResult.Unplayable(request.id, first.receipt, AcquisitionProblem(AcquisitionProblemCode.MEMBER_NOT_PLAYABLE))
        }
        val result = assertIs<AcquisitionResolution.Accepted>(rig.queue.resolveMusicPrefetch(queue.context, queue.editRevision, current))
        assertIs<AcquisitionOutcome.Unplayable>(result.record.outcome)
        assertEquals(2, rig.gateway.acquisitions.size)
        assertTrue(rig.gateway.collections.isEmpty())
        assertEquals(3, rig.repository.state.value.occurrences.values.count { it.selectionState == OccurrenceSelectionState.PENDING })
    }

    @Test fun staleQueueRevisionFailsBeforeAnyMemberQuery() = runTest {
        val rig = Rig()
        val queue = rig.admit()
        assertEquals("queue_selection_action_stale", assertFailsWith<VeyraException> {
            rig.queue.resolveCurrent(queue.context, queue.editRevision - 1, queue.currentOccurrenceId!!)
        }.failure.detail)
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun outgoingOccurrenceCheckpointAndAdmissionAreForwardedInTheSameRepositoryCommand() = runTest {
        val rig = Rig()
        val prepared = rig.prepared
        val checkpoint = OccurrenceCheckpoint(ExecutionOccurrenceId("outgoing"), ExecutionBindingId("outgoing-binding"),
            42_000, 90_000, 123_000, "authored-session", 8, 21, false)
        rig.queue.admit(prepared, QueueContext.MUSIC, 0, QueueAdmissionOperation.REPLACE, checkpoint)
        val command = rig.repository.queueAdmissions.single()
        assertSame(checkpoint, command.outgoingCheckpoint)
        assertEquals(prepared.requests, command.requests)
        assertEquals(prepared.scope, command.scope)
        assertEquals(prepared.occurrences, command.occurrences)
        assertTrue(rig.gateway.acquisitions.isEmpty())
    }

    @Test fun durableBatchHasOnlyIncludedOccurrencesAndNoFabricatedParentFileOrEagerQuery() = runTest {
        val rig = Rig()
        val prepared = rig.factory.collection(authoredSnapshot(holes = setOf(1)), ObservedCollectionChoice.Available(true, false), PlaybackControlsMode.MUSIC)
        val batch = rig.batch.admit(prepared)
        assertEquals(2, batch.members.size)
        assertEquals(listOf(0, 2), batch.members.map { it.scopeMemberPosition })
        assertEquals(prepared.requests.map { it.id }, batch.members.map { it.requestId })
        assertTrue(batch.members.all { it.state == DownloadBatchMemberState.PENDING_SELECTION && it.claimId == null && it.requiredBindingId == null })
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.repository.state.value.claims.isEmpty())
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }

    @Test fun oneBatchMemberResolutionPinsItsExactBindingButDoesNotClaimPhysicalExecution() = runTest {
        val rig = Rig()
        val batch = rig.batch.admit(rig.prepared)
        rig.batch.resolveMember(batch.id, 2, batch.intentRevision)
        val updated = rig.repository.state.value.batches.getValue(batch.id)
        assertNotNull(updated.members[2].requiredBindingId)
        assertNull(updated.members[2].claimId)
        assertEquals(DownloadBatchMemberState.WAITING_FOR_JOB, updated.members[2].state)
        assertEquals(1, rig.gateway.acquisitions.size)
        assertEquals(4, updated.members.count { it.state == DownloadBatchMemberState.PENDING_SELECTION })
        assertTrue(rig.repository.state.value.claims.isEmpty())
    }

    @Test fun pauseAndCancelOnlyWriteThisBatchesClaimIntentAndRejectStaleSelectionActions() = runTest {
        val rig = Rig()
        val first = rig.batch.admit(rig.prepared)
        val other = rig.batch.admit(rig.prepared)
        val paused = rig.batch.setIntent(first.id, 0, DownloadBatchIntent.PAUSED)
        assertEquals(DownloadBatchIntent.PAUSED, paused.intent)
        assertEquals(1L, paused.intentRevision)
        assertEquals(DownloadBatchIntent.ACTIVE, rig.repository.state.value.batches.getValue(other.id).intent)
        assertFailsWith<VeyraException> { rig.batch.resolveMember(first.id, 0, 0) }
        assertFailsWith<VeyraException> { rig.batch.resolveMember(first.id, 0, paused.intentRevision) }
        val cancelled = rig.batch.setIntent(first.id, paused.intentRevision, DownloadBatchIntent.CANCELLED, retainCompletedAssets = true)
        assertEquals(DownloadBatchIntent.CANCELLED, cancelled.intent)
        assertTrue(rig.repository.batchIntents.all { it.batchId == first.id && it.retainCompletedAssets })
        assertTrue(rig.gateway.acquisitions.isEmpty())
        assertTrue(rig.repository.state.value.assetUses.isEmpty())
    }
}
