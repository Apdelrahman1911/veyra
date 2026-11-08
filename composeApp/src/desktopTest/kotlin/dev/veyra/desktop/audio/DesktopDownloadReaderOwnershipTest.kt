package dev.veyra.desktop.audio

import dev.veyra.model.FailureCode
import dev.veyra.model.MediaKind
import dev.veyra.model.VeyraException
import dev.veyra.playback.EngineEvent
import dev.veyra.playback.NativeReaderDrainResult
import dev.veyra.playback.PlaybackSelectionDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Real DesktopAudioEngine actor + authored DesktopAudioDeck/PlaybackResource doubles. The
 * doubles prove ownership/ordering answers only; they do not decode, fetch, open libmpv, release
 * a real Windows/Linux file descriptor, or establish native/platform verification.
 */
class DesktopDownloadReaderOwnershipTest {
    @Test fun pristineEngineEstablishesOnlyEpochZeroAndRejectsArbitraryWrongEpochs() =
        ownershipTest(AuthoredDesktopDeck("initial")) {
            val first = firstDeck
            assertEquals(NativeReaderDrainResult.Drained(0L), engine.awaitRetiredReaders(0L))
            assertEquals(listOf(0L, 0L), first.readerDrainCalls.toList())
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(7L))
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(-1L))
            assertEquals(listOf(0L, 0L), first.readerDrainCalls.toList())
        }

    @Test fun registeredButFailedInvocationMakesAnEmptyLookingEngineNonPristine() =
        ownershipTest(AuthoredDesktopDeck("initial")) {
            val first = firstDeck
            val fixture = AuthoredAcquisition.catalogue()
            val resource = resource("rejected-owner")
            val mismatch = fixture.selection().copy(executed = PlaybackSelectionDisplay("Fabricated execution"))
            val error = assertFailsWith<VeyraException> {
                engine.prepare(fixture.source(resource = resource), mismatch, 0, 2)
            }
            assertEquals(FailureCode.INVALID_STATE, error.failure.code)
            assertEquals("native_playback_selection_mismatch", error.failure.detail)
            assertEquals(1, resource.closeCalls.get())
            assertTrue(first.prepareGenerations.isEmpty())
            // The rejected invocation still registered source ownership, so this is not pristine.
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(0L))
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(5L))
            assertTrue(first.readerDrainCalls.isEmpty())
        }

    @Test fun exactStaleEpochMismatchDoesNotMutateTheLiveGeneration() =
        ownershipTest(AuthoredDesktopDeck("initial")) {
            val first = firstDeck
            val resource = resource("live-owner")
            engine.prepare(legacy("A", resource), 0, 1)
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(0L))
            assertIs<NativeReaderDrainResult.Superseded>(engine.awaitRetiredReaders(99L))
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())
            assertEquals(0, resource.closeCalls.get())
            assertEquals(NativeReaderDrainResult.Drained(1L), engine.awaitRetiredReaders(1L))
            engine.play(1)
            assertEquals(listOf(1L, 1L), first.playCalls.toList())
            assertEquals(0, resource.closeCalls.get())
        }

    @Test fun acquiredCatalogueAndNeutralSelectionsUseTheRealProjectorAndOnlyTransport() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val catalogue = AuthoredAcquisition.catalogue()
            val catalogueResource = resource("catalogue-owner")
            val catalogueNative = catalogue.native(catalogueResource)
            assertEquals(catalogue.selection().executed, catalogueNative.display)
            assertEquals(true, catalogueNative.catalogMedia != null)
            engine.prepare(catalogue.source(resource = catalogueResource), catalogue.selection(), 0, 1)
            assertEquals(1, first.prepareInputs.size)
            assertEquals("https://media.example/authored-owned-source", first.prepareInputs.single().uri)
            assertEquals(false, first.prepareInputs.single().isLocal)
            assertEquals(musicMasterGain(), first.gainCalls.single())
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())
            assertEquals(0, catalogueResource.closeCalls.get())

            val neutral = AuthoredAcquisition.neutralShow()
            val neutralNative = neutral.native()
            assertEquals(null, neutralNative.catalogMedia, "A neutral source recording must not become catalogue media")
            assertEquals(dev.veyra.model.PlaybackControlsMode.PODCAST, neutralNative.controlsMode)
            val neutralResource = resource("neutral-owner")
            val replacement = deck("B")
            enqueueReplacement(replacement)
            engine.prepare(neutral.source(resource = neutralResource), neutral.selection(), 0, 2)
            assertEquals(1, replacement.prepareInputs.size)
            assertEquals(1f, replacement.gainCalls.single(), "Podcast controls keep unity master gain")
            assertEquals(listOf(1L), first.prepareGenerations.toList())
            assertTrue(first.destroyed)
            assertEquals(1, first.closeCalls.get())
            assertEquals(1, catalogueResource.closeCalls.get())
            assertEquals(0, neutralResource.closeCalls.get())
            engine.play(2)
            assertEquals(listOf(2L), replacement.playCalls.toList())
        }

    @Test fun mismatchedAcquiredSelectionIsOwnedThenClosedOnceWithoutTouchingLiveB() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val liveResource = resource("live")
            val fixture = AuthoredAcquisition.catalogue()
            val rejected = resource("rejected")
            engine.prepare(legacy("A", liveResource), 0, 1)
            val pauseCountBeforeRejected = first.pauseCalls.size
            val mismatch = fixture.selection().copy(executed = PlaybackSelectionDisplay("Fabricated C"))
            val error = assertFailsWith<VeyraException> {
                engine.prepare(fixture.source(resource = rejected), mismatch, 0, 2)
            }
            assertEquals(FailureCode.INVALID_STATE, error.failure.code)
            assertEquals("native_playback_selection_mismatch", error.failure.detail)
            assertEquals("INVALID_STATE", error.message)
            assertFalse(error.message.orEmpty().contains(fixture.context.request.completeTitle))
            assertEquals(1, rejected.closeCalls.get())
            assertTrue(rejected.closed)
            assertEquals(0, liveResource.closeCalls.get())
            assertEquals(listOf(1L), first.prepareGenerations.toList())
            assertEquals(pauseCountBeforeRejected, first.pauseCalls.size,
                "Selection validation must not touch the live deck again")
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())
        }

    @Test fun failedRejectedResourceClosePinsTheExactOwnerAndVetoesTheReaderFence() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val liveResource = resource("live")
            val fixture = AuthoredAcquisition.catalogue()
            val rejected = resource("rejected-failed", failClose = true)
            engine.prepare(legacy("A", liveResource), 0, 1)
            val pauseCountBeforeRejected = first.pauseCalls.size
            val mismatch = fixture.selection().copy(executed = PlaybackSelectionDisplay("Fabricated C"))
            assertFailsWith<VeyraException> {
                engine.prepare(fixture.source(resource = rejected), mismatch, 0, 2)
            }
            assertEquals(1, rejected.closeCalls.get())
            assertEquals(1, rejected.failedCloseCalls.get())
            val drain = assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(1L))
            assertEquals(FailureCode.RECOVERY_REQUIRED, drain.failure.code)
            assertEquals("playback_resource_cleanup", drain.failure.detail)
            assertEquals(1, rejected.closeCalls.get(), "A failed close is never retried")
            assertEquals(0, liveResource.closeCalls.get())
            assertEquals(pauseCountBeforeRejected, first.pauseCalls.size)
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())
            val again = assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(1L))
            assertEquals(drain, again)
            assertEquals(1, rejected.closeCalls.get())
        }

    @Test fun replacementDestroysTheOldNativeHandleBeforeClosingItsResource() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val old = resource("old")
            val gate = CompletableDeferred<Unit>()
            first.closeGate = gate
            old.nativeDestroyObservation = { first.destroyed }
            val replacement = deck("B")
            enqueueReplacement(replacement)
            engine.prepare(legacy("A", old), 0, 1)

            val replacementPrepare = async(start = CoroutineStart.UNDISPATCHED) {
                engine.prepare(legacy("B"), 0, 2)
            }
            first.closeEntered.await()
            assertEquals(1, replacement.prepareGenerations.size)
            assertEquals(1, first.closeCalls.get())
            assertFalse(first.destroyed)
            assertEquals(0, old.closeCalls.get())
            assertEquals(1 to 0, engine.retainedReadersForTest())
            val drain = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitRetiredReaders(2L) }
            assertFalse(drain.isCompleted, "The actor cannot certify release while native destruction is held")
            gate.complete(Unit)
            replacementPrepare.await()
            assertEquals(NativeReaderDrainResult.Drained(2L), drain.await())
            assertTrue(first.destroyed)
            assertEquals(1, old.closeCalls.get())
            assertEquals(listOf(true), old.closeSawNativeDestroy.toList())
            assertEquals(0, engine.retainedReadersForTest().first, "Successful destroy+drain removes the retired slot")
        }

    @Test fun stopCommandAcceptanceAloneDoesNotReleaseARetiredReader() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val old = resource("old")
            val gate = CompletableDeferred<Unit>()
            first.closeGate = gate
            old.nativeDestroyObservation = { first.destroyed }
            enqueueReplacement(deck("idle"))
            engine.prepare(legacy("A", old), 0, 1)

            val stop = async(start = CoroutineStart.UNDISPATCHED) { engine.stop(2L) }
            first.closeEntered.await()
            assertEquals(listOf(2L), first.stopCalls.toList(), "The stop command was accepted")
            assertFalse(first.destroyed)
            assertEquals(0, old.closeCalls.get())
            val lowerFence = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitRetiredReaders(1L) }
            assertFalse(lowerFence.isCompleted)
            gate.complete(Unit)
            stop.await()
            assertTrue(first.destroyed)
            assertEquals(1, old.closeCalls.get())
            assertEquals(listOf(true), old.closeSawNativeDestroy.toList())
            assertEquals(NativeReaderDrainResult.Superseded, lowerFence.await(),
                "A queued lower-epoch drain must be superseded, not return a stale success")
        }

    @Test fun failedRetiredNativeCloseKeepsHandleAndResourceWithoutSilencingUnrelatedB() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val failed = resource("retired-failed")
            first.failClose = true
            val replacement = deck("B")
            enqueueReplacement(replacement)
            val events = EngineEventLog(CoroutineScope(currentCoroutineContext()), engine)
            try {
                engine.prepare(legacy("A", failed), 0, 1)
                engine.prepare(legacy("B"), 0, 2)
                assertEquals(1, first.closeCalls.get())
                assertFalse(first.destroyed)
                assertEquals(0, failed.closeCalls.get())
                assertEquals(1 to 1, engine.retainedReadersForTest())

                engine.play(2)
                assertEquals(listOf(2L), replacement.playCalls.toList())
                val drain = assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(2L))
                assertEquals(FailureCode.RECOVERY_REQUIRED, drain.failure.code)
                assertEquals("playback_resource_cleanup", drain.failure.detail)
                assertEquals(1, first.closeCalls.get(), "A failed native destruction is never retried")
                assertEquals(0, failed.closeCalls.get(), "A failed native handle is never partially released")
                engine.play(2)
                assertEquals(listOf(2L, 2L), replacement.playCalls.toList())
                assertTrue(events.all.none { it is EngineEvent.Error && it.generation == 2L },
                    "The scoped reader fence must not invent an Error for unrelated live B")
                assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(2L))
                assertEquals(1, first.closeCalls.get())
                assertEquals(0, failed.closeCalls.get())
            } finally {
                events.cancel()
            }
            val closeError = assertFailsWith<VeyraException> { engine.close() }
            assertEquals(FailureCode.RECOVERY_REQUIRED, closeError.failure.code)
            assertEquals(1, first.closeCalls.get())
            assertEquals(0, failed.closeCalls.get())
        }

    @Test fun successfulDestroyThenResourceFailureIsStickyAndNeverReclosesTheHandle() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val failed = resource("resource-failed", failClose = true)
            failed.nativeDestroyObservation = { first.destroyed }
            val replacement = deck("B")
            enqueueReplacement(replacement)
            engine.prepare(legacy("A", failed), 0, 1)
            engine.prepare(legacy("B"), 0, 2)

            assertTrue(first.destroyed)
            assertEquals(1, first.closeCalls.get())
            assertEquals(1, failed.closeCalls.get())
            assertEquals(1, failed.failedCloseCalls.get())
            assertEquals(listOf(true), failed.closeSawNativeDestroy.toList())
            assertEquals(1 to 1, engine.retainedReadersForTest())
            val firstDrain = assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(2L))
            assertEquals("playback_resource_cleanup", firstDrain.failure.detail)
            val secondDrain = assertIs<NativeReaderDrainResult.Unacknowledged>(engine.awaitRetiredReaders(2L))
            assertEquals(firstDrain, secondDrain)
            assertEquals(1, first.closeCalls.get(), "Re-observation must not re-close a consumed native handle")
            assertEquals(1, failed.closeCalls.get(), "A failed resource close is never retried")

            // The same failed resource presented again is an alias, not a fresh close attempt or retry.
            val aliasError = assertFailsWith<VeyraException> {
                engine.prepare(legacy("failed-alias", failed), 0, 3)
            }
            assertEquals("native_playback_resource_already_owned", aliasError.failure.detail)
            assertEquals(1, failed.closeCalls.get())
            assertEquals(listOf(2L), replacement.prepareGenerations.toList())
            engine.play(2)
            assertEquals(listOf(2L), replacement.playCalls.toList())

            val closeError = assertFailsWith<VeyraException> { engine.close() }
            assertEquals(FailureCode.RECOVERY_REQUIRED, closeError.failure.code)
            assertEquals(1, first.closeCalls.get())
            assertEquals(1, failed.closeCalls.get())
        }

    @Test fun bothPauseAndDestroyFailureUsesTruthfulTransportContainment() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val staged = deck("staged", failPause = true, failClose = true)
            enqueueReplacement(staged)
            val stagedResource = resource("staged-resource")
            engine.prepare(legacy("A"), 0, 1)
            engine.preloadNext(legacy("staged", stagedResource), 0, 2, 1)
            assertEquals(1, staged.prepareGenerations.size)

            val containment = assertFailsWith<VeyraException> { engine.cancelNext(2, 1) }
            assertEquals(FailureCode.INVALID_STATE, containment.failure.code)
            assertEquals("native_audio_silence_unacknowledged", containment.failure.detail)
            assertEquals(1, staged.closeCalls.get())
            assertFalse(staged.destroyed)
            assertEquals(0, stagedResource.closeCalls.get())
            assertTrue(staged.pauseCalls.isNotEmpty())
            assertTrue(first.pauseCalls.isNotEmpty(), "Unknown silence must contain the other deck too")

            val play = assertFailsWith<VeyraException> { engine.play(1) }
            assertEquals("native_audio_silence_unacknowledged", play.failure.detail)
            val closeError = assertFailsWith<VeyraException> { engine.close() }
            assertEquals(FailureCode.RECOVERY_REQUIRED, closeError.failure.code)
            assertEquals(1, staged.closeCalls.get())
            assertEquals(0, stagedResource.closeCalls.get())
        }

    @Test fun activeResourceAliasesNeverCloseTheLeaderAndDistinctOwnersStillDrain() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val leader = resource("leader")
            engine.prepare(legacy("leader", leader), 0, 1)
            val aliasError = assertFailsWith<VeyraException> {
                engine.prepare(legacy("alias", leader), 0, 2)
            }
            assertEquals("native_playback_resource_already_owned", aliasError.failure.detail)
            assertEquals(0, leader.closeCalls.get())
            assertFalse(first.destroyed)
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())

            val replacement = deck("B")
            enqueueReplacement(replacement)
            val distinct = resource("distinct")
            engine.prepare(legacy("B", distinct), 0, 3)
            assertTrue(first.destroyed)
            assertEquals(1, leader.closeCalls.get())
            assertEquals(0, distinct.closeCalls.get())
            assertEquals(listOf(3L), replacement.prepareGenerations.toList())
            engine.close()
            assertEquals(1, leader.closeCalls.get())
            assertEquals(1, distinct.closeCalls.get())
            assertTrue(replacement.destroyed)
        }

    @Test fun concurrentSameResourceHandoffsHaveOneLeaderAndOneRejectedAlias() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val resource = resource("concurrent")
            val leader = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.prepare(legacy("leader", resource), 0, 1) }
            }
            val alias = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { engine.prepare(legacy("alias", resource), 0, 2) }
            }
            assertTrue(leader.await().isSuccess)
            val aliasError = assertIs<VeyraException>(alias.await().exceptionOrNull())
            assertEquals("native_playback_resource_already_owned", aliasError.failure.detail)
            assertEquals(listOf(1L), first.prepareGenerations.toList())
            assertEquals(0, resource.closeCalls.get())
            assertFalse(first.destroyed)
        }

    @Test fun cancelNextRetiresTheStagedDeckBeforeItsResource() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val staged = deck("staged")
            val gate = CompletableDeferred<Unit>()
            staged.closeGate = gate
            enqueueReplacement(staged)
            val stagedResource = resource("staged-resource")
            stagedResource.nativeDestroyObservation = { staged.destroyed }
            engine.prepare(legacy("A"), 0, 1)
            engine.preloadNext(legacy("staged", stagedResource), 0, 2, 1)
            assertEquals(1, staged.prepareGenerations.size)

            val cancel = async(start = CoroutineStart.UNDISPATCHED) { engine.cancelNext(2, 1) }
            staged.closeEntered.await()
            assertFalse(staged.destroyed)
            assertEquals(0, stagedResource.closeCalls.get())
            val fence = async(start = CoroutineStart.UNDISPATCHED) { engine.awaitRetiredReaders(1L) }
            assertFalse(fence.isCompleted)
            gate.complete(Unit)
            cancel.await()
            assertTrue(staged.destroyed)
            assertEquals(1, stagedResource.closeCalls.get())
            assertEquals(listOf(true), stagedResource.closeSawNativeDestroy.toList())
            assertEquals(NativeReaderDrainResult.Drained(1L), fence.await())
            assertEquals(0, first.closeCalls.get())
        }

    @Test fun failedPreloadRetiresItsStagedNativeDeckBeforeClosingTheResource() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val staged = deck("staged-failed-prepare", failPrepare = true)
            val gate = CompletableDeferred<Unit>()
            staged.closeGate = gate
            enqueueReplacement(staged)
            val stagedResource = resource("staged-failed-resource")
            stagedResource.nativeDestroyObservation = { staged.destroyed }
            engine.prepare(legacy("A"), 0, 1)
            val events = EngineEventLog(CoroutineScope(currentCoroutineContext()), engine)
            try {
                val preload = async(start = CoroutineStart.UNDISPATCHED) {
                    engine.preloadNext(legacy("staged-failed", stagedResource), 0, 2, 1)
                }
                staged.closeEntered.await()
                assertFalse(staged.destroyed)
                assertEquals(0, stagedResource.closeCalls.get())
                gate.complete(Unit)
                preload.await()
                assertTrue(staged.destroyed)
                assertEquals(1, stagedResource.closeCalls.get())
                assertEquals(listOf(true), stagedResource.closeSawNativeDestroy.toList())
                val failed = assertIs<EngineEvent.StageFailed>(
                    events.next { it is EngineEvent.StageFailed && it.nextGeneration == 2L },
                )
                assertEquals("crossfade_preload_failed", failed.failure.detail)
                assertEquals("1", engine.diagnostics()["managed-decks"])
            } finally {
                events.cancel()
            }
        }

    @Test fun callerCancellationCannotBecomeDrained() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val gate = CompletableDeferred<Unit>()
            first.readerDrainGate = gate
            engine.prepare(legacy("A"), 0, 1)
            val outcome = CompletableDeferred<Throwable?>()
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                outcome.complete(runCatching { engine.awaitRetiredReaders(1L) }.exceptionOrNull())
            }
            first.readerDrainEntered.await()
            caller.cancel()
            val cancellation = outcome.await()
            assertIs<CancellationException>(cancellation, "A cancelled caller must never be converted to Drained")
            gate.complete(Unit)
            engine.play(1)
            assertEquals(listOf(1L), first.playCalls.toList())
            assertEquals(NativeReaderDrainResult.Drained(1L), engine.awaitRetiredReaders(1L))
        }

    @Test fun closeAdmissionRacingAPendingLowerDeckFenceIsNotAStaleSuccess() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val gate = CompletableDeferred<Unit>()
            first.readerDrainGate = gate
            engine.prepare(legacy("A"), 0, 1)
            val drainOutcome = CompletableDeferred<NativeReaderDrainResult>()
            val drain = launch(start = CoroutineStart.UNDISPATCHED) {
                drainOutcome.complete(engine.awaitRetiredReaders(1L))
            }
            first.readerDrainEntered.await()
            val close = async(start = CoroutineStart.UNDISPATCHED) { engine.close() }
            assertFalse(close.isCompleted)
            gate.complete(Unit)
            val result = assertIs<NativeReaderDrainResult.Unacknowledged>(drainOutcome.await())
            assertEquals("native_audio_owner_closed", result.failure.detail)
            close.await()
            drain.join()
            assertTrue(first.destroyed)
        }

    @Test fun postShutdownPendingResourceCloseIsJoinedEvenAfterAnEarlierCloseFailure() =
        ownershipTest(AuthoredDesktopDeck("A")) {
            val first = firstDeck
            val firstResource = resource("first-close-failure", failClose = true)
            engine.prepare(legacy("A", firstResource), 0, 1)
            val firstClose = assertFailsWith<VeyraException> { engine.close() }
            assertEquals(FailureCode.RECOVERY_REQUIRED, firstClose.failure.code)
            assertEquals(1, firstResource.closeCalls.get())

            val pending = resource("late-pending")
            val gate = CompletableDeferred<Unit>()
            pending.closeGate = gate
            val late = async { runCatching { engine.prepare(legacy("late", pending), 0, 2) } }
            pending.closeEntered.await()
            assertFalse(late.isCompleted, "The rejected handoff is already closing its transferred resource")
            val secondClose = async { runCatching { engine.close() } }
            assertFalse(secondClose.isCompleted, "Close must join the observed pending owner, not trust the past result")
            val alias = async { runCatching { engine.prepare(legacy("late-alias", pending), 0, 3) } }
            gate.complete(Unit)

            assertIs<IllegalStateException>(late.await().exceptionOrNull())
            assertIs<IllegalStateException>(alias.await().exceptionOrNull())
            val closeError = secondClose.await().exceptionOrNull()
            assertEquals(FailureCode.RECOVERY_REQUIRED, assertIs<VeyraException>(closeError).failure.code)
            assertEquals(1, pending.closeCalls.get(), "One transferred unique resource is attempted once")
            assertTrue(pending.closed)
            assertEquals(1, firstResource.closeCalls.get())
        }
}
