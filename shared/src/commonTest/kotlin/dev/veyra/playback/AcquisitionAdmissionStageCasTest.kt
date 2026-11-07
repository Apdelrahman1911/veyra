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
        val attemptedAdmissions = mutableListOf<AcquisitionPlaybackCommand.AdmitQueue>()
        val acknowledgedAdmissions = mutableListOf<AcquisitionPlaybackCommand.AdmitQueue>()
        private val repository = object : AcquisitionLibraryRepository by store.acquisition {
            override suspend fun applyPlayback(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView {
                if (command is AcquisitionPlaybackCommand.AdmitQueue) {
                    attemptedAdmissions += command
                    beforeAdmit(command)
                }
                val result = store.acquisition.applyPlayback(command)
                if (command is AcquisitionPlaybackCommand.AdmitQueue) {
                    acknowledgedAdmissions += command
                    afterAdmit(command, result)
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

        suspend fun startStage(): Stage {
            val outgoing = startQueue()
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
