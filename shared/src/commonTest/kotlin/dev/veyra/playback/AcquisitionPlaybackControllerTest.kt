package dev.veyra.playback

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual actor/store/normalized commands; authored gateway+native doubles, no HTTP/files/audio. */
@OptIn(ExperimentalCoroutinesApi::class)
class AcquisitionPlaybackControllerTest {
    @Test fun foreignRoomServicesAreRejectedBeforeAttachingAnyControllerJobOrNativeOwner() = runTest {
        val foreign = AcquisitionPlaybackFixture(this)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val local = LibraryStore(AcquisitionProjectionTestPersistence(), dispatcher)
        val parent = SupervisorJob()
        val engine = PlaybackTestEngine()
        try {
            val children = parent.children.toList()
            assertFailsWith<IllegalArgumentException> {
                PlaybackController(local, engine, PlaybackTestResolver(local), CoroutineScope(parent), dispatcher = dispatcher,
                    acquisition = foreign.services)
            }
            assertEquals(children, parent.children.toList())
            assertTrue(engine.loads.isEmpty())
            assertTrue(engine.stops.isEmpty())
            assertEquals(0, engine.closeCalls, "A rejected constructor never acquired the caller's engine")
        } finally { parent.cancel(); foreign.close(); local.close() }
    }

    @Test fun schema3InitializationWithoutExplicitServicesFailsClosedWithoutFallbackSourceUse() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = LibraryStore(AcquisitionProjectionTestPersistence(), dispatcher)
        val engine = PlaybackTestEngine()
        val resolver = PlaybackTestResolver(store)
        val controller = PlaybackController(store, engine, resolver, this, dispatcher = dispatcher)
        try {
            val result = assertIs<PlaybackResult.Failure>(controller.initialize())
            assertEquals(FailureCode.INVALID_STATE, result.failure.code)
            assertFalse(controller.state.value.initialized)
            assertTrue(resolver.requests.isEmpty())
            assertTrue(engine.loads.isEmpty())
        } finally { controller.close(); store.close() }
    }

    @Test fun claimBindingAndEpochAreAcknowledgedInOrderBeforeActualTypedNativePrepare() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val observed = mutableListOf<String>()
            rig.gateway.selections.acquireResult = { request ->
                val committed = rig.durable.acquisition
                assertEquals(AcquisitionJobState.CLAIMED, committed.requests.getValue(request.id).state)
                assertEquals(OccurrenceSelectionState.PENDING, committed.occurrences.values.single().selectionState)
                assertTrue(rig.engine.loads.isEmpty())
                observed += "claim"; authoredSingle(request)
            }
            rig.gateway.prepare = { context, representation ->
                val committed = rig.durable
                val occurrence = committed.acquisition.occurrences.values.single()
                assertEquals(OccurrenceSelectionState.BOUND, occurrence.selectionState)
                assertEquals(context.binding.id, occurrence.bindingId)
                assertEquals(0L, committed.checkpoints.getValue(occurrence.id).sequence)
                assertTrue(committed.history.isEmpty())
                observed += "binding+epoch"; rig.gateway.source(context, representation)
            }
            rig.engine.beforePrepare = {
                assertEquals(rig.state.generation, rig.durable.checkpoints.values.single().generation)
                observed += "native-prepare"
            }
            val id = rig.neutral(title = "  Complete untrimmed parent A — العنوان الكامل  ")
            assertEquals(listOf("claim", "binding+epoch", "native-prepare"), observed)
            val native = assertNotNull(rig.engine.loads.single().acquired)
            assertEquals(id, native.selection.occurrenceId)
            assertEquals(rig.state.currentSelection?.occurrence, native.selection.occurrence)
            assertEquals(rig.state.currentSelection?.executionContext, native.selection.executionContext)
            assertEquals("  Complete untrimmed parent A — العنوان الكامل  ", native.source.context.request.completeTitle)
            assertNull(rig.state.currentItem)
            assertEquals(id.value, rig.state.currentEntryId)
            assertEquals(PlaybackControlsMode.MUSIC, rig.state.controlsMode)
            assertTrue(rig.legacy.requests.isEmpty())
            assertEquals(1, rig.gateway.selections.acquisitions.size)
        } finally { rig.close() }
    }

    @Test fun readyOrPreReadyPositionAndEndDoNotInventARealNativeCheckpoint() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize(); rig.engine.autoReady = false
            val id = rig.neutral(); val token = rig.state.generation
            rig.event(EngineEvent.Position(token, 91_000, 100_000))
            rig.event(EngineEvent.Ended(token))
            assertEquals(PlaybackPhase.PREPARING, rig.state.phase)
            assertEquals(0L, rig.state.positionMs)
            rig.event(EngineEvent.Ready(token, 100_000, true))
            rig.command(PlaybackCommand.FlushProgress)
            assertEquals(0L, rig.durable.checkpoints.getValue(id).sequence)
            assertTrue(rig.durable.history.isEmpty())
            assertTrue(rig.durable.neutralResume.isEmpty())
            rig.event(EngineEvent.Position(token, 4_321, 100_000))
            rig.command(PlaybackCommand.FlushProgress)
            assertEquals(4_321L, rig.durable.checkpoints.getValue(id).positionMs)
            assertTrue(rig.durable.checkpoints.getValue(id).sequence > 0)
            assertEquals(4_321L, rig.durable.history.single().lastPositionMs)
        } finally { rig.close() }
    }

    @Test fun restoredTypedQueueIsPausedAndPerformsNoLookupNativePrepareOrFileValidation() = runTest {
        val first = AcquisitionPlaybackFixture(this)
        first.initialize()
        val id = first.neutral(PlaybackControlsMode.PODCAST)
        first.event(EngineEvent.Position(first.state.generation, 12_345, 100_000))
        first.command(PlaybackCommand.FlushProgress)
        first.close()
        val reopened = AcquisitionPlaybackFixture(this, first.persistence.graph)
        try {
            reopened.initialize()
            assertEquals(id.value, reopened.state.currentEntryId)
            assertEquals(12_345L, reopened.state.positionMs)
            assertEquals(PlaybackPhase.PAUSED, reopened.state.phase)
            assertFalse(reopened.state.desiredPlaying)
            assertFalse(reopened.state.isPlaying)
            assertNull(reopened.state.currentItem)
            assertTrue(reopened.engine.loads.isEmpty())
            assertTrue(reopened.engine.stops.isEmpty())
            assertTrue(reopened.gateway.selections.acquisitions.isEmpty())
            assertTrue(reopened.gateway.preparations.isEmpty())
            assertTrue(reopened.legacy.requests.isEmpty())
            assertEquals(0, reopened.validations)
        } finally { reopened.close() }
    }

    @Test fun aStructurallyValidForeignReturnedContextIsDisposedOnceAndNeverPrepared() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        val rejected = CountingPlaybackResource()
        try {
            rig.initialize()
            val other = rig.captures.capture(AlbumCapture(Album("foreign", "Complete other parent")))
            rig.store.acquisition.capture(CaptureAcquisition(other))
            rig.selection.resolve(other.id)
            val outcome = rig.store.acquisition.state.value.requests.getValue(other.id).outcome as AcquisitionOutcome.Single
            val foreign = rig.store.acquisition.state.value.executionContext(outcome.bindingId)
            rig.gateway.prepare = { _, representation -> rig.gateway.source(foreign, representation, rejected) }
            rig.neutral()
            assertTrue(rig.engine.loads.isEmpty())
            assertEquals(1, rejected.closeCalls)
            assertNotEquals(foreign.request.id, rig.state.currentSelection?.request?.id)
            assertNotNull(rig.state.failure)
            rig.close()
            assertEquals(1, rejected.closeCalls)
        } finally { if (!rig.engine.closed) rig.close() }
    }

    @Test fun staleRevisionRejectsEveryTypedQueueEditWithoutCommitOrNativeSideEffects() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize(); val first = rig.neutral()
            rig.neutral(operation = QueueAdmissionOperation.APPEND, activate = false)
            val stale = rig.queue.editRevision - 1
            val commands = listOf(
                PlaybackCommand.Remove(QueueContext.MUSIC, first.value, stale),
                PlaybackCommand.Move(QueueContext.MUSIC, first.value, 1, stale),
                PlaybackCommand.Clear(QueueContext.MUSIC, stale),
                PlaybackCommand.ClearUpcoming(QueueContext.MUSIC, stale),
                PlaybackCommand.UndoQueueEdit(QueueContext.MUSIC, stale),
                PlaybackCommand.SetShuffle(true, QueueContext.MUSIC, stale),
                PlaybackCommand.SetRepeat(RepeatMode.ALL, QueueContext.MUSIC, stale),
            )
            val before = rig.persistence.graph
            val stops = rig.engine.stops.toList(); val loads = rig.engine.loads.toList()
            commands.forEach { command ->
                val failure = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(command))
                runCurrent()
                assertEquals(FailureCode.INVALID_STATE, failure.failure.code)
                assertSame(before, rig.persistence.graph)
                assertEquals(stops, rig.engine.stops)
                assertEquals(loads, rig.engine.loads)
            }
        } finally { rig.close() }
    }

    @Test fun unknownSelectionIsNotAutomaticallyResearchedByResumeTimerOrLaterControl() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            rig.gateway.selections.acquireResult = { error("authored ambiguous source response") }
            rig.neutral()
            assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN, rig.durable.acquisition.requests.values.single().state)
            repeat(3) { rig.command(PlaybackCommand.Resume); rig.tick() }
            advanceTimeBy(10_000); runCurrent()
            assertEquals(1, rig.gateway.selections.acquisitions.size)
            assertTrue(rig.gateway.preparations.isEmpty())
            assertTrue(rig.engine.loads.isEmpty())
            assertNotNull(rig.state.failure)
        } finally { rig.close() }
    }

    @Test fun lateSourceReturnedAfterOfflinePolicyChangeIsDisposedWithoutNativeAdmission() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        val entered = CompletableDeferred<Unit>(); val respond = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        try {
            rig.initialize()
            rig.gateway.prepare = { context, required -> withContext(NonCancellable) {
                entered.complete(Unit); respond.await(); rig.gateway.source(context, required, resource)
            } }
            rig.neutral(); entered.await()
            rig.store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
            runCurrent(); respond.complete(Unit); runCurrent()
            assertTrue(rig.engine.loads.isEmpty())
            assertEquals(1, resource.closeCalls)
            assertTrue(rig.legacy.requests.isEmpty())
        } finally { respond.complete(Unit); rig.close() }
    }

    @Test fun finalNativeGuardRejectsAChangedQueueRevisionAfterSourcePreparation() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        val stopped = CompletableDeferred<Unit>(); val releaseStop = CompletableDeferred<Unit>()
        val resource = CountingPlaybackResource()
        try {
            rig.initialize()
            rig.engine.beforeStop = { stopped.complete(Unit); releaseStop.await() }
            rig.gateway.prepare = { context, required -> rig.gateway.source(context, required, resource) }
            rig.neutral(); stopped.await()
            assertEquals(1, rig.gateway.preparations.size)
            assertTrue(rig.engine.loads.isEmpty())
            val queue = rig.queue
            rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.EditQueue(
                ExecutionQueueExpectation(queue.context, queue.editRevision, queue.currentOccurrenceId), queue.displayedOrder,
                queue.originalOrder, queue.currentOccurrenceId, queue.positionMs, queue.speed, queue.shuffle, RepeatMode.ALL, null))
            releaseStop.complete(Unit); runCurrent()
            assertTrue(rig.engine.loads.isEmpty())
            assertEquals(1, resource.closeCalls)
            assertNotNull(rig.state.failure)
        } finally { releaseStop.complete(Unit); rig.close() }
    }

    @Test fun finalNativeGuardRejectsANewerDurableEpochEvenWhenQueueBindingAndControllerGenerationAreUnchanged() = runTest {
        for (replaceSession in listOf(false, true)) {
            val rig = AcquisitionPlaybackFixture(this)
            val stopped = CompletableDeferred<Unit>(); val releaseStop = CompletableDeferred<Unit>()
            val resource = CountingPlaybackResource()
            try {
                rig.initialize()
                rig.engine.beforeStop = { stopped.complete(Unit); releaseStop.await() }
                rig.gateway.prepare = { context, required -> rig.gateway.source(context, required, resource) }
                val id = rig.neutral(); stopped.await()
                val queue = rig.queue
                val admitted = rig.durable.checkpoints.getValue(id)
                val controllerGeneration = rig.state.generation
                val occurrence = rig.durable.acquisition.occurrences.getValue(id)
                assertEquals(1, rig.gateway.preparations.size)
                assertTrue(rig.engine.loads.isEmpty(), "Prepare is queued behind the blocked native stop")
                rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.AdmitLoad(
                    ExecutionQueueExpectation(queue.context, queue.editRevision, queue.currentOccurrenceId),
                    ExecutionLoadAdmission(admitted.bindingId, admitted,
                        if (replaceSession) "other-native-session" else admitted.sessionId,
                        if (replaceSession) admitted.generation else admitted.generation + 1,
                        admitted.positionMs, admitted.measuredDurationMs, admitted.observedEpochMs + 1)))
                runCurrent()
                assertEquals(queue, rig.queue)
                assertEquals(occurrence, rig.durable.acquisition.occurrences.getValue(id))
                assertEquals(controllerGeneration, rig.state.generation)
                val newer = rig.durable.checkpoints.getValue(id)
                releaseStop.complete(Unit); runCurrent()
                assertTrue(rig.engine.loads.isEmpty())
                assertEquals(1, resource.closeCalls)
                assertNotNull(rig.state.failure)
                assertEquals(newer, rig.durable.checkpoints.getValue(id), "Rejected old prepare cannot write over the newer epoch")
                assertTrue(rig.durable.history.isEmpty())
            } finally { releaseStop.complete(Unit); rig.close() }
            assertEquals(1, resource.closeCalls)
        }
    }

    @Test fun incomingSourceCannotPreloadAfterEitherItsOwnOrTheOutgoingDurableEpochWasReplaced() = runTest {
        for (replaceOutgoing in listOf(false, true)) {
            val rig = AcquisitionPlaybackFixture(this)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val resource = CountingPlaybackResource()
            try {
                rig.initialize(); val first = rig.neutral()
                val incoming = rig.neutral(operation = QueueAdmissionOperation.APPEND, activate = false)
                rig.gateway.prepare = { context, required ->
                    entered.complete(Unit); release.await(); rig.gateway.source(context, required, resource)
                }
                rig.store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = true)) }.getOrThrow()
                val generation = rig.state.generation
                rig.event(EngineEvent.Position(generation, 85_000, 100_000)); rig.tick(); entered.await()
                val queue = rig.queue
                val expected = ExecutionQueueExpectation(queue.context, queue.editRevision, queue.currentOccurrenceId)
                val outgoing = rig.durable.checkpoints.getValue(first)
                val admitted = rig.durable.checkpoints.getValue(incoming)
                if (replaceOutgoing) {
                    rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.AdmitLoad(expected,
                        ExecutionLoadAdmission(outgoing.bindingId, outgoing, "other-outgoing-session", outgoing.generation,
                            outgoing.positionMs, outgoing.measuredDurationMs, outgoing.observedEpochMs + 1)))
                    assertEquals(admitted, rig.durable.checkpoints.getValue(incoming), "Incoming epoch alone is insufficient")
                } else {
                    rig.store.acquisition.applyPlayback(AcquisitionPlaybackCommand.AdmitStage(expected,
                        outgoing.bindingId, outgoing.sessionId, outgoing.generation, incoming,
                        ExecutionLoadAdmission(admitted.bindingId, admitted, admitted.sessionId, admitted.generation + 1,
                            admitted.positionMs, admitted.measuredDurationMs, admitted.observedEpochMs + 1)))
                }
                release.complete(Unit); runCurrent()
                assertTrue(rig.engine.preloads.isEmpty())
                assertEquals(1, resource.closeCalls)
                assertEquals(first.value, rig.state.currentEntryId)
                assertEquals(generation, rig.state.generation)
                assertTrue(rig.state.desiredPlaying, "A rejected incoming stage must not stop unrelated current playback")
                assertNull(rig.state.crossfade)
                assertNotNull(rig.state.failure)
            } finally { release.complete(Unit); rig.close() }
        }
    }

    @Test fun neutralPodcastEndTimerUsesTheExactDisplayedOccurrenceAndNeverCrossfadesOrMarksRssPlayed() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize()
            val first = rig.neutral(PlaybackControlsMode.PODCAST)
            val token = rig.state.generation
            val next = rig.neutral(PlaybackControlsMode.PODCAST, operation = QueueAdmissionOperation.APPEND, activate = false)
            rig.store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = true)) }.getOrThrow()
            rig.command(PlaybackCommand.SleepAtRecordingEnd(first.value, token))
            rig.event(EngineEvent.Position(token, 99_000, 100_000)); rig.tick()
            assertTrue(rig.engine.preloads.isEmpty())
            rig.event(EngineEvent.Ended(token))
            assertEquals(first.value, rig.state.currentEntryId)
            assertFalse(rig.state.desiredPlaying)
            assertNull(rig.state.sleepTimer)
            assertTrue(rig.durable.catalogProgress.isEmpty())
            assertTrue(rig.durable.snapshot.shows.isEmpty())
            assertTrue(rig.durable.history.single().endedNaturally)
            rig.command(PlaybackCommand.PlayOccurrence(QueueContext.PODCAST, rig.queue.editRevision, next))
            rig.command(PlaybackCommand.SleepDuration(10_000))
            val timer = rig.state.sleepTimer
            val stale = assertIs<PlaybackResult.Failure>(rig.controller.dispatch(PlaybackCommand.SleepAtRecordingEnd(first.value, token)))
            assertEquals(FailureCode.INVALID_STATE, stale.failure.code)
            assertEquals(timer, rig.state.sleepTimer)
            assertEquals(next.value, rig.state.currentEntryId)
        } finally { rig.close() }
    }

    @Test fun incomingStageReservationDoesNotEraseTheDirtyOutgoingRealSample() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize(); val first = rig.neutral()
            val incoming = rig.neutral(operation = QueueAdmissionOperation.APPEND, activate = false)
            rig.store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = true)) }.getOrThrow()
            rig.event(EngineEvent.Position(rig.state.generation, 85_000, 100_000)); rig.tick()
            assertEquals(incoming, assertNotNull(rig.engine.preloads.single().acquired).selection.occurrenceId)
            assertEquals(0L, rig.durable.checkpoints.getValue(incoming).sequence)
            assertEquals(0L, rig.durable.checkpoints.getValue(first).sequence)
            advanceTimeBy(5_250); runCurrent()
            assertEquals(85_000L, rig.durable.checkpoints.getValue(first).positionMs)
            assertTrue(rig.durable.checkpoints.getValue(first).sequence > 0)
            assertEquals(first.value, rig.state.currentEntryId)
            assertEquals(85_000L, rig.durable.history.single().lastPositionMs)
        } finally { rig.close() }
    }

    @Test fun crossfadeNativeConfirmationSeesAlreadyCommittedIncomingCursorAndBothRealHistories() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize(); val first = rig.neutral()
            val incoming = rig.neutral(operation = QueueAdmissionOperation.APPEND, activate = false)
            rig.store.update { it.copy(settings = it.settings.copy(crossfadeEnabled = true, crossfadeDurationMs = 4_000)) }.getOrThrow()
            val outgoingGeneration = rig.state.generation
            rig.event(EngineEvent.Position(outgoingGeneration, 85_000, 100_000)); rig.tick()
            val stage = rig.engine.preloads.single()
            rig.event(EngineEvent.StageReady(outgoingGeneration, stage.generation, 100_000, true))
            rig.event(EngineEvent.Position(outgoingGeneration, 97_000, 100_000)); rig.tick()
            assertEquals(1, rig.engine.fades.size)
            rig.engine.beforeConfirm = {
                assertEquals(incoming, rig.durable.acquisition.queues.getValue(QueueContext.MUSIC).currentOccurrenceId)
                assertEquals(97_000L, rig.durable.checkpoints.getValue(first).positionMs)
                assertEquals(3_000L, rig.durable.checkpoints.getValue(incoming).positionMs)
                assertEquals(setOf(first, incoming), rig.durable.history.mapNotNull { it.occurrenceId }.toSet())
            }
            rig.event(EngineEvent.CrossfadeCompleted(outgoingGeneration, stage.generation, 3_000, 100_000,
                seekable = true, effectiveSpeed = 1f, isPlaying = true))
            assertEquals(listOf(outgoingGeneration to stage.generation), rig.engine.confirmations)
            assertEquals(incoming.value, rig.state.currentEntryId)
            assertEquals(stage.generation, rig.state.generation)
            assertNull(rig.state.currentItem)
            assertNull(rig.state.crossfade)
            assertEquals(2, rig.gateway.selections.acquisitions.size)
        } finally { rig.close() }
    }

    @Test fun successfulCloseGrantsPositiveDependencyReceiptOnlyAfterAllOwnersReturn() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        val entered = CompletableDeferred<Unit>(); val drain = CompletableDeferred<Unit>()
        try {
            rig.initialize(); rig.neutral()
            rig.engine.beforeClose = { entered.complete(Unit); drain.await() }
            val closing = async { rig.controller.close() }
            entered.await()
            assertFalse(rig.controller.dependencyReleaseSafeAfterClose)
            assertFalse(closing.isCompleted)
            drain.complete(Unit); closing.await()
            assertTrue(rig.controller.dependencyReleaseSafeAfterClose)
            rig.controller.close()
            assertEquals(1, rig.engine.closeCalls)
        } finally { drain.complete(Unit); rig.close() }
    }

    @Test fun failedNativeCloseWithholdsDependencyReceiptAndNeverAutomaticallyRetriesNativeDestruction() = runTest {
        val rig = AcquisitionPlaybackFixture(this)
        try {
            rig.initialize(); rig.neutral()
            rig.engine.beforeClose = { error("authored native release failure") }
            val failure = assertFailsWith<VeyraException> { rig.controller.close() }
            assertFalse(rig.controller.dependencyReleaseSafeAfterClose)
            assertSame(failure, assertFailsWith<VeyraException> { rig.controller.close() })
            assertEquals(1, rig.engine.closeCalls)
        } finally { rig.store.close() /* Pure in-memory test persistence owns no native dependency. */ }
    }
}
