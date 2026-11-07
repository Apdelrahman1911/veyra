package dev.veyra.platform.background

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import dev.veyra.downloads.DownloadBackgroundDrainAck
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.VeyraException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Simulated worker ACKs exercise host races; this is not an actual OS foreground-service test. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloadExecutionGateTest {
    @Test fun acquisitionWaitsForForegroundAndAtomicIdleHandoffSchedulesSuccessor() = runBlocking {
        val scheduled = Channel<String>(Channel.UNLIMITED)
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { scheduled.send(it.id.toString()) }
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
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { }
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
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { scheduled.send(it.id.toString()) }
        val pending = async { gate.acquire {} }
        val token = withTimeout(2_000) { scheduled.receive() }
        pending.cancelAndJoin()
        assertEquals(0, gate.leaseCount.value)
        assertTrue(gate.foregroundStarted(token))
        assertTrue(gate.retireIfIdle(token))
    }

    @Test fun wakeDelegationWaitsForDurableEnqueueAckWithoutTakingALease() = runBlocking {
        val scheduled = CompletableDeferred<String>()
        val ack = CompletableDeferred<Unit>()
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { work -> scheduled.complete(work.id.toString()); ack.await() }
        val wake = async { gate.requestForegroundWork() }
        val token = withTimeout(2_000) { scheduled.await() }
        try {
            assertEquals(0, gate.leaseCount.value)
            assertFalse("Scheduling an operation is not its durable ACK", wake.isCompleted)
        } finally { ack.complete(Unit) }
        withTimeout(2_000) { wake.await() }
        assertEquals(0, gate.leaseCount.value)
        assertTrue(gate.foregroundStarted(token))
        assertTrue(gate.retireIfIdle(token))
    }

    @Test fun cancelledWakeWaiterDoesNotCancelItsAlreadySubmittedEnqueue() = runBlocking {
        val scheduled = CompletableDeferred<String>()
        val ack = CompletableDeferred<Unit>()
        var enqueues = 0
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { work ->
            enqueues++
            scheduled.complete(work.id.toString())
            ack.await()
        }
        val wake = async { gate.requestForegroundWork() }
        val token = withTimeout(2_000) { scheduled.await() }
        wake.cancelAndJoin()
        assertEquals(0, gate.leaseCount.value)
        ack.complete(Unit)
        withTimeout(2_000) { gate.requestForegroundWork() }
        assertEquals(1, enqueues)
        assertTrue(gate.foregroundStarted(token))
        assertTrue(gate.retireIfIdle(token))
    }

    @Test fun idleEpochRejectsEvenAnAcquireAndReleaseBetweenCandidateAndConfirmation() = runBlocking {
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { fail("An already foreground gate must not enqueue") }
        assertTrue(gate.foregroundStarted("worker"))
        val candidate = requireNotNull(gate.idleCandidate("worker"))
        gate.acquire {}.close()
        assertEquals(0, gate.leaseCount.value)
        assertFalse(withContext(Dispatchers.Main.immediate) { gate.retire(candidate) })
        assertTrue(gate.retireIfIdle("worker"))
        assertFalse(withContext(Dispatchers.Main.immediate) { gate.retire(candidate) })
    }

    @Test fun anUnrelatedFailedStartCannotExpireTheCurrentPendingWorker() = runBlocking {
        val scheduled = Channel<String>(Channel.UNLIMITED)
        var expirations = 0
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { scheduled.send(it.id.toString()) }
        val acquire = async { gate.acquire { expirations++ } }
        val token = withTimeout(2_000) { scheduled.receive() }
        gate.failedStart("retired-unrelated-worker")
        assertEquals(1, gate.leaseCount.value)
        assertEquals(0, expirations)
        assertFalse(acquire.isCompleted)
        assertTrue(gate.foregroundStarted(token))
        withTimeout(2_000) { acquire.await() }.close()
        assertTrue(gate.retireIfIdle(token))
    }

    @Test fun enqueueFailureDrainsOneExpirationAndRejectsWakeAck() = runBlocking {
        val scheduled = CompletableDeferred<String>()
        val failEnqueue = CompletableDeferred<Unit>()
        var expirations = 0
        val gate = AndroidDownloadExecutionGate(ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() }, isWorkUnfinished = { true }) { work ->
            scheduled.complete(work.id.toString())
            failEnqueue.await()
            error("untrusted backend diagnostics must not escape")
        }
        val acquire = async { runCatching { gate.acquire { expirations++ } } }
        val token = withTimeout(2_000) { scheduled.await() }
        val wake = async(Dispatchers.Main.immediate) { runCatching { gate.requestForegroundWork() } }
        // Put the wake on Main behind registration before releasing the injected failure.
        withContext(Dispatchers.Main.immediate) { }
        failEnqueue.complete(Unit)
        val acquisitionError = withTimeout(2_000) { acquire.await() }.exceptionOrNull() as VeyraException
        val wakeError = withTimeout(2_000) { wake.await() }.exceptionOrNull() as VeyraException
        withTimeout(2_000) { gate.awaitForegroundReady() }
        gate.failedStart(token)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, acquisitionError.failure.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, wakeError.failure.code)
        assertEquals(1, expirations)
        assertEquals(0, gate.leaseCount.value)
        assertFalse(wakeError.message.orEmpty().contains("untrusted backend"))
    }

    @Test fun cachedCancelledWorkIsReplacedByAnImmediateWakeWithoutANativeLease() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val states = ConcurrentHashMap<UUID, WorkInfo.State>()
        val reads = Channel<UUID>(Channel.UNLIMITED)
        val drains = AtomicInteger()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {},
            drainOwners = { drains.incrementAndGet(); DownloadBackgroundDrainAck() },
            isWorkUnfinished = { id ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                reads.send(id)
                states[id]?.isFinished == false
            },
        ) { work -> states[work.id] = WorkInfo.State.ENQUEUED; requests.send(work) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val cancelled = withTimeout(2_000) { requests.receive() }
        states[cancelled.id] = WorkInfo.State.CANCELLED // No doWork/failedStart callback ever ran.

        withTimeout(2_000) { gate.requestForegroundWork() }
        val successor = withTimeout(2_000) { requests.receive() }
        assertEquals(cancelled.id, withTimeout(2_000) { reads.receive() })
        assertNotEquals(cancelled.id, successor.id)
        assertEquals(0L, successor.workSpec.initialDelay)
        assertEquals(0, gate.leaseCount.value)
        assertEquals("An obsolete enqueue ACK is not a real foreground owner to revoke", 0, drains.get())
        assertTrue(requests.tryReceive().isFailure)
        assertTrue(gate.foregroundStarted(successor.id.toString()))
        assertTrue(gate.retireIfIdle(successor.id.toString()))
    }

    @Test fun acquisitionRefreshesAMissingCachedWorkerBeforeWaitingForRealForeground() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val states = ConcurrentHashMap<UUID, WorkInfo.State>()
        val expirations = AtomicInteger()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() },
            isWorkUnfinished = { states[it]?.isFinished == false },
        ) { work -> states[work.id] = WorkInfo.State.ENQUEUED; requests.send(work) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val missing = withTimeout(2_000) { requests.receive() }
        states.remove(missing.id)
        val acquire = async { gate.acquire { expirations.incrementAndGet(); Unit } }
        val successor = withTimeout(2_000) { requests.receive() }
        try {
            assertNotEquals(missing.id, successor.id)
            assertEquals(0L, successor.workSpec.initialDelay)
            assertEquals(1, gate.leaseCount.value)
            assertFalse("Enqueue/state-read success must not manufacture setForeground", acquire.isCompleted)
            gate.failedStart(missing.id.toString())
            assertEquals(0, expirations.get())
            assertTrue(gate.foregroundStarted(successor.id.toString()))
            withTimeout(2_000) { acquire.await() }.close()
            assertTrue(gate.retireIfIdle(successor.id.toString()))
        } finally { acquire.cancelAndJoin() }
    }

    @Test fun delayedTerminalStateReadCannotReplaceARealForegroundAcknowledgement() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val reading = CompletableDeferred<UUID>()
        val finishRead = CompletableDeferred<Unit>()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() },
            isWorkUnfinished = { reading.complete(it); finishRead.await(); false },
        ) { requests.send(it) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val original = withTimeout(2_000) { requests.receive() }
        val wake = async { gate.requestForegroundWork() }
        try {
            assertEquals(original.id, withTimeout(2_000) { reading.await() })
            assertTrue(gate.foregroundStarted(original.id.toString()))
            finishRead.complete(Unit)
            withTimeout(2_000) { wake.await() }
            assertTrue("Old query must not schedule over an acknowledged foreground worker", requests.tryReceive().isFailure)
            val live = gate.acquire {}
            assertEquals(1, gate.leaseCount.value)
            live.close()
            assertTrue(gate.retireIfIdle(original.id.toString()))
        } finally { finishRead.complete(Unit); wake.cancelAndJoin() }
    }

    @Test fun delayedOldStateReadCannotClearAConcurrentSuccessorRequestEpoch() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val old = AtomicReference<UUID>()
        val oldReads = AtomicInteger()
        val reading = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() },
            isWorkUnfinished = { id ->
                if (id == old.get()) {
                    if (oldReads.incrementAndGet() == 1) { reading.complete(Unit); finishRead.await() }
                    false
                } else true
            },
        ) { requests.send(it) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        old.set(withTimeout(2_000) { requests.receive() }.id)
        val firstWake = async { gate.requestForegroundWork() }
        try {
            withTimeout(2_000) { reading.await() }
            // The second fresh read observes terminal state and submits the immediate successor.
            withTimeout(2_000) { gate.requestForegroundWork() }
            val successor = withTimeout(2_000) { requests.receive() }
            assertNotEquals(old.get(), successor.id)
            finishRead.complete(Unit)
            withTimeout(2_000) { firstWake.await() }
            assertEquals(2, oldReads.get())
            assertTrue("Late old-ID response must neither clear nor duplicate the new request", requests.tryReceive().isFailure)
            assertTrue(gate.foregroundStarted(successor.id.toString()))
            assertTrue(gate.retireIfIdle(successor.id.toString()))
        } finally { finishRead.complete(Unit); firstWake.cancelAndJoin() }
    }

    @Test fun unknownWorkInfoReadIsTypedFailureNotMissingWorkOrAnAliveAcknowledgement() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val failRead = AtomicBoolean(true)
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() },
            isWorkUnfinished = {
                if (failRead.get()) error("private backend diagnostics")
                true
            },
        ) { requests.send(it) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val original = withTimeout(2_000) { requests.receive() }
        val failure = withTimeout(2_000) { runCatching { gate.requestForegroundWork() }.exceptionOrNull() } as VeyraException
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
        assertEquals("download_worker_state_failed", failure.failure.detail)
        assertFalse(failure.message.orEmpty().contains("private backend"))
        assertTrue(requests.tryReceive().isFailure)
        assertEquals(0, gate.leaseCount.value)
        failRead.set(false)
        withTimeout(2_000) { gate.requestForegroundWork() }
        assertTrue("A read failure must not have discarded the exact known request", requests.tryReceive().isFailure)
        assertTrue(gate.foregroundStarted(original.id.toString()))
        assertTrue(gate.retireIfIdle(original.id.toString()))
    }

    @Test fun boundedWorkInfoReadFailureReleasesAcquisitionWithoutInventingAReplacement() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, drainOwners = { DownloadBackgroundDrainAck() },
            isWorkUnfinished = { awaitCancellation() }, stateReadTimeoutMs = 100,
        ) { requests.send(it) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val original = withTimeout(2_000) { requests.receive() }
        val failure = withTimeout(2_000) { runCatching { gate.acquire {} }.exceptionOrNull() } as VeyraException
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.failure.code)
        assertEquals("download_worker_state_timeout", failure.failure.detail)
        assertEquals(0, gate.leaseCount.value)
        assertTrue(requests.tryReceive().isFailure)
        assertTrue(gate.foregroundStarted(original.id.toString()))
        assertTrue(gate.retireIfIdle(original.id.toString()))
    }

    @Test fun cancelledWorkerAndForegroundReentryWaitForOwnerUnwindAfterNativeLeaseClose() = runBlocking {
        val ownerStarted = CompletableDeferred<Unit>()
        val closingNative = CompletableDeferred<Unit>()
        val closeNative = CompletableDeferred<Unit>()
        val enteredDrain = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        val drains = AtomicInteger()
        lateinit var owner: Job
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = {
                drains.incrementAndGet(); enteredDrain.complete(Unit)
                owner.join() // Independent owner drain, not the cancel-only expiration callback.
                DownloadBackgroundDrainAck()
            },
        ) { fail("An acknowledged foreground owner does not enqueue") }
        assertTrue(gate.foregroundStarted("old-worker"))
        val lease = gate.acquire { callbacks.incrementAndGet(); owner.cancel() }
        owner = launch(Dispatchers.Default) {
            ownerStarted.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) {
                lease.close() // Real coordinator can close allowance before final Room/file unwind.
                closingNative.complete(Unit)
                closeNative.await()
            } }
        }
        withTimeout(2_000) { ownerStarted.await() }
        val ended = async { gate.foregroundEnded("old-worker") }
        val duplicateEnd = async { enteredDrain.await(); gate.foregroundEnded("old-worker") }
        val ready = async { enteredDrain.await(); gate.awaitForegroundReady() }
        val replacement = async { enteredDrain.await(); gate.foregroundStarted("replacement") }
        try {
            withTimeout(2_000) { closingNative.await(); enteredDrain.await() }
            assertEquals(0, gate.leaseCount.value)
            assertFalse(ended.isCompleted)
            assertFalse(duplicateEnd.isCompleted)
            assertFalse(ready.isCompleted)
            assertFalse(replacement.isCompleted)
            val rejected = runCatching { gate.acquire {} }.exceptionOrNull() as VeyraException
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, rejected.failure.code)
            ended.cancel() // Cancellation of the worker caller must not skip the accepted drain.
            assertNull(withTimeoutOrNull(100) { ended.join(); true })
            assertFalse(owner.isCompleted)
            closeNative.complete(Unit)
            withTimeout(2_000) { ended.join(); duplicateEnd.await(); ready.await(); assertTrue(replacement.await()) }
            assertTrue(owner.isCompleted)
            assertEquals(1, callbacks.get())
            assertEquals(1, drains.get())
            gate.acquire {}.close()
            assertTrue(gate.retireIfIdle("replacement"))
        } finally {
            closeNative.complete(Unit)
            owner.cancelAndJoin(); ended.cancelAndJoin(); duplicateEnd.cancelAndJoin()
            ready.cancelAndJoin(); replacement.cancelAndJoin()
        }
    }

    @Test fun zeroNativeLeasesStillNeedAnExplicitDrainAckAndKnownCheckpointFailureDoesNotHideIt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val drains = AtomicInteger()
        val checkpoint = AppFailure(FailureCode.STORAGE_IO, "authored_checkpoint_failure")
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = {
                drains.incrementAndGet(); entered.complete(Unit); finished.await()
                DownloadBackgroundDrainAck(checkpoint) // Explicit post-join error, NOT an unknown throw.
            },
        ) { fail("A new foreground worker is supplied explicitly by this fake test") }
        assertTrue(gate.foregroundStarted("pending-shared-claim"))
        val ended = async { gate.foregroundEnded("pending-shared-claim") }
        val replacement = async { entered.await(); gate.foregroundStarted("next-worker") }
        try {
            withTimeout(2_000) { entered.await() }
            assertEquals(0, gate.leaseCount.value)
            assertFalse(ended.isCompleted)
            assertFalse(replacement.isCompleted)
            assertNull(withTimeoutOrNull(100) { gate.awaitForegroundReady(); true })
            finished.complete(Unit)
            withTimeout(2_000) { ended.await(); assertTrue(replacement.await()) }
            assertEquals(1, drains.get())
            assertTrue(gate.retireIfIdle("next-worker"))
            gate.foregroundEnded("pending-shared-claim")
            assertEquals("An old worker must not repeat a completed drain", 1, drains.get())
        } finally { finished.complete(Unit); ended.cancelAndJoin(); replacement.cancelAndJoin() }
    }

    @Test fun queuedFailedStartDrainsRegisteredOwnersEvenWithoutAnyNativeLease() = runBlocking {
        val requests = Channel<OneTimeWorkRequest>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = { entered.complete(Unit); finished.await(); DownloadBackgroundDrainAck() },
        ) { requests.send(it) }
        withTimeout(2_000) { gate.requestForegroundWork() }
        val failed = withTimeout(2_000) { requests.receive() }
        val failureOwner = async { gate.failedStart(failed.id.toString()) }
        try {
            withTimeout(2_000) { entered.await() }
            assertEquals(0, gate.leaseCount.value)
            assertFalse(failureOwner.isCompleted)
            assertNull(withTimeoutOrNull(100) { gate.awaitForegroundReady(); true })
            finished.complete(Unit)
            withTimeout(2_000) { failureOwner.await(); gate.requestForegroundWork() }
            val successor = withTimeout(2_000) { requests.receive() }
            assertNotEquals(failed.id, successor.id)
            assertTrue(gate.foregroundStarted(successor.id.toString()))
            assertTrue(gate.retireIfIdle(successor.id.toString()))
        } finally { finished.complete(Unit); failureOwner.cancelAndJoin() }
    }

    @Test fun unknownDrainExceptionKeepsAStickyTypedBarrierEvenAfterTheLastLeaseCloses() = runBlocking {
        val drains = AtomicInteger()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = { drains.incrementAndGet(); error("private native drain diagnostics") },
        ) { fail("Failed drain must not admit another work request") }
        assertTrue(gate.foregroundStarted("undrained"))
        val lease = gate.acquire {}
        val failure = withTimeout(2_000) { runCatching { gate.foregroundEnded("undrained") }.exceptionOrNull() } as VeyraException
        assertEquals("download_owner_drain_failed_restart_required", failure.failure.detail)
        assertFalse(failure.message.orEmpty().contains("private native"))
        lease.close()
        assertEquals(0, gate.leaseCount.value)
        for (operation in listOf<suspend () -> Unit>(
            { gate.awaitForegroundReady() }, { gate.acquire {}.close() },
            { gate.foregroundStarted("must-not-start"); Unit }, { gate.foregroundEnded("undrained") },
        )) {
            val rejected = withTimeout(2_000) { runCatching { operation() }.exceptionOrNull() } as VeyraException
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, rejected.failure.code)
            assertEquals("download_owner_drain_failed_restart_required", rejected.failure.detail)
        }
        assertEquals("A failed acknowledgement must not silently retry an unsafe drain", 1, drains.get())
    }

    @Test fun anExplicitCancellationThrownByDrainIsNotAnAcknowledgement() = runBlocking {
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = { throw CancellationException("authored unknown incomplete drain") },
        ) { fail("An incomplete drain must not enqueue") }
        assertTrue(gate.foregroundStarted("cancelled-drain"))
        val failure = withTimeout(2_000) { runCatching { gate.foregroundEnded("cancelled-drain") }.exceptionOrNull() } as VeyraException
        assertEquals("download_owner_drain_failed_restart_required", failure.failure.detail)
        assertEquals(0, gate.leaseCount.value)
        val reentry = withTimeout(2_000) { runCatching { gate.foregroundStarted("new-worker") }.exceptionOrNull() } as VeyraException
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, reentry.failure.code)
    }

    @Test fun unknownExpirationCallbackStillAttemptsIndependentJoinBeforeStickyFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val drains = AtomicInteger()
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = { drains.incrementAndGet(); entered.complete(Unit); joined.await(); DownloadBackgroundDrainAck() },
        ) { fail("Unknown old owner failure cannot enqueue a new worker") }
        assertTrue(gate.foregroundStarted("callback-error"))
        val lease = gate.acquire { throw AssertionError("authored private callback diagnostics") }
        val ended = async { runCatching { gate.foregroundEnded("callback-error") } }
        try {
            withTimeout(2_000) { entered.await() }
            assertFalse("Unknown callback error must not skip the actual independent drain", ended.isCompleted)
            assertNull(withTimeoutOrNull(100) { gate.awaitForegroundReady(); true })
            joined.complete(Unit)
            val failure = withTimeout(2_000) { ended.await() }.exceptionOrNull() as VeyraException
            assertEquals("download_owner_drain_failed_restart_required", failure.failure.detail)
            assertFalse(failure.message.orEmpty().contains("private callback"))
            lease.close()
            assertEquals(0, gate.leaseCount.value)
            val reentry = runCatching { gate.foregroundStarted("must-not-start") }.exceptionOrNull() as VeyraException
            assertEquals(FailureCode.BACKGROUND_RESTRICTED, reentry.failure.code)
            assertEquals(1, drains.get())
        } finally { joined.complete(Unit); lease.close(); ended.cancelAndJoin() }
    }

    @Test fun unknownDrainErrorDoesNotLeaveAnUncompletedForegroundBarrierWait() = runBlocking {
        val gate = AndroidDownloadExecutionGate(
            ensureCoverage = {}, isWorkUnfinished = { true },
            drainOwners = { throw AssertionError("authored private checkpoint diagnostics") },
        ) { fail("Failed drain cannot enqueue") }
        assertTrue(gate.foregroundStarted("drain-error"))
        val failed = runCatching { gate.foregroundEnded("drain-error") }.exceptionOrNull() as VeyraException
        assertEquals("download_owner_drain_failed_restart_required", failed.failure.detail)
        val readyFailure = withTimeout(2_000) { runCatching { gate.awaitForegroundReady() }.exceptionOrNull() } as VeyraException
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, readyFailure.failure.code)
        assertFalse(readyFailure.message.orEmpty().contains("private checkpoint"))
    }
}
