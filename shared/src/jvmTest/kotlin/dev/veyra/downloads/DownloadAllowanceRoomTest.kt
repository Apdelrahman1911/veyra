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
    @Test fun deniedAllowancePersistsTypedNeedsForegroundAndReleasesPreTransferClaim() = executorSchema3Case {
        // EX03: first selection is legitimate setup; denied EXECUTION cannot add any source work.
        val owner = owner()
        val (id, selected) = enqueue(owner)
        owner.gate.beforeAcquire = { throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_os_denial")) }
        val firstSelections = owner.selector.requests.toList()
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val durable = awaitJob(owner, id) { it.job.status == DownloadStatus.NEEDS_FOREGROUND && it.job.leaseOwner == null }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.job.failure?.code)
        assertNull(durable.activeAttempt)
        assertEquals(1, durable.attempts.size)
        assertTrue(durable.attempts.all { it.finishedEpochMs != null })
        assertEquals(selected.context, durable.producerContext)
        assertEquals(firstSelections, owner.selector.requests)
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        owner.legacy.assertUnused()
    }

    @Test fun inlineAcquireExpirationDoesNotSelfJoinAndClosesAllowanceExactlyOnce() = executorSchema3Case {
        // EX07: create retained bytes through a real typed transfer/pause, never a snapshot seed.
        val setup = owner()
        val entered = CompletableDeferred<Unit>()
        setup.gateway.transfer = { call -> call.write(); entered.complete(Unit); awaitCancellation() }
        val (id, selected) = enqueue(setup)
        setup.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        setup.coordinator.pause(id)
        val retained = join(setup, id)
        val path = assertNotNull(retained.job.stagingPath)
        assertEquals(DownloadStatus.PAUSED, retained.job.status)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)

        val owner = owner(setup.store)
        owner.gate.beforeAcquire = { expiration -> expiration() }
        owner.coordinator.resume(id)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val durable = awaitJob(owner, id) { it.job.status == DownloadStatus.NEEDS_FOREGROUND && it.job.leaseOwner == null }
        assertEquals(1, owner.gate.acquires.get())
        assertEquals(1, owner.gate.closes.get())
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.job.failure?.code)
        assertEquals(retained.job.producer, durable.job.producer)
        assertEquals(selected.context, durable.producerContext)
        assertEquals(retained.job.representationId, durable.job.representationId)
        assertEquals(path, durable.job.stagingPath)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(path)))
        assertEquals(1, setup.selector.requests.size)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        owner.legacy.assertUnused()
    }

    @Test fun checkpointFailureCannotPreventInlineExpirationFromCancellingAcquisition() = executorSchema3Case {
        // EX02: schema3 writes finish AFTER actual drain. A rolled-back finish is not a cleared lease.
        val owner = owner()
        val (id, selected) = enqueue(owner)
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_expiry_checkpoint_failure")
        owner.expectedCloseFailure = failure
        val failOnce = AtomicBoolean(true)
        faults(owner.store).beforeCommit = { before, after ->
            if (before.state().physicalDownloads[id]?.leaseOwner != null &&
                after.state().physicalDownloads[id]?.status == DownloadStatus.NEEDS_FOREGROUND &&
                failOnce.compareAndSet(true, false)) throw VeyraException(failure)
        }
        owner.gate.beforeAcquire = { expiration -> expiration() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { !failOnce.get() }
        assertEquals(failure, owner.coordinator.drainForBackground().checkpointFailure)
        val unconfirmed = join(owner, id)
        assertEquals(selected.context, unconfirmed.producerContext)
        assertNotNull(unconfirmed.job.leaseOwner, "Pre-COMMIT failure must not fabricate durable finish")
        assertNotNull(unconfirmed.activeAttempt)
        assertNull(unconfirmed.activeAttempt?.finishedEpochMs)
        assertEquals(1, owner.repository.finishCalls.size, "No automatic retry may erase the unconfirmed finish")
        assertEquals(1, owner.gate.closes.get())
        assertNotNull(files.tryLeasePaths(listOf(assertNotNull(unconfirmed.job.stagingPath)))).close()
        assertNotNull(files.tryTransferSlot(1)).close()
        assertEquals(1, owner.selector.requests.size)
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertEquals(failure, owner.coordinator.lastFailure.value)
    }

    @Test fun expirationInsideNoncancellableClosePreservesResumableIdentityWithoutSelfJoin() = executorSchema3Case {
        // EX04: the actual allowance, not a removed PlaybackResource seam, calls expiration inline.
        val owner = owner()
        owner.gateway.transfer = { call -> call.write(); throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE)) }
        owner.gate.beforeClose = {
            val expiration: suspend () -> Unit = owner.gate.expirations.single()
            withContext(NonCancellable) { expiration() }
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val durable = awaitJob(owner, id) { it.job.status == DownloadStatus.NEEDS_FOREGROUND && it.job.leaseOwner == null }
        assertEquals(1, owner.gate.closes.get())
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, durable.job.failure?.code)
        assertEquals(selected.context, durable.producerContext)
        assertEquals(authoredRepresentation, durable.job.representationId)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(assertNotNull(durable.job.stagingPath))))
        assertEquals(1, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.size)
    }

    @Test fun callbackQueuedBeforeCloseCannotSuspendANewerAttempt() = executorSchema3Case {
        // EX01: new attempt, same immutable first receipt/stage; old callback cannot revoke it.
        val owner = owner()
        val holdNew = barrier()
        val calls = AtomicInteger()
        owner.gateway.transfer = { call ->
            if (calls.incrementAndGet() == 1) throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE))
            holdNew.stop(); call.complete()
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val first = awaitJob(owner, id) { it.job.status == DownloadStatus.WAITING_NETWORK && it.job.leaseOwner == null }
        val expired: suspend () -> Unit = owner.gate.expirations.single()
        assertEquals(1, owner.gate.closes.get())
        owner.coordinator.resume(id)
        holdNew.entered.await()
        val current = join(owner, id)
        assertNotEquals(first.attempts.single().id, current.job.activeAttemptId)
        assertEquals(first.job.stagingPath, current.job.stagingPath)
        expired()
        delay(160)
        val after = join(owner, id)
        assertEquals(DownloadStatus.RUNNING, after.job.status)
        assertEquals(current.job.activeAttemptId, after.job.activeAttemptId)
        assertEquals(current.job.leaseOwner, after.job.leaseOwner)
        assertEquals(current.job.ownershipGeneration, after.job.ownershipGeneration)
        assertEquals(selected.context, after.producerContext)
        holdNew.release()
        complete(owner, id)
        awaitCondition { owner.gate.closes.get() == 2 }
        assertEquals(1, owner.selector.requests.size)
        assertEquals(2, owner.gateway.transfers.size)
        assertEquals(1, owner.gateway.transfers.map { it.context }.distinct().size)
    }

    @Test fun foregroundResumeDuringOldUnwindRejectsItsOldEpochCallbackAndEventuallyReschedules() = executorSchema3Case {
        // EX05 REQUIRED PRODUCTION HOLD: one foreground announcement must survive late old finish.
        val owner = owner()
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val calls = AtomicInteger()
        owner.gateway.transfer = { call ->
            if (calls.incrementAndGet() == 1) {
                call.write(); entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { unwinding.stop() } }
            } else call.complete()
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        val first = join(owner, id)
        coroutineScope {
            val oldExpiration: suspend () -> Unit = owner.gate.expirations[0]
            val expiration = async { oldExpiration() }
            try {
                unwinding.entered.await()
                expiration.await() // The callback cancels; only the independent owner path joins.
                owner.coordinator.resumePending()
                oldExpiration()
                assertEquals(first.job.leaseOwner, join(owner, id).job.leaseOwner)
                assertEquals(1, owner.gateway.transfers.size, "No replacement before positive old physical unwind")
            } finally { unwinding.release() }
        }
        val completed = complete(owner, id) // Do NOT add a second resume or a fixture status reset.
        assertEquals(selected.context, completed.producerContext)
        assertEquals(2, owner.gateway.transfers.size)
        assertEquals(1, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.map { it.context }.distinct().size)
    }

    @Test fun idempotentForegroundPermissionAnnouncementDoesNotRevokeActiveWork() = executorSchema3Case {
        // EX06: unchanged host permission cannot revoke an already admitted canonical owner.
        val owner = owner()
        val transfer = barrier()
        owner.gateway.transfer = { call -> transfer.stop(); call.complete() }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        transfer.entered.await()
        val before = join(owner, id)
        owner.coordinator.resumePending()
        delay(160)
        val after = join(owner, id)
        assertEquals(DownloadStatus.RUNNING, after.job.status)
        assertEquals(before.job.activeAttemptId, after.job.activeAttemptId)
        assertEquals(before.job.leaseOwner, after.job.leaseOwner)
        assertEquals(before.job.ownershipGeneration, after.job.ownershipGeneration)
        assertEquals(selected.context, after.producerContext)
        transfer.release()
        complete(owner, id)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.selector.requests.size)
    }

}
