package dev.veyra.desktop

import dev.veyra.desktop.runtime.AlreadyRunningException
import dev.veyra.desktop.runtime.DesktopProcessContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Real repository-private process locks, but no native session/window/audio is created. */
class DesktopLifetimeTest {
    // The extra field prevents coroutine stacktrace recovery from cloning the exception;
    // assertSame below checks the close fence, not a debug-mode copying implementation detail.
    private class CleanupFailure(val marker: Any = Any()) : IllegalStateException("authored cleanup failure")

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun runNext() { checkNotNull(tasks.poll()) { "Expected an authored callback continuation" }.run() }
        fun runAvailable() { while (true) (tasks.poll() ?: return).run() }
    }

    private fun owner(
        callbackDispatcher: CoroutineDispatcher = Dispatchers.Default,
        reportCallbackFailure: (String) -> Unit = { System.err.println(it) },
    ): Pair<DesktopLifetime, Path> {
        val root = Path.of(requireNotNull(System.getenv("VEYRA_PROJECT_ROOT")))
            .resolve(".work/desktop-audio/lifetime-tests")
        Files.createDirectories(root)
        val directory = Files.createTempDirectory(root, "authored-")
        return DesktopLifetime(DesktopProcessContext.acquire(directory), callbackDispatcher, reportCallbackFailure) to directory
    }

    @Test fun cancelledCallerObservesCompletedCloseInsteadOfItsOldCancellation(): Unit = runBlocking {
        val (owner, directory) = owner()
        val outcome = CompletableDeferred<Result<Unit>>()
        try {
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("authored prior cancellation"))
                outcome.complete(runCatching { owner.close() })
            }
            withTimeout(10_000) { caller.join(); outcome.await().getOrThrow() }
            DesktopProcessContext.acquire(directory).close()
        } finally { owner.close(); owner.scope.coroutineContext[Job]!!.join() }
    }

    @Test fun quitScopeCancellationCannotReplaceTheSuccessfulDrainResult(): Unit = runBlocking {
        val (owner, directory) = owner()
        val outcome = CompletableDeferred<Result<Unit>>()
        try {
            val caller = owner.scope.launch {
                // close cancels this scope itself. Its IO return must still preserve the
                // completed release result, rather than reporting an unrelated cancellation.
                outcome.complete(runCatching { owner.close() })
            }
            withTimeout(10_000) { caller.join(); outcome.await().getOrThrow() }
            assertTrue(caller.isCompleted)
            DesktopProcessContext.acquire(directory).close()
        } finally { owner.close(); owner.scope.coroutineContext[Job]!!.join() }
    }

    @Test fun failedCloseRemainsStickyForCancelledAndSubsequentCallers(): Unit = runBlocking {
        val (owner, directory) = owner()
        val calls = AtomicInteger()
        val failure = CleanupFailure()
        owner.verification = AutoCloseable { calls.incrementAndGet(); throw failure }
        val outcome = CompletableDeferred<Result<Unit>>()
        try {
            val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("authored prior cancellation"))
                outcome.complete(runCatching { owner.close() })
            }
            val first = withTimeout(10_000) { caller.join(); outcome.await() }
            assertSame(failure, first.exceptionOrNull())
            assertSame(failure, runCatching { owner.close() }.exceptionOrNull())
            assertEquals(1, calls.get())
            // Even a failed dependency close attempts the independent process-lock release.
            DesktopProcessContext.acquire(directory).close()
        } finally { runCatching { owner.close() }; owner.scope.coroutineContext[Job]!!.join() }
    }

    @Test fun admittedCallbackNonCancellableCleanupFinishesBeforeOwnersAndProcessLockRelease(): Unit = runBlocking {
        val (owner, directory) = owner()
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val cleanupFinished = AtomicBoolean()
        val verificationClosed = AtomicBoolean()
        owner.verification = AutoCloseable {
            assertTrue(cleanupFinished.get(), "Verification owner was released before its callback finished")
            verificationClosed.set(true)
        }
        try {
            assertTrue(owner.submitMediaCallback {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupEntered.complete(Unit)
                        finishCleanup.await()
                        cleanupFinished.set(true)
                    }
                }
            })
            withTimeout(10_000) { entered.await() }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.close() } }
            withTimeout(10_000) { cleanupEntered.await() }
            assertFalse(closing.isCompleted)
            assertFalse(verificationClosed.get())
            assertFalse(owner.submitMediaCallback { error("Retired callback must never run") })
            assertFailsWith<AlreadyRunningException> { DesktopProcessContext.acquire(directory).close() }
            finishCleanup.complete(Unit)
            withTimeout(10_000) { closing.await().getOrThrow() }
            assertTrue(cleanupFinished.get())
            assertTrue(verificationClosed.get())
            DesktopProcessContext.acquire(directory).close()
        } finally {
            finishCleanup.complete(Unit)
            owner.close()
            owner.scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun delayedCallbackIsDroppedAfterRetirementAndPostCloseSubmissionIsRejected(): Unit = runBlocking {
        val dispatcher = QueuedDispatcher()
        val (owner, directory) = owner(callbackDispatcher = dispatcher)
        val calls = AtomicInteger()
        try {
            assertTrue(owner.submitMediaCallback { calls.incrementAndGet() })
            // UNDISPATCHED reaches the close admission fence before its IO continuation.
            val closing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.close() } }
            assertFalse(owner.submitMediaCallback { calls.incrementAndGet() })
            dispatcher.runNext()
            withTimeout(10_000) { closing.await().getOrThrow() }
            assertFalse(owner.submitMediaCallback { calls.incrementAndGet() })
            dispatcher.runAvailable()
            assertEquals(0, calls.get())
            DesktopProcessContext.acquire(directory).close()
        } finally {
            dispatcher.runAvailable()
            owner.close()
            owner.scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun cancelledParentRejectsNewCallbacksAndNeverStartsItsQueuedCallback(): Unit = runBlocking {
        val dispatcher = QueuedDispatcher()
        val (owner, _) = owner(callbackDispatcher = dispatcher)
        val calls = AtomicInteger()
        try {
            assertTrue(owner.submitMediaCallback { calls.incrementAndGet() })
            owner.scope.cancel(CancellationException("authored private parent cancellation"))
            assertFalse(owner.submitMediaCallback { calls.incrementAndGet() })
            dispatcher.runNext()
            withTimeout(10_000) { owner.scope.coroutineContext[Job]!!.join() }
            assertEquals(0, calls.get())
            owner.close()
        } finally {
            dispatcher.runAvailable()
            owner.close()
            owner.scope.coroutineContext[Job]!!.join()
        }
    }

    @Test fun callbackFailureReportsOnlyStaticTextAndDoesNotPoisonTheNextCallbackOrClose(): Unit = runBlocking {
        val dispatcher = QueuedDispatcher()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val (owner, _) = owner(callbackDispatcher = dispatcher, reportCallbackFailure = { diagnostics.add(it) })
        val calls = AtomicInteger()
        try {
            assertTrue(owner.submitMediaCallback { throw CancellationException("authored private cancellation") })
            dispatcher.runNext()
            assertTrue(diagnostics.isEmpty(), "Ordinary cancellation must not produce a raw diagnostic")
            assertTrue(owner.submitMediaCallback {
                throw IllegalStateException("authored private source token", IllegalArgumentException("private cause")).apply {
                    addSuppressed(IllegalStateException("private suppressed value"))
                }
            })
            dispatcher.runNext()
            assertEquals(listOf("Veyra could not complete a media-control action."), diagnostics.toList())
            assertTrue(owner.submitMediaCallback { calls.incrementAndGet() })
            dispatcher.runNext()
            assertEquals(1, calls.get())
            owner.close()
        } finally {
            dispatcher.runAvailable()
            owner.close()
            owner.scope.coroutineContext[Job]!!.join()
        }
    }
}
