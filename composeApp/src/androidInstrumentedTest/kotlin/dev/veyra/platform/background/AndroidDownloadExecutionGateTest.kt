package dev.veyra.platform.background

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Simulated worker ACKs exercise host races; this is not an actual OS foreground-service test. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloadExecutionGateTest {
    @Test fun acquisitionWaitsForForegroundAndAtomicIdleHandoffSchedulesSuccessor() = runBlocking {
        val scheduled = Channel<String>(Channel.UNLIMITED)
        val gate = AndroidDownloadExecutionGate { scheduled.send(it.id.toString()) }
        val first = async { gate.acquire {} }
        val token = withTimeout(2_000) { scheduled.receive() }
        assertFalse(first.isCompleted)
        assertEquals(1, gate.leaseCount.value)
        assertTrue(gate.foregroundStarted(token))
        val lease = withTimeout(2_000) { first.await() }
        assertFalse(gate.retireIfIdle(token))
        assertFalse(gate.foregroundStarted("another-worker"))
        lease.close(); lease.close()
        assertTrue(gate.retireIfIdle(token))
        val second = async { gate.acquire {} }
        val successor = withTimeout(2_000) { scheduled.receive() }
        assertNotEquals(token, successor)
        gate.foregroundEnded(token) // A retired old worker cannot expire successor leases.
        assertFalse(second.isCompleted)
        assertTrue(gate.foregroundStarted(successor))
        withTimeout(2_000) { second.await() }.close()
        assertTrue(gate.retireIfIdle(successor))
    }

    @Test fun foregroundResumeWaitsForOldExpirationAndCallbacksAreNotDuplicated() = runBlocking {
        val gate = AndroidDownloadExecutionGate { }
        gate.foregroundStarted("old-worker")
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var callbacks = 0
        val lease = gate.acquire { callbacks++; entered.complete(Unit); finish.await() }
        val ended = async { gate.foregroundEnded("old-worker") }
        val resume = async { entered.await(); gate.awaitForegroundReady() }
        try {
            withTimeout(2_000) { entered.await() }
            delay(50)
            assertFalse(resume.isCompleted)
            try { gate.acquire {}; fail("New allowance raced an unfinished expiration") }
            catch (error: VeyraException) { assertEquals(FailureCode.BACKGROUND_RESTRICTED, error.failure.code) }
        } finally { finish.complete(Unit) }
        withTimeout(2_000) { ended.await(); resume.await() }
        lease.close()
        gate.foregroundEnded("old-worker")
        assertEquals(1, callbacks)
        assertEquals(0, gate.leaseCount.value)
        assertTrue(gate.foregroundStarted("new-worker"))
        val replacement = gate.acquire { fail("Stale worker expired a new lease") }
        gate.foregroundEnded("old-worker")
        replacement.close()
        assertTrue(gate.retireIfIdle("new-worker"))
    }

    @Test fun cancelledWaitingAcquisitionDoesNotLeakALease() = runBlocking {
        val scheduled = Channel<String>(Channel.UNLIMITED)
        val gate = AndroidDownloadExecutionGate { scheduled.send(it.id.toString()) }
        val pending = async { gate.acquire {} }
        val token = withTimeout(2_000) { scheduled.receive() }
        pending.cancelAndJoin()
        assertEquals(0, gate.leaseCount.value)
        assertTrue(gate.foregroundStarted(token))
        assertTrue(gate.retireIfIdle(token))
    }
}
