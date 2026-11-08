package dev.veyra.playback

import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Authored controller/owner fakes only: no native, file-descriptor, Room or physical audio proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReaderDrainTest {
    @Test fun crossfadeRetiredAWithoutAnAssetRowCannotAcknowledgeUntilItsReaderCloses() = runTest {
        val f = PlaybackFixture(this)
        val resource = HeldResource()
        f.resolver.resources["first"] = resource
        f.engine.deferRetiredClosures = true
        try {
            promoteSecond(f)
            assertTrue(f.store.state.value.downloads.isEmpty())
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertTrue(resource.entered.isCompleted)
            assertFalse(request.isCompleted)
            assertEquals("second", f.engine.active?.source?.mediaId)
            assertEquals(generation, f.state.generation)
            assertTrue(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
            resource.release.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertTrue(resource.closed)
            assertEquals(1, resource.closeCalls)
            assertEquals(listOf(generation), f.engine.readerDrains)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(stops, f.engine.stops)
        } finally { resource.release.complete(Unit); f.close() }
    }

    @Test fun pausedAwayStagedReaderRemainsPartOfTheRequiredNativeDrain() = runTest {
        val f = PlaybackFixture(this)
        val resource = HeldResource()
        f.resolver.resources["second"] = resource
        f.engine.deferRetiredClosures = true
        try {
            f.play(crossfade = true)
            f.readyNext()
            f.command(PlaybackCommand.Pause)
            assertNull(f.state.crossfade)
            assertNull(f.engine.staged)
            assertEquals("second", f.engine.retired.single().source.mediaId)
            val stops = f.engine.stops.toList()
            val request = async { f.controller.releaseForAssetRemoval("second") }
            runCurrent()
            assertTrue(resource.entered.isCompleted)
            assertFalse(request.isCompleted)
            resource.release.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Accepted>(request.await())
            assertEquals("first", f.state.currentItem?.id)
            assertFalse(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
            assertEquals(1, resource.closeCalls)
        } finally { resource.release.complete(Unit); f.close() }
    }

    @Test fun unacknowledgedRetiredAResultDoesNotStopBOrDisableItsSubsequentControls() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("second")))
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            f.engine.readerDrainOverride = { NativeReaderDrainResult.Unacknowledged(AppFailure(FailureCode.STORAGE_IO, "authored_retired_failure")) }
            assertEquals(FailureCode.STORAGE_IO, assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first")).failure.code)
            assertEquals(generation, f.state.generation)
            assertEquals(PlaybackPhase.PLAYING, f.state.phase)
            assertNull(f.state.failure)
            f.command(PlaybackCommand.Pause)
            assertFalse(f.state.isPlaying)
            f.command(PlaybackCommand.Resume)
            f.command(PlaybackCommand.Seek(4_000))
            assertTrue(f.state.isPlaying)
            assertEquals(4_000L, f.state.positionMs)
            assertEquals(generation, f.state.generation)
            assertEquals(stops, f.engine.stops)
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
        } finally { f.close() }
    }

    @Test fun unexpectedThrowFromTheDrainIsLocallyContainedWithoutPoisoningTheNativeWorker() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("second")))
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            f.engine.beforeReaderDrain = { error("authored-private-drain-detail") }
            val failure = assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            assertEquals(FailureCode.UNEXPECTED, failure.failure.code)
            assertFalse(failure.failure.toString().contains("authored-private-drain-detail"))
            assertEquals(generation, f.state.generation)
            assertEquals(PlaybackPhase.PLAYING, f.state.phase)
            assertEquals(stops, f.engine.stops)
            f.engine.beforeReaderDrain = null
            f.command(PlaybackCommand.Pause)
            f.command(PlaybackCommand.Resume)
            assertIs<PlaybackResult.Accepted>(f.controller.releaseForAssetRemoval("first"))
            assertTrue(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
        } finally { f.engine.beforeReaderDrain = null; f.close() }
    }

    @Test fun resourceThrownCancellationIsNotAnAcknowledgementOrUnrelatedOwnerCancellation() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("second")))
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            f.engine.beforeReaderDrain = { throw CancellationException("authored resource cancellation") }
            assertEquals(FailureCode.CANCELLED, assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first")).failure.code)
            f.engine.beforeReaderDrain = null
            f.command(PlaybackCommand.Pause)
            f.command(PlaybackCommand.Resume)
            assertEquals(generation, f.state.generation)
            assertTrue(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
            assertIs<PlaybackResult.Accepted>(f.controller.releaseForAssetRemoval("first"))
        } finally { f.engine.beforeReaderDrain = null; f.close() }
    }

    @Test fun aPositiveResultForAnotherGenerationNeverGrantsRemoval() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("second")))
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            f.engine.readerDrainOverride = { NativeReaderDrainResult.Drained(it + 1) }
            assertEquals(FailureCode.INVALID_STATE, assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first")).failure.code)
            assertEquals(generation, f.state.generation)
            assertTrue(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
        } finally { f.close() }
    }

    @Test fun nativeGenerationChangeOrOwnerCloseWhileAwaitingNeverBecomesPositiveEvidence() = runTest {
        for (closeOwner in listOf(false, true)) {
            val f = PlaybackFixture(this)
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            try {
                f.play(listOf(testTrack("second")))
                f.engine.beforeReaderDrain = { entered.complete(Unit); finish.await() }
                val request = async { f.controller.releaseForAssetRemoval("first") }
                runCurrent()
                assertTrue(entered.isCompleted)
                if (closeOwner) f.engine.closed = true else f.engine.activeGeneration++
                finish.complete(Unit)
                runCurrent()
                assertIs<PlaybackResult.Failure>(request.await())
            } finally { finish.complete(Unit); f.engine.beforeReaderDrain = null; f.close() }
        }
    }

    @Test fun controllerGenerationChangeDuringNativeAwaitIsRejectedAtTheFinalActorBoundary() = runTest {
        val f = PlaybackFixture(this)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            f.play(listOf(testTrack("second")))
            val original = f.state.generation
            f.engine.beforeReaderDrain = { entered.complete(Unit); finish.await() }
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertTrue(entered.isCompleted)
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("third"))))
            assertTrue(f.state.generation > original)
            finish.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Failure>(request.await())
            assertEquals("third", f.state.currentItem?.id)
            assertTrue(f.state.isPlaying)
        } finally { finish.complete(Unit); f.engine.beforeReaderDrain = null; f.close() }
    }

    @Test fun sameGenerationTargetAdmissionInvalidatesTheFenceEvenIfStagingDisappearsBeforeAck() = runTest {
        val f = PlaybackFixture(this)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            f.play(listOf(testTrack("second"), testTrack("first")), crossfade = true)
            val generation = f.state.generation
            f.engine.beforeReaderDrain = { entered.complete(Unit); finish.await() }
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertTrue(entered.isCompleted)
            f.event(EngineEvent.Position(generation, 90_000, 100_000))
            advanceTimeBy(250); runCurrent()
            assertEquals("first", f.state.crossfade?.incomingItem?.id)
            // Intentional user pause retires that new stage without changing the transport epoch.
            f.command(PlaybackCommand.Pause)
            assertNull(f.state.crossfade)
            assertEquals(generation, f.state.generation)
            finish.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Failure>(request.await())
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(generation, f.state.generation)
        } finally { finish.complete(Unit); f.engine.beforeReaderDrain = null; f.close() }
    }

    @Test fun targetStageCancellationFailureIsScopedBeforeTheMandatoryDrainRuns() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.play(listOf(testTrack("second"), testTrack("first")), crossfade = true)
            f.readyNext()
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            f.engine.beforeCancelNext = { error("authored target cleanup failure") }
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            assertEquals(listOf(generation), f.engine.readerDrains)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(PlaybackPhase.PLAYING, f.state.phase)
            assertEquals(generation, f.state.generation)
            assertEquals(stops, f.engine.stops)
            f.engine.beforeCancelNext = null
            f.command(PlaybackCommand.Seek(1_000))
            assertEquals(1_000L, f.state.positionMs)
            assertEquals(stops, f.engine.stops)
        } finally { f.engine.beforeCancelNext = null; f.close() }
    }

    @Test fun failedRetiredNativeOwnerIsRetainedWithoutRetryAndStillVetoesShutdown() = runTest {
        val f = PlaybackFixture(this)
        var closes = 0
        val failedOwner = object : PlaybackResource {
            override suspend fun close() { closes++; error("authored-private-retired-detail") }
        }
        f.resolver.resources["first"] = failedOwner
        f.engine.deferRetiredClosures = true
        try {
            promoteSecond(f)
            val stops = f.engine.stops.toList()
            repeat(2) { assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first")) }
            assertEquals(1, closes)
            assertSame(failedOwner, f.engine.retired.single().source.resource)
            f.command(PlaybackCommand.Pause)
            f.command(PlaybackCommand.Resume)
            assertTrue(f.state.isPlaying)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(stops, f.engine.stops)
            val failure = assertFailsWith<VeyraException> { f.controller.close() }
            assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
            assertEquals(1, closes)
        } finally {
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun failedUnhandedAResourceCannotTurnIntoErrorBAndIsNotRetriedByAnotherRemoval() = runTest {
        val f = PlaybackFixture(this)
        val resolveGate = CompletableDeferred<Unit>()
        var closes = 0
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() { closes++; error("authored-private-discarded-detail") }
        }
        f.resolver.behavior = { item, binding ->
            f.resolver.result(item, binding).also { if (item.id == "first") withContext(NonCancellable) { resolveGate.await() } }
        }
        try {
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("first"))))
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack("second"))))
            val generation = f.state.generation
            val stops = f.engine.stops.toList()
            val request = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertFalse(request.isCompleted)
            resolveGate.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Failure>(request.await())
            assertEquals(1, closes)
            assertEquals("second", f.state.currentItem?.id)
            assertEquals(PlaybackPhase.PLAYING, f.state.phase)
            assertEquals(generation, f.state.generation)
            assertEquals(stops, f.engine.stops)
            assertNull(f.state.failure)
            assertIs<PlaybackResult.Failure>(f.controller.releaseForAssetRemoval("first"))
            assertEquals(1, closes)
            f.command(PlaybackCommand.Pause)
            f.command(PlaybackCommand.Resume)
            assertTrue(f.state.isPlaying)
            assertEquals(stops, f.engine.stops)
            assertFailsWith<VeyraException> { f.controller.close() }
            assertEquals(1, closes)
        } finally {
            resolveGate.complete(Unit)
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun callerCancellationNeverAcknowledgesAndAnotherRequestWaitsForTheRetainedDrain() = runTest {
        val f = PlaybackFixture(this)
        val resource = HeldResource()
        f.resolver.resources["first"] = resource
        f.engine.deferRetiredClosures = true
        try {
            promoteSecond(f)
            val caller = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertTrue(resource.entered.isCompleted)
            caller.cancel()
            runCurrent()
            assertFailsWith<CancellationException> { caller.await() }
            assertFalse(resource.closed)
            val second = async { f.controller.releaseForAssetRemoval("first") }
            runCurrent()
            assertFalse(second.isCompleted)
            assertEquals(1, resource.closeCalls)
            resource.release.complete(Unit)
            runCurrent()
            assertIs<PlaybackResult.Accepted>(second.await())
            assertTrue(resource.closed)
            assertEquals(1, resource.closeCalls)
            assertTrue(f.state.isPlaying)
        } finally { resource.release.complete(Unit); f.close() }
    }

    @Test fun pristineInitializedControllerStillCallsTheMandatoryEngineDrainAtGenerationZero() = runTest {
        val f = PlaybackFixture(this)
        try {
            f.controller.initialize().getOrThrow()
            runCurrent()
            assertEquals(-1L, f.engine.activeGeneration)
            assertIs<PlaybackResult.Accepted>(f.controller.releaseForAssetRemoval("never-played"))
            assertEquals(listOf(0L), f.engine.readerDrains)
            assertEquals(0L, f.engine.activeGeneration)
            assertTrue(f.engine.loads.isEmpty())
            assertTrue(f.engine.stops.isEmpty())
        } finally { f.close() }
    }

    private suspend fun promoteSecond(f: PlaybackFixture) {
        f.play(crossfade = true)
        f.startFade()
        val next = requireNotNull(f.engine.staged)
        f.event(EngineEvent.CrossfadeCompleted(f.state.generation, next.generation, 2_250, 100_000, true))
        assertEquals("second", f.state.currentItem?.id)
        assertEquals("second", f.engine.active?.source?.mediaId)
    }

    private class HeldResource : PlaybackResource {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var closeCalls = 0
        var closed = false
        override suspend fun close() {
            closeCalls++
            entered.complete(Unit)
            release.await()
            closed = true
        }
    }
}
