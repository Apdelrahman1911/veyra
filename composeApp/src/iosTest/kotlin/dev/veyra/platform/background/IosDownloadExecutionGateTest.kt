@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.platform.background

import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import dev.veyra.platform.runIosMainContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.UIKit.UIBackgroundTaskIdentifier

/** Synthetic task deadlines test lifecycle logic, not Apple's actual background time allowance. */
class IosDownloadExecutionGateTest {
    @Test fun staleExpirationCannotEndSuccessorAndResumeWaitsForCheckpoint() = runIosMainContract {
        val host = FakeHost()
        val gate = IosDownloadExecutionGate(this, host)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var callbacks = 0
        try {
            gate.acquire { callbacks++; entered.complete(Unit); finish.await() }
            val old = host.latest
            gate.didEnterBackground()
            host.expire(old)
            withTimeout(2_000) { entered.await() }
            assertEquals(listOf(old), host.ended)
            gate.didBecomeActive()
            val resuming = async { gate.awaitForegroundReady() }
            delay(50)
            assertFalse(resuming.isCompleted)
            try { gate.acquire {}; fail("An unfinished checkpoint allowed a new lease") }
            catch (failure: VeyraException) { assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code) }
            finish.complete(Unit)
            assertTrue(withTimeout(2_000) { resuming.await() })
            val replacement = gate.acquire { fail("A stale task expired its successor") }
            val next = host.latest
            assertTrue(old != next)
            host.expire(old)
            delay(50)
            assertEquals(1, callbacks)
            assertFalse(next in host.ended)
            replacement.close(); replacement.close()
            assertEquals(listOf(old, next), host.ended)
        } finally { finish.complete(Unit); withContext(NonCancellable) { gate.close() } }
    }

    @Test fun proactiveDeadlineEndsUIKitTaskEvenWhenCheckpointIsBlocked() = runIosMainContract {
        val host = FakeHost().apply { remaining = 5.0 }
        val gate = IosDownloadExecutionGate(this, host)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        try {
            gate.acquire { entered.complete(Unit); finish.await() }
            val task = host.latest
            gate.didEnterBackground()
            withTimeout(2_000) { entered.await() }
            delay(2_200)
            assertEquals(listOf(task), host.ended)
            try { gate.acquire {}; fail("An exhausted background budget renewed per file") }
            catch (failure: VeyraException) { assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code) }
        } finally { finish.complete(Unit); withContext(NonCancellable) { gate.close() } }
        assertEquals(1, host.ended.size)
    }

    @Test fun cancellationAtMainReturnEdgeClosesNewlyCreatedLease() = runIosMainContract {
        val host = FakeHost()
        val gate = IosDownloadExecutionGate(this, host)
        lateinit var acquiring: Job
        host.onBegin = { acquiring.cancel() }
        try {
            acquiring = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { gate.acquire {} }
            acquiring.start()
            withTimeout(2_000) { acquiring.join() }
            assertEquals(listOf(host.latest), host.ended)
            val created = host.latest
            gate.didEnterBackground()
            assertEquals(created, host.latest) // No leaked active lease needs another task.
        } finally { gate.close() }
    }

    @Test fun deniedAndInlineExpiredAllowancesNeverAdmitTransfer() = runIosMainContract {
        val host = FakeHost().apply { rejectBegin = true }
        val gate = IosDownloadExecutionGate(this, host)
        try {
            try { gate.acquire {}; fail("A rejected native allowance admitted work") }
            catch (failure: VeyraException) { assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code) }
            host.rejectBegin = false
            host.expireInline = true
            gate.didBecomeActive()
            assertTrue(gate.awaitForegroundReady())
            try { gate.acquire {}; fail("An inline-expired allowance admitted work") }
            catch (failure: VeyraException) { assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code) }
            assertEquals(listOf(host.latest), host.ended)
        } finally { gate.close() }
        assertEquals(1, host.ended.size)
    }

    private class FakeHost : IosBackgroundTaskHost {
        var remaining = 100.0
        var latest: UIBackgroundTaskIdentifier = 0uL
        var onBegin: () -> Unit = {}
        var rejectBegin = false
        var expireInline = false
        val ended = mutableListOf<UIBackgroundTaskIdentifier>()
        private val handlers = mutableMapOf<UIBackgroundTaskIdentifier, () -> Unit>()
        override val backgroundTimeRemaining get() = remaining
        override fun begin(expiration: () -> Unit): UIBackgroundTaskIdentifier {
            check(!rejectBegin) { "Synthetic unavailable background host" }
            latest++
            handlers[latest] = expiration
            onBegin()
            if (expireInline) expiration()
            return latest
        }
        override fun end(identifier: UIBackgroundTaskIdentifier) { ended += identifier }
        fun expire(identifier: UIBackgroundTaskIdentifier) { handlers.getValue(identifier)() }
    }
}
