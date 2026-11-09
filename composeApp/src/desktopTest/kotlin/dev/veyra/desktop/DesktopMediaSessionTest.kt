package dev.veyra.desktop

import dev.veyra.desktop.session.DesktopMediaSession
import dev.veyra.desktop.session.DesktopSessionStatus
import dev.veyra.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fake session backends only: lifecycle/failure evidence, not native media-control proof. */
class DesktopMediaSessionTest {
    private class CleanupFailure(val attempt: Int) : IllegalStateException("authored backend cleanup failure")

    private class Backend(
        private val updating: (PlaybackState) -> Unit = {},
        private val closing: () -> Unit = {},
    ) : DesktopMediaSession.Backend {
        val updates = AtomicInteger()
        val closes = AtomicInteger()
        override fun update(state: PlaybackState) { updates.incrementAndGet(); updating(state) }
        override fun close() { closes.incrementAndGet(); closing() }
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Test backend did not reach the expected lifecycle step")
    }

    @Test fun activeUpdateIsJoinedBeforeBackendReleaseAndParentRemainsUsable(): Unit = runBlocking {
        val parent = SupervisorJob()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val updateReturned = AtomicBoolean()
        val playback = MutableStateFlow(PlaybackState())
        val backend = Backend(updating = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            updateReturned.set(true)
        }, closing = { assertTrue(updateReturned.get(), "Backend was released during an active update") })
        val session = DesktopMediaSession(backend, CoroutineScope(parent), playback, DesktopSessionStatus.Available("fixture"))
        try {
            await(entered)
            val result = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
            assertFalse(result.isCompleted)
            proceed.countDown()
            result.await()
            session.close()
            playback.value = playback.value.copy(positionMs = 1_000)
            assertEquals(1, backend.updates.get())
            assertEquals(1, backend.closes.get())
            assertTrue(parent.isActive)
            assertFalse(parent.children.any(), "Session's owned scope must be fully drained")
        } finally {
            proceed.countDown()
            session.close()
            parent.cancelAndJoin()
        }
    }

    @Test fun concurrentSuccessfulClosesAwaitOneBackendRelease(): Unit = runBlocking {
        val parent = SupervisorJob()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val backend = Backend(closing = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        })
        val session = DesktopMediaSession(backend, CoroutineScope(parent), MutableStateFlow(PlaybackState()), DesktopSessionStatus.Available("fixture"))
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
            await(entered)
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.close() }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            proceed.countDown()
            first.await()
            second.await()
            session.close()
            assertEquals(1, backend.closes.get())
            assertTrue(parent.isActive)
            assertFalse(parent.children.any())
        } finally {
            proceed.countDown()
            session.close()
            parent.cancelAndJoin()
        }
    }

    @Test fun backendFailureIsStickyForConcurrentAndRepeatedCloseAndStillDrainsScope(): Unit = runBlocking {
        val parent = SupervisorJob()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val failure = CleanupFailure(attempt = 1)
        val backend = Backend(closing = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            throw failure
        })
        val session = DesktopMediaSession(backend, CoroutineScope(parent), MutableStateFlow(PlaybackState()), DesktopSessionStatus.Available("fixture"))
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.close() } }
            await(entered)
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.close() } }
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            proceed.countDown()
            assertSame(failure, first.await().exceptionOrNull())
            assertSame(failure, second.await().exceptionOrNull())
            assertSame(failure, assertFailsWith<CleanupFailure> { session.close() })
            assertEquals(1, backend.closes.get())
            assertTrue(parent.isActive)
            assertFalse(parent.children.any(), "Failed backend release must not retain the session scope")
        } finally {
            proceed.countDown()
            runCatching { session.close() }
            parent.cancelAndJoin()
        }
    }

    @Test fun callerCancellationCannotAbandonAnInFlightBackendRelease(): Unit = runBlocking {
        val parent = SupervisorJob()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val released = AtomicBoolean()
        val backend = Backend(closing = {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
            released.set(true)
        })
        val session = DesktopMediaSession(backend, CoroutineScope(parent), MutableStateFlow(PlaybackState()), DesktopSessionStatus.Available("fixture"))
        try {
            val caller = launch(start = CoroutineStart.UNDISPATCHED) { session.close() }
            await(entered)
            caller.cancel()
            assertFalse(caller.isCompleted)
            assertFalse(released.get())
            proceed.countDown()
            caller.join()
            session.close()
            assertTrue(released.get())
            assertEquals(1, backend.closes.get())
            assertTrue(parent.isActive)
            assertFalse(parent.children.any())
        } finally {
            proceed.countDown()
            session.close()
            parent.cancelAndJoin()
        }
    }

    @Test fun unavailableSessionStillClosesItsOwnedScopeIdempotently(): Unit = runBlocking {
        val parent = SupervisorJob()
        val status = DesktopSessionStatus.Unavailable("authored_unavailable")
        val session = DesktopMediaSession(null, CoroutineScope(parent), MutableStateFlow(PlaybackState()), status)
        try {
            session.close()
            session.close()
            assertEquals(status, session.status.value)
            assertTrue(parent.isActive)
            assertFalse(parent.children.any())
        } finally {
            session.close()
            parent.cancelAndJoin()
        }
    }
}
