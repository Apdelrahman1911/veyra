package dev.veyra.desktop

import dev.veyra.desktop.session.DesktopMediaSession
import dev.veyra.desktop.session.DesktopSessionStatus
import dev.veyra.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Controlled backends/dispatchers only. No native library, D-Bus, window or live source opens. */
class DesktopMediaSessionFactoryTest {
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext() {
            val task = tasks.poll(10, TimeUnit.SECONDS)
            checkNotNull(task) { "The expected fixture continuation was not dispatched" }
            task.run()
        }
        fun runAvailable() {
            while (true) (tasks.poll() ?: return).run()
        }
    }

    /** Signals AFTER the actual session collector has handled a value. This proves an initial
     * projection really was consumed before acquisition, rather than hoping for an IO race. */
    private class ObservedPlayback(initial: PlaybackState = PlaybackState()) : StateFlow<PlaybackState> {
        private val upstream = MutableStateFlow(initial)
        private val delivered = Channel<PlaybackState>(Channel.UNLIMITED)
        val collectorStopped = CompletableDeferred<Unit>()
        override val value: PlaybackState get() = upstream.value
        override val replayCache: List<PlaybackState> get() = upstream.replayCache
        fun change(value: PlaybackState) { upstream.value = value }
        suspend fun awaitDelivery(expected: PlaybackState) = withTimeout(10_000) {
            assertEquals(expected, delivered.receive())
        }
        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<PlaybackState>): Nothing {
            try {
                upstream.collect(object : FlowCollector<PlaybackState> {
                    override suspend fun emit(value: PlaybackState) {
                        collector.emit(value)
                        delivered.send(value)
                    }
                })
            } finally { collectorStopped.complete(Unit) }
        }
    }

    private class Backend(
        private val updating: (PlaybackState) -> Unit = {},
        private val closing: () -> Unit = {},
    ) : DesktopMediaSession.Backend {
        val projections = ConcurrentLinkedQueue<PlaybackState>()
        val closes = AtomicInteger()
        override fun update(state: PlaybackState) { projections.add(state); updating(state) }
        override fun close() { closes.incrementAndGet(); closing() }
    }

    private class Harness(
        val playback: ObservedPlayback = ObservedPlayback(),
        register: (DesktopMediaSession) -> Unit = {},
        acquire: () -> DesktopMediaSession.Backend,
    ) {
        val lifetime = SupervisorJob()
        private val caller = SupervisorJob()
        private val returns = QueuedDispatcher()
        val acquisition = QueuedDispatcher()
        private val published = AtomicReference<DesktopMediaSession?>()
        val registrations = AtomicInteger()
        val acquisitions = AtomicInteger()
        val owned: DesktopMediaSession get() = checkNotNull(published.get())
        val task = CoroutineScope(caller + returns).async {
            DesktopMediaSession.createOwned(
                parentScope = CoroutineScope(lifetime), playback = playback,
                implementation = "fixture", unavailableReason = "authored_session_unavailable",
                onOwned = { owner ->
                    registrations.incrementAndGet()
                    check(published.compareAndSet(null, owner)) { "A second owner was registered" }
                    register(owner)
                },
                dispatcher = acquisition,
                acquireBackend = { acquisitions.incrementAndGet(); acquire() },
            )
        }
        fun start() { returns.runNext() }
        suspend fun finish(): Result<DesktopMediaSession> {
            repeat(16) {
                if (task.isCompleted) return runCatching { task.await() }
                returns.runNext()
            }
            error("Fixture initialization did not complete within its bounded continuation count")
        }
        suspend fun cleanup() = withContext(NonCancellable) {
            task.cancel()
            acquisition.runAvailable()
            finish()
            published.get()?.let { runCatching { it.close() } }
            caller.cancelAndJoin()
            lifetime.cancelAndJoin()
        }
    }

    private fun assertSanitized(failure: Throwable?, reason: String): Throwable {
        val error = assertNotNull(failure)
        assertEquals(reason, error.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.toString().contains(SENSITIVE_TEXT))
        return error
    }

    @Test fun ownerIsPublishedBeforeAcquisitionAndLatestConsumedProjectionIsReplayedOnAttach(): Unit = runBlocking {
        val playback = ObservedPlayback()
        val backend = Backend()
        val harness = Harness(playback = playback, acquire = { backend })
        try {
            harness.start()
            assertEquals(1, harness.registrations.get())
            assertEquals(0, harness.acquisitions.get())
            assertEquals(DesktopSessionStatus.Unavailable("media_session_not_initialized"), harness.owned.status.value)
            playback.awaitDelivery(playback.value)
            val latest = playback.value.copy(positionMs = 42_000)
            playback.change(latest)
            playback.awaitDelivery(latest)
            assertTrue(backend.projections.isEmpty())

            harness.acquisition.runNext()
            assertFalse(harness.task.isCompleted, "Return delivery must still be queued")
            assertEquals(listOf(latest), backend.projections.toList(), "No later playback mutation may be required for initial controls")
            assertSame(harness.owned, harness.finish().getOrThrow())
            assertEquals(DesktopSessionStatus.Available("fixture"), harness.owned.status.value)
            assertEquals(1, harness.registrations.get())
            harness.owned.close()
            assertEquals(1, backend.closes.get())
            assertTrue(harness.lifetime.isActive)
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun cancellationAfterAcquisitionButBeforeDispatcherReturnCannotLoseItsPublishedOwner(): Unit = runBlocking {
        val backend = Backend()
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            harness.acquisition.runNext()
            assertEquals(1, harness.acquisitions.get())
            assertEquals(0, backend.closes.get())
            assertFalse(harness.task.isCompleted)
            val owner = harness.owned
            harness.task.cancel()
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            assertSame(owner, harness.owned)
            assertEquals(1, backend.closes.get())
            owner.close()
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
            assertTrue(harness.lifetime.isActive)
        } finally { harness.cleanup() }
    }

    @Test fun lostReturnWithFailedReleaseIsSanitizedRetainedAndStickyAtEveryHostClose(): Unit = runBlocking {
        val backend = Backend(closing = { throw IllegalStateException(SENSITIVE_TEXT) })
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            harness.acquisition.runNext()
            val owner = harness.owned
            harness.task.cancel()
            assertSanitized(harness.finish().exceptionOrNull(), "media_session_cleanup_failed")
            val first = assertSanitized(runCatching { owner.close() }.exceptionOrNull(), "media_session_cleanup_failed")
            val repeated = assertSanitized(runCatching { owner.close() }.exceptionOrNull(), "media_session_cleanup_failed")
            assertSame(first, repeated)
            assertEquals(1, backend.closes.get(), "Frozen session close must not perform duplicate native release")
            assertEquals(1, harness.registrations.get())
            assertFalse(harness.lifetime.children.any())
            assertTrue(harness.lifetime.isActive)
        } finally { harness.cleanup() }
    }

    @Test fun alreadyCancelledCallerReceivesTheSuccessfulCloseFenceRatherThanItsCancellationText(): Unit = runBlocking {
        val backend = Backend()
        val harness = Harness(acquire = { backend })
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            val outcome = CompletableDeferred<Result<Unit>>()
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException(SENSITIVE_TEXT))
                outcome.complete(runCatching { harness.owned.close() })
            }
            val result = withTimeout(10_000) { outcome.await() }
            withTimeout(10_000) { caller.join() }
            assertTrue(result.isSuccess, "Observe close itself, not only completion of an already-cancelled Job")
            assertEquals(1, backend.closes.get())
            harness.owned.close()
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun alreadyCancelledCallerReceivesTheSameSanitizedStickyCloseFailureAsTheHost(): Unit = runBlocking {
        val backend = Backend(closing = { throw IllegalStateException(SENSITIVE_TEXT) })
        val harness = Harness(acquire = { backend })
        try {
            harness.start(); harness.acquisition.runNext(); harness.finish().getOrThrow()
            val outcome = CompletableDeferred<Result<Unit>>()
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException(SENSITIVE_TEXT))
                outcome.complete(runCatching { harness.owned.close() })
            }
            val failure = assertSanitized(withTimeout(10_000) { outcome.await() }.exceptionOrNull(), "media_session_cleanup_failed")
            withTimeout(10_000) { caller.join() }
            val hostFailure = assertSanitized(runCatching { harness.owned.close() }.exceptionOrNull(), "media_session_cleanup_failed")
            assertSame(failure, hostFailure)
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun cancellationBeforeAcquisitionClosesPublishedResourceFreeOwnerWithoutOpeningBackend(): Unit = runBlocking {
        val backend = Backend()
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            val owner = harness.owned
            harness.task.cancel()
            harness.acquisition.runNext()
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            assertEquals(0, harness.acquisitions.get())
            owner.close()
            assertEquals(0, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
            assertTrue(harness.lifetime.isActive)
        } finally { harness.cleanup() }
    }

    @Test fun throwingRegistrationIsSanitizedAndDrainsScopeBeforeAnyNativeAcquisition(): Unit = runBlocking {
        val backend = Backend()
        val harness = Harness(register = { throw IllegalArgumentException(SENSITIVE_TEXT) }, acquire = { backend })
        try {
            harness.start()
            assertSanitized(harness.finish().exceptionOrNull(), "media_session_owner_registration_failed")
            assertEquals(1, harness.registrations.get())
            assertEquals(0, harness.acquisitions.get())
            assertEquals(0, backend.closes.get())
            harness.owned.close()
            assertFalse(harness.lifetime.children.any())
            assertTrue(harness.lifetime.isActive)
        } finally { harness.cleanup() }
    }

    @Test fun cancellingRegistrationPreservesCancellationAndStillDrainsItsResourceFreeScope(): Unit = runBlocking {
        val harness = Harness(register = {
            throw CancellationException(SENSITIVE_TEXT).apply {
                initCause(IllegalArgumentException("authored private callback cause: $SENSITIVE_TEXT"))
                addSuppressed(IllegalStateException("authored private callback suppressed: $SENSITIVE_TEXT"))
            }
        }, acquire = { Backend() })
        try {
            harness.start()
            val failure = assertSanitized(harness.finish().exceptionOrNull(), "media_session_initialization_cancelled")
            assertTrue(failure is CancellationException)
            assertEquals(0, harness.acquisitions.get())
            harness.owned.close()
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun closingRegisteredOwnerBeforeAcquisitionPreventsAnyLateNativeAllocation(): Unit = runBlocking {
        val backend = Backend()
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            harness.owned.close()
            harness.acquisition.runNext()
            assertSanitized(harness.finish().exceptionOrNull(), "media_session_closed_during_initialization")
            assertEquals(0, harness.acquisitions.get())
            assertEquals(0, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
            assertTrue(harness.lifetime.isActive)
        } finally { harness.cleanup() }
    }

    @Test fun absentBackendAndMissingNativeLibraryAreGracefulOnlyWithAClosedResourceFreeOwner(): Unit = runBlocking {
        for (failure in listOf(IllegalStateException(SENSITIVE_TEXT), UnsatisfiedLinkError(SENSITIVE_TEXT))) {
            val harness = Harness(acquire = { throw failure })
            try {
                harness.start()
                harness.acquisition.runNext()
                assertSame(harness.owned, harness.finish().getOrThrow())
                assertEquals(DesktopSessionStatus.Unavailable("authored_session_unavailable"), harness.owned.status.value)
                assertEquals(1, harness.acquisitions.get())
                assertEquals(1, harness.registrations.get())
                harness.owned.close()
                assertFalse(harness.lifetime.children.any())
                assertTrue(harness.lifetime.isActive)
            } finally { harness.cleanup() }
        }
    }

    @Test fun failedInitialProjectionReleasesAttachedBackendBeforeGracefulUnavailableReturn(): Unit = runBlocking {
        val backend = Backend(updating = { throw IllegalStateException(SENSITIVE_TEXT) })
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            harness.playback.awaitDelivery(harness.playback.value)
            harness.acquisition.runNext()
            assertSame(harness.owned, harness.finish().getOrThrow())
            assertEquals(DesktopSessionStatus.Unavailable("authored_session_unavailable"), harness.owned.status.value)
            assertEquals(1, backend.closes.get())
            harness.owned.close()
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun failedInitialProjectionAndFailedReleaseCannotBecomeGracefulUnavailable(): Unit = runBlocking {
        val backend = Backend(updating = { throw IllegalStateException(SENSITIVE_TEXT) },
            closing = { throw IllegalStateException(SENSITIVE_TEXT) })
        val harness = Harness(acquire = { backend })
        try {
            harness.start()
            harness.playback.awaitDelivery(harness.playback.value)
            harness.acquisition.runNext()
            assertSanitized(harness.finish().exceptionOrNull(), "media_session_cleanup_failed")
            assertSanitized(runCatching { harness.owned.close() }.exceptionOrNull(), "media_session_cleanup_failed")
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
        } finally { harness.cleanup() }
    }

    @Test fun hostCloseWaitsForAdmittedSynchronousAcquisitionBeforeReleasingItsBackend(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val backend = Backend()
        val harness = Harness(acquire = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS)) { "Fixture acquisition was not released" }
            backend
        })
        var executing: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            harness.start()
            harness.playback.awaitDelivery(harness.playback.value)
            executing = async(Dispatchers.IO) { harness.acquisition.runNext() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            harness.task.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { harness.owned.close() }
            withTimeout(10_000) { harness.playback.collectorStopped.await() }
            assertFalse(closing.isCompleted)
            assertEquals(0, backend.closes.get())
            proceed.countDown()
            executing.await()
            closing.await()
            assertTrue(harness.finish().exceptionOrNull() is CancellationException)
            assertEquals(1, harness.acquisitions.get())
            assertEquals(1, backend.closes.get())
            harness.owned.close()
            assertEquals(1, backend.closes.get())
            assertFalse(harness.lifetime.children.any())
        } finally {
            proceed.countDown()
            executing?.await()
            harness.cleanup()
        }
    }

    companion object { private const val SENSITIVE_TEXT = "authored-sensitive-backend-text-not-for-diagnostics" }
}
