package dev.veyra.playback

import dev.veyra.downloads.DownloadMaterializationEvidence
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.test.*

/**
 * Real controller/LibraryStore/pure canonical commands, not Room or native execution.
 * The same-owner repository decorator suspends BEFORE the actual command's transaction so an
 * independent canonical write can invalidate its final CAS. No fabricated successful ACKs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionAdmissionStageCasTest {
    @Test fun staleDisplayedLibraryRevisionDoesNotCancelTheStageOrAttemptAnAdmission() = runTest {
        val rig = Fixture(this)
        try {
            val stage = rig.startStage()
            val command = rig.admission()
            rig.store.update { it.copy(settings = it.settings.copy(theme = ThemeMode.DARK)) }.getOrThrow()
            runCurrent()
            val before = rig.persistence.graph
            val attempts = rig.attemptedAdmissions.size
            val undo = rig.state.undoableQueueContexts

            val failed = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
            runCurrent()

            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertEquals("acquisition_stale_library_revision", failed.failure.detail)
            assertEquals(attempts, rig.attemptedAdmissions.size)
            assertSame(before, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            rig.assertStage(stage)
            assertFalse(command.prepared.requests.single().id in rig.durable.acquisition.requests)
        } finally { rig.close() }
    }

    @Test fun staleDisplayedQueueRevisionDoesNotCancelTheStageOrAttemptAnAdmission() = runTest {
        val rig = Fixture(this)
        try {
            val stage = rig.startStage()
            val command = rig.admission().let { it.copy(expectedQueueRevision = it.expectedQueueRevision - 1) }
            val before = rig.persistence.graph
            val attempts = rig.attemptedAdmissions.size
            val undo = rig.state.undoableQueueContexts

            val failed = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
            runCurrent()

            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertEquals(attempts, rig.attemptedAdmissions.size)
            assertSame(before, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            rig.assertStage(stage)
        } finally { rig.close() }
    }

    @Test fun rejectedAdmissionDoesNotCancelOrRepeatTheAlreadySentNeighbourSelection() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        try {
            val outgoing = rig.startQueue()
            val incoming = rig.queue(QueueContext.MUSIC).displayedOrder.last()
            val requestId = assertNotNull(rig.durable.acquisition.occurrences.getValue(incoming).requestId)
            var sourceCancelled = false
            rig.gateway.selections.acquireResult = { request ->
                assertEquals(requestId, request.id)
                try { gate.await() }
                catch (cancelled: CancellationException) { sourceCancelled = true; throw cancelled }
                authoredSingle(request)
            }
            rig.requestStage(); gate.entered.await(); runCurrent()
            val stage = assertNotNull(rig.state.crossfade)
            val generation = rig.state.generation
            val stops = rig.engine.stops.toList()
            val cancellations = rig.engine.cancellations.toList()
            assertTrue(rig.engine.preloads.isEmpty())
            assertEquals(AcquisitionJobState.CLAIMED, rig.durable.acquisition.requests.getValue(requestId).state)
            assertEquals(2, rig.gateway.selections.acquisitions.size)
            val command = rig.admission()
            rig.store.update { it.copy(settings = it.settings.copy(theme = ThemeMode.DARK)) }.getOrThrow()
            runCurrent()

            val failed = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
            runCurrent()
            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertFalse(sourceCancelled)
            assertEquals(stage, rig.state.crossfade)
            assertEquals(generation, rig.state.generation)
            assertEquals(outgoing.value, rig.state.currentEntryId)
            assertEquals(stops, rig.engine.stops)
            assertEquals(cancellations, rig.engine.cancellations)
            assertEquals(AcquisitionJobState.CLAIMED, rig.durable.acquisition.requests.getValue(requestId).state)

            gate.release.complete(Unit); runCurrent()
            assertFalse(sourceCancelled)
            assertEquals(AcquisitionJobState.COMMITTED, rig.durable.acquisition.requests.getValue(requestId).state)
            assertEquals(stage.incomingGeneration, rig.engine.preloads.single().generation)
            assertEquals(incoming, assertNotNull(rig.engine.preloads.single().acquired).selection.occurrenceId)
            assertEquals(2, rig.gateway.selections.acquisitions.size)
            assertFalse(command.prepared.requests.single().id in rig.durable.acquisition.requests)
        } finally { gate.release.complete(Unit); rig.close() }
    }

    @Test fun libraryDriftWhileApplyIsSuspendedRejectsWithoutCancellingTheStillValidStage() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        try {
            val stage = rig.startStage()
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val command = rig.admission()
            rig.beforeAdmit = { gate.await() }
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            rig.assertStage(stage)

            // Not an offline/rate/selection change: the old MUSIC stage is still authoritative.
            rig.store.update { it.copy(settings = it.settings.copy(language = LanguagePreference.ARABIC)) }.getOrThrow()
            val afterIndependentWrite = rig.persistence.graph
            val acknowledged = rig.acknowledgedAdmissions.size
            val undo = rig.state.undoableQueueContexts
            assertNotEquals(command.prepared.expectedCatalogRevision, rig.durable.snapshot.revision)
            assertEquals(command.expectedQueueRevision, rig.queue(QueueContext.MUSIC).editRevision)
            rig.assertStage(stage)

            gate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertEquals("acquisition_stale_library_revision", failed.failure.detail)
            assertEquals(acknowledged, rig.acknowledgedAdmissions.size)
            assertSame(afterIndependentWrite, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            assertFalse(command.prepared.requests.single().id in rig.durable.acquisition.requests)
            rig.assertStage(stage)

            // A rejected checkpoint-bearing admission must not clear the outgoing dirty sample.
            rig.controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow(); runCurrent()
            assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
            assertTrue(rig.durable.checkpoints.getValue(stage.outgoing).sequence > 0)
            rig.assertStage(stage)
        } finally { gate.release.complete(Unit); rig.close() }
    }

    @Test fun inactiveQueueDriftWhileApplyIsSuspendedCannotCancelTheUnchangedActiveStage() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        try {
            val stage = rig.startStage()
            // Null library CAS isolates the final queue CAS rather than failing at library CAS.
            val command = rig.admission(QueueContext.PODCAST, expectedLibraryRevision = null)
            rig.beforeAdmit = { gate.await() }
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            rig.assertStage(stage)

            val podcast = rig.queue(QueueContext.PODCAST)
            rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.EditQueue(
                ExecutionQueueExpectation(podcast.context, podcast.editRevision, podcast.currentOccurrenceId),
                podcast.displayedOrder, podcast.originalOrder, podcast.currentOccurrenceId,
                podcast.positionMs, podcast.speed, podcast.shuffle, podcast.repeat, null,
            ))
            val afterIndependentWrite = rig.persistence.graph
            val acknowledged = rig.acknowledgedAdmissions.size
            val undo = rig.state.undoableQueueContexts
            assertEquals(podcast.editRevision + 1, rig.queue(QueueContext.PODCAST).editRevision)
            assertEquals(stage.musicQueueRevision, rig.queue(QueueContext.MUSIC).editRevision)
            rig.assertStage(stage)

            gate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertEquals("acquisition_queue_revision_stale", failed.failure.detail)
            assertEquals(acknowledged, rig.acknowledgedAdmissions.size)
            assertSame(afterIndependentWrite, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            assertFalse(command.prepared.requests.single().id in rig.durable.acquisition.requests)
            rig.assertStage(stage)
        } finally { gate.release.complete(Unit); rig.close() }
    }

    @Test fun rejectedSuspendedAcknowledgementRetainsQueuedStageEventsAndDirtyOutgoingSample() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        try {
            val stage = rig.startStage()
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val command = rig.admission()
            val requestId = command.prepared.requests.single().id
            val before = rig.persistence.graph
            val undo = rig.state.undoableQueueContexts
            val acknowledged = rig.acknowledgedAdmissions.size
            rig.persistence.beforeAcknowledgement = { proposed ->
                if (requestId in proposed.state().requests) {
                    // Pure canonical admission/checkpoint has been calculated, not published.
                    assertEquals(86_125L, proposed.playbackView().checkpoints.getValue(stage.outgoing).positionMs)
                    gate.await()
                    throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_admission_ack_rejected"))
                }
            }
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            assertSame(before, rig.persistence.graph)
            rig.assertStage(stage)

            // This goes through the real event collector/mailbox while the actor is in apply.
            // It must still belong to the old stage if that admission later fails.
            rig.engine.emit(EngineEvent.StageReady(stage.outgoingGeneration, stage.native.generation, 100_000, true))
            runCurrent()
            assertEquals(stage.ui, rig.state.crossfade)
            gate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.STORAGE_IO, failed.failure.code)
            assertEquals(acknowledged, rig.acknowledgedAdmissions.size)
            assertSame(before, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            rig.assertStage(stage, stage.ui.copy(phase = CrossfadePhase.READY))

            rig.persistence.beforeAcknowledgement = {}
            rig.controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow(); runCurrent()
            assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
            assertEquals(86_125L, rig.durable.history.single().lastPositionMs)
            assertFalse(requestId in rig.durable.acquisition.requests)
            rig.assertStage(stage, stage.ui.copy(phase = CrossfadePhase.READY))
        } finally {
            gate.release.complete(Unit)
            rig.persistence.beforeAcknowledgement = {}
            rig.close()
        }
    }

    @Test fun successfulAdmissionCancelsOnlyAfterTheQueueAndOutgoingCheckpointAreAcknowledged() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        try {
            val stage = rig.startStage()
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val command = rig.admission()
            val requestId = command.prepared.requests.single().id
            val occurrenceId = command.prepared.occurrences.single().id
            val oldRequests = rig.gateway.selections.acquisitions.size
            var sawAcknowledgementBeforeCancel = false
            var sawNativeCancellation = false
            rig.beforeAdmit = { gate.await() }
            rig.afterAdmit = { _, result ->
                assertTrue(requestId in result.acquisition.requests)
                assertEquals(stage.musicQueueRevision + 1, result.acquisition.queues.getValue(QueueContext.MUSIC).editRevision)
                assertEquals(stage.outgoing, result.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
                assertEquals(86_125L, result.checkpoints.getValue(stage.outgoing).positionMs)
                assertTrue(result.checkpoints.getValue(stage.outgoing).sequence > 0)
                // The canonical ACK is real, but the controller has not yet issued cancellation.
                rig.assertStage(stage)
                sawAcknowledgementBeforeCancel = true
            }
            rig.engine.beforeCancelNext = {
                assertTrue(sawAcknowledgementBeforeCancel)
                assertTrue(requestId in rig.durable.acquisition.requests)
                assertTrue(occurrenceId in rig.queue(QueueContext.MUSIC).displayedOrder)
                assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
                sawNativeCancellation = true
            }
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            rig.assertStage(stage)
            gate.release.complete(Unit)
            assertIs<PlaybackResult.Accepted>(dispatched.await())
            runCurrent()

            assertTrue(sawAcknowledgementBeforeCancel)
            assertTrue(sawNativeCancellation)
            assertEquals(stage.cancellations + (stage.outgoingGeneration to stage.native.generation), rig.engine.cancellations)
            assertEquals(stage.outgoing.value, rig.state.currentEntryId)
            assertEquals(stage.outgoingGeneration, rig.state.generation)
            assertEquals(stage.stops, rig.engine.stops)
            assertEquals(stage.loads, rig.engine.loads)
            assertEquals(oldRequests, rig.gateway.selections.acquisitions.size,
                "Appending a third request must not eagerly search it; the existing next receipt can be restaged")
            assertEquals(OccurrenceSelectionState.PENDING, rig.durable.acquisition.occurrences.getValue(occurrenceId).selectionState)
            assertTrue(QueueContext.MUSIC in rig.state.undoableQueueContexts)
        } finally {
            gate.release.complete(Unit)
            rig.afterAdmit = { _, _ -> }
            rig.engine.beforeCancelNext = null
            rig.close()
        }
    }

    @Test fun rejectedActivationAfterThePreliminaryReadRetainsTheHandedStageAndOutgoingOwner() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        val resource = CountingPlaybackResource()
        try {
            val target = rig.admitInactive(QueueContext.PODCAST)
            val stage = rig.startStage(resource)
            val targetRequest = assertNotNull(rig.durable.acquisition.occurrences.getValue(target).requestId)
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val activeBinding = assertNotNull(rig.state.currentSelection).bindingId
            val targetQueue = rig.queue(QueueContext.PODCAST)
            val preparations = rig.gateway.preparations.size
            val command = PlaybackCommand.PlayOccurrence(QueueContext.PODCAST, targetQueue.editRevision, target)
            rig.beforeActivate = { gate.await() }
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            assertEquals(1, rig.attemptedActivations.size)
            assertTrue(rig.acknowledgedActivations.isEmpty())
            assertEquals(86_125L, rig.state.positionMs)
            assertEquals(activeBinding, rig.state.currentSelection?.bindingId)
            rig.assertStage(stage)

            // The preliminary read already passed. Only the inactive target queue advances.
            rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.EditQueue(
                ExecutionQueueExpectation(targetQueue.context, targetQueue.editRevision, targetQueue.currentOccurrenceId),
                targetQueue.displayedOrder, targetQueue.originalOrder, targetQueue.currentOccurrenceId,
                targetQueue.positionMs, targetQueue.speed, targetQueue.shuffle, targetQueue.repeat, null))
            val afterIndependentWrite = rig.persistence.graph
            assertEquals(targetQueue.editRevision + 1, rig.queue(QueueContext.PODCAST).editRevision)
            assertEquals(QueueContext.MUSIC, rig.durable.snapshot.activeContext)
            rig.assertStage(stage)
            assertEquals(0, resource.closeCalls)

            gate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.INVALID_STATE, failed.failure.code)
            assertEquals("playback_queue_action_stale", failed.failure.detail)
            assertEquals(1, rig.attemptedActivations.size)
            assertTrue(rig.acknowledgedActivations.isEmpty())
            assertSame(afterIndependentWrite, rig.persistence.graph)
            assertEquals(QueueContext.MUSIC, rig.durable.snapshot.activeContext)
            assertEquals(activeBinding, rig.state.currentSelection?.bindingId)
            assertEquals(stage.outgoing.value, rig.state.currentEntryId)
            assertEquals(stage.outgoingGeneration, rig.state.generation)
            rig.assertStage(stage)
            assertEquals(0, resource.closeCalls)
            assertEquals(target, rig.queue(QueueContext.PODCAST).displayedOrder.single())
            assertEquals(OccurrenceSelectionState.PENDING, rig.durable.acquisition.occurrences.getValue(target).selectionState)
            assertEquals(AcquisitionJobState.READY, rig.durable.acquisition.requests.getValue(targetRequest).state)
            assertTrue(rig.gateway.selections.acquisitions.none { it.id == targetRequest })
            assertEquals(preparations, rig.gateway.preparations.size)
            assertEquals(stage.stops, rig.engine.stops)
            assertEquals(stage.loads, rig.engine.loads)
            assertEquals(listOf(stage.native), rig.engine.preloads)

            // The rejected transaction must not clear the still-dirty outgoing native sample.
            rig.controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow(); runCurrent()
            assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
            assertTrue(rig.durable.checkpoints.getValue(stage.outgoing).sequence > 1)
            assertEquals(86_125L, rig.durable.history.single().lastPositionMs)
            rig.assertStage(stage)
            assertEquals(0, resource.closeCalls)
        } finally {
            gate.release.complete(Unit)
            rig.beforeActivate = {}
            rig.close()
        }
    }

    @Test fun rejectedActivationAcknowledgementRetainsTheOldStageAndQueuesItsReadyEvent() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        val resource = CountingPlaybackResource()
        try {
            val target = rig.admitInactive(QueueContext.PODCAST)
            val stage = rig.startStage(resource)
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val before = rig.persistence.graph
            val undo = rig.state.undoableQueueContexts
            val targetQueue = rig.queue(QueueContext.PODCAST)
            var proposedChecks = 0
            rig.persistence.beforeAcknowledgement = { proposed ->
                val view = proposed.playbackView()
                if (view.snapshot.activeContext == QueueContext.PODCAST &&
                    view.acquisition.queues.getValue(QueueContext.PODCAST).currentOccurrenceId == target) {
                    proposedChecks++
                    val outgoing = view.checkpoints.getValue(stage.outgoing)
                    assertEquals(86_125L, outgoing.positionMs)
                    assertEquals(100_000L, outgoing.measuredDurationMs)
                    assertTrue(outgoing.sequence > 0)
                    assertEquals(stage.outgoingGeneration, outgoing.generation)
                    assertEquals(stage.outgoing, view.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
                    gate.await()
                    throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_activation_ack_rejected"))
                }
            }
            val command = PlaybackCommand.PlayOccurrence(QueueContext.PODCAST, targetQueue.editRevision, target)
            val preparations = rig.gateway.preparations.size
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            assertEquals(1, proposedChecks)
            assertEquals(1, rig.attemptedActivations.size)
            assertSame(before, rig.persistence.graph)
            assertEquals(86_125L, rig.state.positionMs)
            rig.assertStage(stage)
            assertEquals(0, resource.closeCalls)

            // Real event collector/mailbox while the activation transaction is suspended.
            rig.engine.emit(EngineEvent.StageReady(stage.outgoingGeneration, stage.native.generation, 100_000, true))
            runCurrent()
            assertEquals(stage.ui, rig.state.crossfade)
            gate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.STORAGE_IO, failed.failure.code)
            assertEquals("authored_activation_ack_rejected", failed.failure.detail)
            assertEquals(1, rig.attemptedActivations.size)
            assertTrue(rig.acknowledgedActivations.isEmpty())
            assertSame(before, rig.persistence.graph)
            assertEquals(undo, rig.state.undoableQueueContexts)
            rig.assertStage(stage, stage.ui.copy(phase = CrossfadePhase.READY))
            assertEquals(0, resource.closeCalls)

            rig.persistence.beforeAcknowledgement = {}
            rig.controller.dispatch(PlaybackCommand.FlushProgress).getOrThrow(); runCurrent()
            assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
            assertTrue(rig.durable.checkpoints.getValue(stage.outgoing).sequence > 1)
            assertEquals(86_125L, rig.durable.history.single().lastPositionMs)
            assertEquals(preparations, rig.gateway.preparations.size)
            assertEquals(stage.loads, rig.engine.loads)
            assertEquals(listOf(stage.native), rig.engine.preloads)
            rig.assertStage(stage, stage.ui.copy(phase = CrossfadePhase.READY))
            assertEquals(0, resource.closeCalls)
        } finally {
            gate.release.complete(Unit)
            rig.persistence.beforeAcknowledgement = {}
            rig.close()
        }
    }

    @Test fun rejectedActivationDoesNotCancelOrRepeatTheInFlightNeighbourSelection() = runTest {
        val rig = Fixture(this)
        val sourceGate = ApplyGate()
        val ackGate = ApplyGate()
        val resource = CountingPlaybackResource()
        try {
            val outgoing = rig.startQueue()
            val incoming = rig.queue(QueueContext.MUSIC).displayedOrder.last()
            val requestId = assertNotNull(rig.durable.acquisition.occurrences.getValue(incoming).requestId)
            var sourceCancelled = false
            rig.gateway.selections.acquireResult = { request ->
                assertEquals(requestId, request.id)
                try { sourceGate.await() }
                catch (cancelled: CancellationException) { sourceCancelled = true; throw cancelled }
                authoredSingle(request)
            }
            rig.gateway.prepare = { context, required -> rig.gateway.source(context, required, resource) }
            rig.requestStage(); sourceGate.entered.await(); runCurrent()
            val ui = assertNotNull(rig.state.crossfade)
            val generation = rig.state.generation
            val stops = rig.engine.stops.toList()
            val cancellations = rig.engine.cancellations.toList()
            assertTrue(rig.engine.preloads.isEmpty())
            assertEquals(AcquisitionJobState.CLAIMED, rig.durable.acquisition.requests.getValue(requestId).state)
            assertEquals(2, rig.gateway.selections.acquisitions.size)
            val preparations = rig.gateway.preparations.size
            val queue = rig.queue(QueueContext.MUSIC)
            rig.persistence.beforeAcknowledgement = { proposed ->
                val view = proposed.playbackView()
                if (view.snapshot.activeContext == QueueContext.MUSIC &&
                    view.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId == incoming) {
                    ackGate.await()
                    throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_activation_ack_rejected"))
                }
            }
            val command = PlaybackCommand.PlayOccurrence(QueueContext.MUSIC, queue.editRevision, incoming)
            val dispatched = async { rig.controller.dispatch(command) }
            ackGate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            assertEquals(1, rig.attemptedActivations.size)
            assertFalse(sourceCancelled)
            assertEquals(ui, rig.state.crossfade)
            assertEquals(generation, rig.state.generation)
            assertEquals(outgoing.value, rig.state.currentEntryId)
            assertEquals(stops, rig.engine.stops)
            assertEquals(cancellations, rig.engine.cancellations)
            assertEquals(AcquisitionJobState.CLAIMED, rig.durable.acquisition.requests.getValue(requestId).state)
            assertTrue(rig.engine.preloads.isEmpty())
            assertEquals(2, rig.gateway.selections.acquisitions.size)

            ackGate.release.complete(Unit)
            val failed = assertIs<PlaybackResult.Failure>(dispatched.await())
            runCurrent()
            assertEquals(FailureCode.STORAGE_IO, failed.failure.code)
            assertEquals("authored_activation_ack_rejected", failed.failure.detail)
            assertTrue(rig.acknowledgedActivations.isEmpty())
            assertFalse(sourceCancelled)
            assertEquals(ui, rig.state.crossfade)
            assertEquals(generation, rig.state.generation)
            assertEquals(outgoing.value, rig.state.currentEntryId)

            sourceGate.release.complete(Unit); runCurrent()
            assertFalse(sourceCancelled)
            assertEquals(AcquisitionJobState.COMMITTED, rig.durable.acquisition.requests.getValue(requestId).state)
            assertEquals(2, rig.gateway.selections.acquisitions.size)
            assertEquals(preparations + 1, rig.gateway.preparations.size)
            val preload = rig.engine.preloads.single()
            assertEquals(ui.incomingGeneration, preload.generation)
            assertEquals(incoming, assertNotNull(preload.acquired).selection.occurrenceId)
            assertEquals(ui.incomingGeneration, rig.state.crossfade?.incomingGeneration)
            assertEquals(outgoing.value, rig.state.currentEntryId)
            assertEquals(generation, rig.state.generation)
            assertEquals(0, resource.closeCalls)

            rig.event(EngineEvent.StageReady(generation, preload.generation, 100_000, true))
            assertEquals(CrossfadePhase.READY, rig.state.crossfade?.phase)
            assertEquals(incoming.value, rig.state.crossfade?.incomingEntryId)
            assertEquals(outgoing.value, rig.state.currentEntryId)
        } finally {
            sourceGate.release.complete(Unit)
            ackGate.release.complete(Unit)
            rig.persistence.beforeAcknowledgement = {}
            rig.close()
        }
    }

    @Test fun successfulActivationRetiresTheOldStageOnlyAfterTheCommittedAcknowledgement() = runTest {
        val rig = Fixture(this)
        val gate = ApplyGate()
        val resource = CountingPlaybackResource()
        try {
            val stage = rig.startStage(resource)
            val incoming = rig.queue(QueueContext.MUSIC).displayedOrder.last()
            val binding = assertNotNull(rig.durable.acquisition.occurrences.getValue(incoming).bindingId)
            rig.event(EngineEvent.Position(stage.outgoingGeneration, 86_125, 100_000))
            val queue = rig.queue(QueueContext.MUSIC)
            val stops = rig.engine.stops.toList()
            val cancellations = rig.engine.cancellations.toList()
            val loads = rig.engine.loads.toList()
            val preloads = rig.engine.preloads.toList()
            val preparations = rig.gateway.preparations.size
            val acquisitions = rig.gateway.selections.acquisitions.size
            var sawCommittedAck = false
            rig.beforeActivate = { gate.await() }
            rig.afterActivate = { _, committed ->
                assertEquals(incoming, committed.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
                assertEquals(queue.editRevision + 1, committed.acquisition.queues.getValue(QueueContext.MUSIC).editRevision)
                assertEquals(binding, committed.acquisition.occurrences.getValue(incoming).bindingId)
                assertEquals(86_125L, committed.checkpoints.getValue(stage.outgoing).positionMs)
                assertTrue(committed.checkpoints.getValue(stage.outgoing).sequence > 0)
                // The canonical ACK is real, but the controller has not processed it yet.
                assertSame(stage.native, rig.engine.staged)
                assertEquals(stage.ui, rig.state.crossfade)
                assertEquals(stage.outgoing.value, rig.state.currentEntryId)
                assertEquals(stage.outgoingGeneration, rig.state.generation)
                assertEquals(stops, rig.engine.stops)
                assertEquals(loads, rig.engine.loads)
                assertEquals(preparations, rig.gateway.preparations.size)
                assertEquals(0, resource.closeCalls)
                sawCommittedAck = true
            }
            val command = PlaybackCommand.PlayOccurrence(QueueContext.MUSIC, queue.editRevision, incoming, binding)
            val dispatched = async { rig.controller.dispatch(command) }
            gate.entered.await(); runCurrent()
            assertFalse(dispatched.isCompleted)
            assertEquals(1, rig.attemptedActivations.size)
            assertTrue(rig.acknowledgedActivations.isEmpty())
            assertSame(stage.native, rig.engine.staged)
            assertEquals(stage.ui, rig.state.crossfade)
            assertEquals(stage.outgoing.value, rig.state.currentEntryId)
            assertEquals(stage.outgoingGeneration, rig.state.generation)
            assertEquals(stops, rig.engine.stops)
            assertEquals(loads, rig.engine.loads)
            assertEquals(preloads, rig.engine.preloads)
            assertEquals(preparations, rig.gateway.preparations.size)
            assertEquals(acquisitions, rig.gateway.selections.acquisitions.size)
            assertEquals(0, resource.closeCalls)

            // A queued old StageReady must not promote the replaced stage after the ACK.
            rig.engine.emit(EngineEvent.StageReady(stage.outgoingGeneration, stage.native.generation, 100_000, true))
            runCurrent()
            assertEquals(stage.ui, rig.state.crossfade)
            gate.release.complete(Unit)
            assertIs<PlaybackResult.Accepted>(dispatched.await())
            runCurrent()

            assertTrue(sawCommittedAck)
            assertEquals(1, rig.attemptedActivations.size)
            assertEquals(1, rig.acknowledgedActivations.size)
            assertEquals(incoming.value, rig.state.currentEntryId)
            assertEquals(binding, rig.state.currentSelection?.bindingId)
            assertEquals(incoming, rig.durable.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
            assertEquals(binding, rig.durable.acquisition.occurrences.getValue(incoming).bindingId)
            assertEquals(86_125L, rig.durable.checkpoints.getValue(stage.outgoing).positionMs)
            assertNull(rig.state.crossfade)
            assertTrue(rig.state.desiredPlaying)
            assertNotEquals(stage.outgoingGeneration, rig.state.generation)
            assertEquals(rig.state.generation, assertNotNull(rig.engine.active).generation)
            assertSame(rig.engine.active, rig.engine.loads.last())
            assertEquals(incoming, assertNotNull(rig.engine.active?.acquired).selection.occurrenceId)
            assertEquals(1, rig.engine.preloads.size)
            assertEquals(stops.size + 1, rig.engine.stops.size)
            assertEquals(rig.state.generation, rig.engine.stops.last())
            assertEquals(loads.size + 1, rig.engine.loads.size)
            assertEquals(cancellations, rig.engine.cancellations)
            assertEquals(preparations + 1, rig.gateway.preparations.size)
            assertEquals(acquisitions, rig.gateway.selections.acquisitions.size)
            assertEquals(1, resource.closeCalls)
        } finally {
            gate.release.complete(Unit)
            rig.beforeActivate = {}
            rig.afterActivate = { _, _ -> }
            rig.close()
        }
    }

    private class ApplyGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        suspend fun await() { entered.complete(Unit); release.await() }
    }

    private data class Stage(
        val outgoing: ExecutionOccurrenceId,
        val outgoingGeneration: Long,
        val musicQueueRevision: Long,
        val native: PlaybackTestEngine.Load,
        val ui: CrossfadeState,
        val cancellations: List<Pair<Long, Long>>,
        val stops: List<Long>,
        val loads: List<PlaybackTestEngine.Load>,
        val selectionCalls: Int,
    )

    /** One canonical owner and engine. The decorator only provides the actual pre-transaction gate. */
    private class Fixture(val testScope: TestScope) {
        val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        val persistence = AcquisitionProjectionTestPersistence()
        val store = LibraryStore(persistence, dispatcher)
        val engine = PlaybackTestEngine()
        val gateway = PlaybackAcquisitionTestGateway()
        private val clock = object : Clock {
            override fun epochMs(): Long = 123_000 + testScope.testScheduler.currentTime
            override fun monotonicMs(): Long = testScope.testScheduler.currentTime
        }
        private var sequence = 0
        private val captures = AcquisitionCaptureFactory(clock) { AcquisitionRequestId("admission-cas-${++sequence}") }
        private val admissions = AcquisitionAdmissionFactory(captures, clock)
        private val selection = AcquisitionCoordinator(store.acquisition, gateway, captures, clock,
            { store.state.value.settings.offlineOnly })
        private val queues = QueueAcquisitionCoordinator(store.acquisition, selection)
        private val noFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path =
                error("Admission actor fixtures must not perform filesystem I/O")
        }
        private val materializationEvidence = DownloadMaterializationEvidence(store.transactionOwner)
        private val resolver = AcquisitionPlaybackResolver(store, gateway,
            DownloadFiles("/__veyra_authored_admission_cas_no_file_io__", noFiles), LocalMediaValidator {
                error("Remote-only actor fixture must not invoke local validation")
            }, materializationEvidence)
        var beforeAdmit: suspend (AcquisitionPlaybackCommand.AdmitQueue) -> Unit = {}
        var afterAdmit: suspend (AcquisitionPlaybackCommand.AdmitQueue, PlaybackAcquisitionView) -> Unit = { _, _ -> }
        var beforeActivate: suspend (AcquisitionPlaybackCommand.Activate) -> Unit = {}
        var afterActivate: suspend (AcquisitionPlaybackCommand.Activate, PlaybackAcquisitionView) -> Unit = { _, _ -> }
        val attemptedAdmissions = mutableListOf<AcquisitionPlaybackCommand.AdmitQueue>()
        val acknowledgedAdmissions = mutableListOf<AcquisitionPlaybackCommand.AdmitQueue>()
        val attemptedActivations = mutableListOf<AcquisitionPlaybackCommand.Activate>()
        val acknowledgedActivations = mutableListOf<AcquisitionPlaybackCommand.Activate>()
        private val repository = object : AcquisitionLibraryRepository by store.acquisition {
            override suspend fun applyPlayback(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView {
                when (command) {
                    is AcquisitionPlaybackCommand.AdmitQueue -> {
                        attemptedAdmissions += command
                        beforeAdmit(command)
                    }
                    is AcquisitionPlaybackCommand.Activate -> {
                        attemptedActivations += command
                        beforeActivate(command)
                    }
                    else -> Unit
                }
                val result = store.acquisition.applyPlayback(command)
                when (command) {
                    is AcquisitionPlaybackCommand.AdmitQueue -> {
                        acknowledgedAdmissions += command
                        afterAdmit(command, result)
                    }
                    is AcquisitionPlaybackCommand.Activate -> {
                        acknowledgedActivations += command
                        afterActivate(command, result)
                    }
                    else -> Unit
                }
                return result
            }
        }
        private val services = PlaybackAcquisitionServices(repository, selection, queues, resolver, captures, admissions)
        private val legacy = PlaybackTestResolver(store).apply {
            behavior = { _, _ -> error("Typed admission fixture must not use legacy selection") }
        }
        val controller = PlaybackController(store, engine, legacy, testScope, clock, dispatcher, acquisition = services)
        val state: PlaybackState get() = controller.state.value
        val durable: PlaybackAcquisitionView get() = persistence.graph.playbackView()
        fun queue(context: QueueContext) = store.acquisition.state.value.queues.getValue(context)

        fun admission(
            context: QueueContext = QueueContext.MUSIC,
            expectedLibraryRevision: Long? = store.state.value.revision,
            operation: QueueAdmissionOperation = QueueAdmissionOperation.APPEND,
            activate: Boolean = false,
        ): PlaybackCommand.AdmitAcquisition {
            val metadata: RequestedMetadata = if (context == QueueContext.MUSIC)
                AlbumCapture(Album("authored-album", "Complete parent title — إصدار"))
            else ShowCapture(PodcastShow("authored-show", "Complete show title — برنامج", "https://example.invalid/feed"))
            val request = captures.capture(metadata, expectedLibraryRevision)
            val prepared = admissions.single(request, PlaybackControlsMode.valueOf(context.name), expectedLibraryRevision)
            return PlaybackCommand.AdmitAcquisition(prepared, context, queue(context).editRevision, operation, activate)
        }

        suspend fun admitInactive(context: QueueContext): ExecutionOccurrenceId {
            controller.initialize().getOrThrow()
            testScope.runCurrent()
            val command = admission(context)
            controller.dispatch(command).getOrThrow()
            testScope.runCurrent()
            return command.prepared.occurrences.single().id
        }

        suspend fun startStage(neighbourResource: PlaybackResource? = null): Stage {
            val outgoing = startQueue()
            if (neighbourResource != null) {
                val incoming = queue(QueueContext.MUSIC).displayedOrder.last()
                val requestId = requireNotNull(durable.acquisition.occurrences.getValue(incoming).requestId)
                var handed = false
                gateway.prepare = { context, required ->
                    if (!handed && context.request.id == requestId) {
                        handed = true
                        gateway.source(context, required, neighbourResource)
                    } else gateway.source(context, required)
                }
            }
            requestStage()
            val native = engine.preloads.single()
            val ui = assertNotNull(state.crossfade)
            assertEquals(CrossfadePhase.PREPARING, ui.phase)
            assertEquals(native.generation, ui.incomingGeneration)
            assertTrue(state.desiredPlaying && state.isPlaying)
            return Stage(outgoing, state.generation, queue(QueueContext.MUSIC).editRevision,
                native, ui, engine.cancellations.toList(), engine.stops.toList(), engine.loads.toList(),
                gateway.selections.acquisitions.size)
        }

        suspend fun startQueue(): ExecutionOccurrenceId {
            controller.initialize().getOrThrow(); testScope.runCurrent()
            val first = admission(operation = QueueAdmissionOperation.REPLACE, activate = true)
            controller.dispatch(first).getOrThrow(); testScope.runCurrent()
            controller.dispatch(admission()).getOrThrow(); testScope.runCurrent()
            return first.prepared.occurrences.single().id
        }

        suspend fun requestStage() {
            store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = true)) }.getOrThrow()
            event(EngineEvent.Position(state.generation, 85_000, 100_000))
            testScope.advanceTimeBy(250); testScope.runCurrent()
        }

        fun assertStage(stage: Stage, expectedUi: CrossfadeState = stage.ui) {
            assertSame(stage.native, engine.staged)
            assertEquals(expectedUi, state.crossfade)
            assertEquals(stage.outgoing.value, state.currentEntryId)
            assertEquals(stage.outgoingGeneration, state.generation)
            assertTrue(state.desiredPlaying && state.isPlaying)
            assertEquals(stage.cancellations, engine.cancellations)
            assertEquals(stage.stops, engine.stops)
            assertEquals(stage.loads, engine.loads)
            assertEquals(stage.selectionCalls, gateway.selections.acquisitions.size)
        }

        suspend fun event(event: EngineEvent) { engine.emit(event); testScope.runCurrent() }
        suspend fun close() { try { controller.close() } finally { store.close() } }
    }
}
