package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** OS allowance contracts exercised with REAL Room + files and controlled callbacks, not an OS/device claim. */
class DownloadAllowanceRoomTest {
    @Test fun deniedAllowancePersistsTypedNeedsForegroundAndReleasesPreTransferClaim() = downloadCase {
        val first = store()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease =
                throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_os_denial"))
        }
        val a = coordinator(first, gateway, gate = gate)
        val id = a.enqueue(item())
        a.start()
        val durable = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.NEEDS_FOREGROUND && r.leaseOwner == null } == true }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.downloads[id]?.failure?.code)
        assertNull(durable.downloads[id]?.activeAttemptId)
        assertEquals(1, durable.downloadAttempts.size)
        assertTrue(durable.downloadAttempts.values.all { it.finishedEpochMs != null })
        gateway.assertUnused()
    }

    @Test fun inlineAcquireExpirationDoesNotSelfJoinAndClosesAllowanceExactlyOnce() = downloadCase {
        val first = store()
        val seeded = seedPending(first, bound = true, bytes = authoredBytes)
        val closes = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                onExpiration()
                return DownloadExecutionLease { closes.incrementAndGet() }
            }
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        val durable = eventually(first) { it.downloads[seeded.record.id]?.let { r -> r.status == DownloadStatus.NEEDS_FOREGROUND && r.leaseOwner == null } == true }
        assertEquals(1, closes.get())
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.downloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.binding.id, durable.downloads[seeded.record.id]?.executionBindingId)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.stagingPath!!)))
        gateway.assertUnused()
    }

    @Test fun checkpointFailureCannotPreventInlineExpirationFromCancellingAcquisition() = downloadCase {
        val fault = persistence()
        val failOnce = AtomicBoolean(true)
        fault.afterTransform = { before, next ->
            if (next.downloads.values.any { it.status == DownloadStatus.NEEDS_FOREGROUND } &&
                before.downloads.values.none { it.status == DownloadStatus.NEEDS_FOREGROUND } && failOnce.compareAndSet(true, false))
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_expiry_checkpoint_failure"))
        }
        val first = store(fault)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val closes = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                onExpiration()
                return DownloadExecutionLease { closes.incrementAndGet() }
            }
        }
        val a = coordinator(first, gateway, gate = gate)
        val id = a.enqueue(item()); a.start()
        val durable = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.NEEDS_FOREGROUND && r.leaseOwner == null } == true }
        assertFalse(failOnce.get(), "The real SQLite checkpoint transaction must have been faulted")
        assertEquals(FailureCode.STORAGE_IO, a.lastFailure.value?.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.downloads[id]?.failure?.code)
        assertEquals(1, closes.get())
        gateway.assertUnused()
    }

    @Test fun expirationInsideNoncancellableClosePreservesResumableIdentityWithoutSelfJoin() = downloadCase {
        val first = store()
        val closes = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease = DownloadExecutionLease {
                withContext(NonCancellable) { onExpiration(); closes.incrementAndGet() }
            }
        }
        val gateway = DownloadTestGateway().apply {
            transfer = { call -> call.write(); throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE)) }
        }
        val a = coordinator(first, gateway, gate = gate)
        val id = a.enqueue(item()); a.start()
        val durable = eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.NEEDS_FOREGROUND && r.leaseOwner == null } == true }
        val record = durable.downloads.getValue(id)
        assertEquals(1, closes.get())
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, record.failure?.code)
        assertNotNull(record.executionBindingId)
        assertEquals(authoredRepresentation, record.representationId)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(record.stagingPath!!)))
    }

    @Test fun callbackQueuedBeforeCloseCannotSuspendANewerAttempt() = downloadCase {
        val first = store()
        val callbacks = CopyOnWriteArrayList<suspend () -> Unit>()
        val closes = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                callbacks += onExpiration
                return DownloadExecutionLease { closes.incrementAndGet() }
            }
        }
        val holdNew = barrier()
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway().apply {
            transfer = { call ->
                if (calls.incrementAndGet() == 1) throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE))
                holdNew.stop(); call.complete()
            }
        }
        val a = coordinator(first, gateway, gate = gate)
        val id = a.enqueue(item()); a.start()
        eventually(first) { it.downloads[id]?.let { r -> r.status == DownloadStatus.WAITING_NETWORK && r.leaseOwner == null } == true }
        val expired = callbacks.single()
        assertEquals(1, closes.get())
        a.resume(id)
        holdNew.entered.await()
        val current = first.flush().getOrThrow().downloads.getValue(id)
        expired()
        delay(160)
        val after = first.flush().getOrThrow().downloads.getValue(id)
        assertEquals(DownloadStatus.RUNNING, after.status)
        assertEquals(current.activeAttemptId, after.activeAttemptId)
        assertEquals(current.leaseOwner, after.leaseOwner)
        holdNew.release()
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        eventually(first) { closes.get() == 2 }
    }

    @Test fun foregroundResumeDuringOldUnwindRejectsItsOldEpochCallbackAndEventuallyReschedules() = downloadCase {
        val first = store()
        val callbacks = CopyOnWriteArrayList<suspend () -> Unit>()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                callbacks += onExpiration
                return DownloadExecutionLease { }
            }
        }
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway().apply {
            transfer = { call ->
                if (calls.incrementAndGet() == 1) {
                    call.write(); entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { unwinding.stop() } }
                } else call.complete()
            }
        }
        val a = coordinator(first, gateway, gate = gate)
        val id = a.enqueue(item()); a.start(); entered.await()
        coroutineScope {
            val pause = async { a.pauseForBackground() }
            unwinding.entered.await()
            a.resumePending()
            val oldExpiration: suspend () -> Unit = callbacks[0]
            oldExpiration() // queued callback belongs to the OLD foreground epoch
            unwinding.release()
            pause.await()
        }
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(2, gateway.transfers.size)
        assertEquals(1, gateway.queries.size)
    }

    @Test fun idempotentForegroundPermissionAnnouncementDoesNotRevokeActiveWork() = downloadCase {
        val first = store()
        val transfer = barrier()
        val gateway = DownloadTestGateway().apply { this.transfer = { call -> transfer.stop(); call.complete() } }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start(); transfer.entered.await()
        val before = first.flush().getOrThrow().downloads.getValue(id)
        a.resumePending()
        delay(160)
        val after = first.flush().getOrThrow().downloads.getValue(id)
        assertEquals(DownloadStatus.RUNNING, after.status)
        assertEquals(before.activeAttemptId, after.activeAttemptId)
        transfer.release()
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.transfers.size)
    }
}
