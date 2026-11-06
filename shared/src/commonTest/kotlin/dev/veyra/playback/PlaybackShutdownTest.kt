package dev.veyra.playback

import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Shutdown ownership/failure tests, not native decoder or physical audio verification. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackShutdownTest {
    @Test fun concurrentCloseKeepsNativeFailureButWaitsForLateResolverAndResourceCleanup() = runTest {
        val f = PlaybackFixture(this)
        val resolveGate = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        var resourceCloseCalls = 0
        var resourceClosed = false
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() {
                resourceCloseCalls++
                closeStarted.complete(Unit)
                closeGate.await()
                resourceClosed = true
            }
        }
        f.resolver.behavior = { item, binding ->
            f.resolver.result(item, binding).also { withContext(NonCancellable) { resolveGate.await() } }
        }
        f.engine.beforeClose = { error("authored-private-native-detail") }
        try {
            f.command(PlaybackCommand.Play(QueueContext.MUSIC, listOf(testTrack())))
            val first = async { runCatching { f.controller.close() }.exceptionOrNull() }
            val second = async { runCatching { f.controller.close() }.exceptionOrNull() }
            runCurrent()
            assertEquals(1, f.engine.closeCalls)
            assertFalse(first.isCompleted || second.isCompleted)
            assertFalse(closeStarted.isCompleted)
            resolveGate.complete(Unit); runCurrent()
            assertTrue(closeStarted.isCompleted)
            assertFalse(first.isCompleted || second.isCompleted)
            assertFalse(resourceClosed)
            closeGate.complete(Unit); runCurrent()
            val failure = assertIs<VeyraException>(first.await())
            assertEquals("Native playback close failed", failure.failure.detail)
            assertFalse(failure.stackTraceToString().contains("authored-private-native-detail"))
            assertSame(failure, second.await())
            assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
            assertTrue(resourceClosed)
            assertEquals(1, resourceCloseCalls)
            assertEquals(1, f.engine.closeCalls)
            assertTrue(f.engine.loads.isEmpty())
        } finally {
            resolveGate.complete(Unit); closeGate.complete(Unit)
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun failedFinalStopStillClosesEngineAndKeepsBothFailuresSanitizedAndSticky() = runTest {
        for (closeAlsoFails in listOf(false, true)) {
            val f = PlaybackFixture(this)
            try {
                f.play(listOf(testTrack()))
                f.engine.beforeStop = { error("authored-private-stop-detail") }
                if (closeAlsoFails) f.engine.beforeClose = { error("authored-private-close-detail") }
                val failure = assertFailsWith<VeyraException> { f.controller.close() }
                assertEquals("Native playback stop failed", failure.failure.detail)
                assertEquals(!closeAlsoFails, f.engine.closed)
                assertEquals(1, f.engine.closeCalls)
                if (closeAlsoFails) {
                    assertEquals("Native playback close failed",
                        assertIs<VeyraException>(failure.suppressedExceptions.single()).failure.detail)
                } else assertTrue(failure.suppressedExceptions.isEmpty())
                assertFalse(failure.stackTraceToString().contains("authored-private-"))
                f.engine.beforeStop = null
                f.engine.beforeClose = null
                assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
                assertEquals(1, f.engine.closeCalls)
            } finally {
                try { assertFailsWith<VeyraException> { f.controller.close() } }
                finally { f.store.close() }
            }
        }
    }

    @Test fun resourceCloseCancellationIsStickyWithoutSkippingLaterResourceCloses() = runTest {
        val f = PlaybackFixture(this)
        val cancellation = CancellationException("authored resource cancellation")
        var failingCloses = 0
        val later = CountingPlaybackResource()
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() { failingCloses++; throw cancellation }
        }
        f.resolver.resources["second"] = later
        f.resolver.behavior = { item, binding -> f.resolver.result(item, binding).copy(mediaId = "rejected-identity") }
        try {
            f.play(listOf(testTrack()))
            f.play(listOf(testTrack("second")))
            assertTrue(f.engine.loads.isEmpty())
            assertEquals(1, failingCloses)
            assertEquals(1, later.closeCalls)
            assertTrue(later.closed)
            assertSame(cancellation, assertFailsWith<CancellationException> { f.controller.close() })
            assertSame(cancellation, assertFailsWith<CancellationException> { f.controller.close() })
            assertEquals(1, f.engine.closeCalls)
            assertEquals(1, failingCloses)
        } finally {
            try { assertFailsWith<CancellationException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun nativeCloseCancellationRemainsCancellationAndRetainsResourceFailure() = runTest {
        val f = PlaybackFixture(this)
        val cancellation = CancellationException("authored native cancellation")
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() { error("authored-private-resource-detail") }
        }
        f.resolver.behavior = { item, binding -> f.resolver.result(item, binding).copy(mediaId = "rejected-identity") }
        f.engine.beforeClose = { throw cancellation }
        try {
            f.play(listOf(testTrack()))
            val failure = assertFailsWith<CancellationException> { f.controller.close() }
            assertSame(cancellation, failure)
            val resourceFailure = assertIs<VeyraException>(failure.suppressedExceptions.single())
            assertEquals("Playback resource cleanup failed", resourceFailure.failure.detail)
            assertFalse(resourceFailure.stackTraceToString().contains("authored-private-resource-detail"))
            assertSame(failure, assertFailsWith<CancellationException> { f.controller.close() })
            assertEquals(1, f.engine.closeCalls)
        } finally {
            try { assertFailsWith<CancellationException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun callerCancellationWaitsForDrainAndIsNotCachedAsTheShutdownOutcome() = runTest {
        for (nativeFails in listOf(false, true)) {
            val f = PlaybackFixture(this)
            val closeStarted = CompletableDeferred<Unit>()
            val closeGate = CompletableDeferred<Unit>()
            val observed = CompletableDeferred<CancellationException>()
            val resource = CountingPlaybackResource()
            f.resolver.resources["first"] = resource
            f.engine.beforeClose = {
                closeStarted.complete(Unit)
                closeGate.await()
                if (nativeFails) error("authored close failure")
            }
            try {
                f.play(listOf(testTrack()))
                val caller = launch {
                    try {
                        f.controller.close()
                        fail("A cancelled close caller must not report success")
                    } catch (cancelled: CancellationException) {
                        observed.complete(cancelled)
                        throw cancelled
                    }
                }
                runCurrent()
                assertTrue(closeStarted.isCompleted)
                caller.cancel(CancellationException("authored caller cancellation")); runCurrent()
                assertFalse(caller.isCompleted)
                closeGate.complete(Unit); caller.join()
                val cancellation = observed.await()
                assertEquals("authored caller cancellation", cancellation.message)
                assertEquals(1, resource.closeCalls)
                assertEquals(1, f.engine.closeCalls)
                if (nativeFails) {
                    val nativeFailure = assertIs<VeyraException>(cancellation.suppressedExceptions.single())
                    assertSame(nativeFailure, assertFailsWith<VeyraException> { f.controller.close() })
                } else {
                    assertTrue(cancellation.suppressedExceptions.isEmpty())
                    f.controller.close() // The original caller's cancellation is not sticky.
                    assertTrue(f.engine.closed)
                }
            } finally {
                closeGate.complete(Unit)
                try {
                    if (nativeFails) assertFailsWith<VeyraException> { f.controller.close() }
                    else f.controller.close()
                } finally { f.store.close() }
            }
        }
    }

    @Test fun saturatedNativeMailboxTimesOutGracefulCheckpointButStillWaitsForActualRelease() = runTest {
        val f = PlaybackFixture(this)
        val prepareStarted = CompletableDeferred<Unit>()
        val releaseStarted = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        var resourceCloseCalls = 0
        var resourceClosed = false
        f.engine.beforePrepare = { prepareStarted.complete(Unit); awaitCancellation() }
        f.resolver.resources["first"] = object : PlaybackResource {
            override suspend fun close() = withContext(NonCancellable) {
                resourceCloseCalls++
                releaseStarted.complete(Unit)
                releaseGate.await()
                resourceClosed = true
            }
        }
        try {
            f.play(listOf(testTrack()))
            assertTrue(prepareStarted.isCompleted)
            // The native worker is in a cancellable prepare; all 128 buffered pauses are admitted.
            repeat(128) { f.controller.dispatch(PlaybackCommand.Pause).getOrThrow() }
            val blocked = async { f.controller.dispatch(PlaybackCommand.Pause) }
            runCurrent()
            assertFalse(blocked.isCompleted) // The actor cannot reach a queued Shutdown now.
            val closing = async { runCatching { f.controller.close() }.exceptionOrNull() }
            runCurrent()
            advanceTimeBy(4_999); runCurrent()
            assertFalse(releaseStarted.isCompleted)
            assertFalse(closing.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(releaseStarted.isCompleted) // Deadline cancelled the blocked native work.
            assertFalse(resourceClosed)
            assertFalse(closing.isCompleted) // Timeout is not a native/resource release receipt.
            assertEquals(0, f.engine.closeCalls)
            assertIs<PlaybackResult.Failure>(blocked.await())
            assertIs<PlaybackResult.Failure>(f.controller.dispatch(PlaybackCommand.Pause))
            releaseGate.complete(Unit); runCurrent()
            val failure = assertIs<VeyraException>(closing.await())
            assertEquals(FailureCode.UNEXPECTED, failure.failure.code)
            assertEquals("Playback shutdown checkpoint timed out", failure.failure.detail)
            assertTrue(resourceClosed)
            assertTrue(f.engine.closed)
            assertEquals(1, resourceCloseCalls)
            assertEquals(1, f.engine.closeCalls)
            assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
            assertEquals(1, resourceCloseCalls)
        } finally {
            releaseGate.complete(Unit)
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }

    @Test fun parentCancellationBeforeOrAfterActorStartupRetainsOneNativeShutdownResult() = runTest {
        for (startActor in listOf(false, true)) {
            val parent = SupervisorJob(coroutineContext[Job])
            if (!startActor) parent.cancel()
            val f = PlaybackFixture(this, ownerScope = CoroutineScope(coroutineContext + parent))
            f.engine.beforeClose = { error("authored native close failure") }
            try {
                if (startActor) {
                    f.controller.initialize().getOrThrow(); runCurrent()
                    parent.cancel(); runCurrent()
                    assertEquals(1, f.engine.closeCalls)
                } else assertEquals(0, f.engine.closeCalls)
                val failure = assertFailsWith<VeyraException> { f.controller.close() }
                assertEquals("Native playback close failed", failure.failure.detail)
                assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
                assertEquals(1, f.engine.closeCalls)
                assertEquals(1, f.engine.stops.size)
            } finally {
                parent.cancel()
                try { assertFailsWith<VeyraException> { f.controller.close() } }
                finally { f.store.close() }
            }
        }
    }

    @Test fun finalCheckpointFailureIsReportedAfterNativeAndResourceRelease() = runTest {
        val f = PlaybackFixture(this)
        val resource = CountingPlaybackResource()
        f.resolver.resources["first"] = resource
        try {
            f.play(listOf(testTrack()))
            f.event(EngineEvent.Position(f.state.generation, 8_000, 100_000))
            f.persistence.reject = { true }
            val failure = assertFailsWith<VeyraException> { f.controller.close() }
            assertEquals(FailureCode.STORAGE_IO, failure.failure.code)
            assertTrue(f.engine.closed)
            assertEquals(1, f.engine.closeCalls)
            assertEquals(1, resource.closeCalls)
            f.persistence.reject = null
            assertSame(failure, assertFailsWith<VeyraException> { f.controller.close() })
        } finally {
            f.persistence.reject = null
            try { assertFailsWith<VeyraException> { f.controller.close() } }
            finally { f.store.close() }
        }
    }
}
