package dev.veyra.downloads

import androidx.sqlite.execSQL
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.Clock
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.ForwardingFileSystem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Real SQLite/owned files, authored NON-AUDIO bytes; fake clock/source/validator/native gate. */
class DownloadSchedulingRoomTest {
    @Test fun schedulingOnlyBootstrapReadsIntentWithoutAnyMediaFileSourceValidatorOrGateWork() {
        val armed = AtomicBoolean()
        val fileCalls = AtomicInteger()
        val observedFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
                if (armed.get()) { fileCalls.incrementAndGet(); error("Scheduling-only media file access") }
                return path
            }
        }
        acquisitionDownloadRoomCase(observedFiles) {
            // Retained state is produced by ACTUAL typed execution/canonical commands:
            // one historical completion, one durable network retry and one real failed
            // local finalization recheck with its documented 5 s deadline.
            val setup = owner()
            val (historicalId, _) = complete(setup, "authored-historical")
            val (waitingId, _) = runtimeRetry(setup, retryAfterMs = 86_400_000, label = "authored-waiting")
            val completing = owner(setup.store)
            val finishing = prepareFinalizationSchema3(completing)
            completing.validation = { throw IOException("authored local recheck failure") }
            completing.coordinator.start(DownloadActivation.DURABLE_WORK)
            val recheck = awaitState(completing.store) { state ->
                state.physicalDownloads[finishing.record.id]?.let { job ->
                    job.failure?.code == FailureCode.STORAGE_IO && job.leaseOwner == null && job.nextRetryEpochMs != null
                } == true
            }
            val localDeadline = assertNotNull(recheck.physicalDownloads.getValue(finishing.record.id).nextRetryEpochMs)
            val networkDeadline = assertNotNull(recheck.physicalDownloads.getValue(waitingId).nextRetryEpochMs)
            assertEquals(clock.epochMs() + 5_000, localDeadline)
            assertEquals(clock.epochMs() + 86_400_000, networkDeadline)
            val durableComplete = recheck.physicalDownloads.getValue(historicalId)
            assertEquals(DownloadStatus.COMPLETE, durableComplete.status)

            // A genuinely separate process-local host must read only canonical Room intent.
            val cold = owner(setup.store)
            armed.set(true)
            try {
                cold.coordinator.initializeScheduling()
                val plan = cold.coordinator.schedulingPlan()
                assertEquals(localDeadline, plan.nextLocalEpochMs)
                assertEquals(networkDeadline, plan.nextNetworkEpochMs)
                assertFalse(plan.hasDueWork)
                assertIs<DownloadIdleDecision.Deferred>(cold.coordinator.idleDecision())
                assertEquals(DownloadStatus.VERIFYING, cold.coordinator.physicalStates.value[historicalId]?.status)
                assertEquals(DownloadStatus.COMPLETE, cold.store.acquisitionView().acquisition.physicalDownloads.getValue(historicalId).status)
                assertEquals(1, completing.validations.size)
                assertEquals(1, completing.gate.acquires.get())
                assertEquals(0, fileCalls.get())
                cold.legacy.assertUnused()
                completing.legacy.assertUnused()
            } finally { armed.set(false) }
        }
    }

    @Test fun durableActivationDoesNotScanHistoricalAssetsAndUserUpgradeIsMonotonic() {
        val armed = AtomicBoolean()
        val fileReadsAndLists = AtomicInteger()
        val observedFiles = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
                if (armed.get() && (functionName == "source" || functionName.startsWith("list")))
                    fileReadsAndLists.incrementAndGet()
                return path
            }
        }
        acquisitionDownloadRoomCase(observedFiles) {
            val setup = owner()
            val (id, _) = complete(setup, "authored-durable-history")
            val historical = setup.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
            assertEquals(DownloadStatus.COMPLETE, historical.status)
            val validating = barrier()
            val cold = owner(setup.store)
            cold.validation = { validating.stop(); AssetValidation.Valid(31_234) }
            armed.set(true)
            try {
                cold.coordinator.start(DownloadActivation.DURABLE_WORK)
                // DURABLE activation must not authorize a historical integrity scan.
                assertIs<DownloadIdleDecision.Deferred>(cold.coordinator.idleDecision())
                delay(160) // Bounded observation; the eventual user check below proves the worker is active.
                assertEquals(0, fileReadsAndLists.get())
                assertEquals(0, cold.validations.size)
                assertEquals(0, cold.gate.acquires.get())
                assertEquals(DownloadStatus.VERIFYING, cold.coordinator.physicalStates.value[id]?.status)
                cold.coordinator.start(DownloadActivation.USER_SESSION)
                validating.entered.await()
                assertEquals(DownloadIdleDecision.OwnedWork, cold.coordinator.idleDecision())
                cold.coordinator.start(DownloadActivation.DURABLE_WORK) // Must not downgrade admitted checks.
                validating.release()
                withTimeout(5_000) { cold.coordinator.physicalStates.first { it[id]?.status == DownloadStatus.COMPLETE } }
                cold.coordinator.awaitDeferred()
                cold.coordinator.start(DownloadActivation.USER_SESSION)
                delay(120)
                assertEquals(1, cold.validations.size)
                // USER_SESSION also admits the moved-from staging-directory sweep. Its separate
                // legitimate allowance is not a second validation of this historical asset.
                assertTrue(cold.gate.acquires.get() >= 1)
                assertTrue(fileReadsAndLists.get() > 0)
                cold.legacy.assertUnused()
            } finally { armed.set(false) }
        }
    }

    @Test fun futureTwentyFourHourRetryRemainsPendingRetiresAndReopensAtItsOriginalDeadline() = executorSchema3Case {
        val setup = owner()
        val (id, selected) = runtimeRetry(setup, retryAfterMs = 86_400_000)
        val deadline = assertNotNull(join(setup, id).job.nextRetryEpochMs)
        assertEquals(clock.epochMs() + 86_400_000, deadline)
        setup.coordinator.close()
        val before = owner(setup.store)
        before.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(5_000) { before.coordinator.hasPendingWork.first { it } }
        val candidate = idle(before)
        assertEquals(deadline, candidate.plan.nextNetworkEpochMs)
        assertNull(candidate.plan.nextLocalEpochMs)
        assertTrue(before.coordinator.confirmIdle(candidate.ticket) { true })
        assertEquals(0, before.gate.acquires.get())
        assertTrue(before.gateway.prepareCalls.isEmpty())
        before.coordinator.close()
        closeStore(setup.store)
        val after = owner(store())
        after.coordinator.initializeScheduling()
        assertEquals(deadline, after.coordinator.schedulingPlan().nextNetworkEpochMs)
        assertNotEquals(candidate.plan.key.process, after.coordinator.schedulingPlan().key.process)
        advance(86_399_999)
        after.coordinator.start(DownloadActivation.DURABLE_WORK)
        assertFalse(after.coordinator.schedulingPlan().hasDueWork)
        delay(120)
        assertTrue(after.gateway.prepareCalls.isEmpty())
        advance(1)
        assertEquals(selected.context, complete(after, id).producerContext)
        assertTrue(after.selector.requests.isEmpty(), "Restart reuses the original first-result receipt")
        assertEquals(1, after.gateway.transfers.size)
        assertEquals(1, after.gate.acquires.get())
        assertEquals(1, setup.selector.requests.size)
    }

    @Test fun reconnectDoesNotBypassTwoSecondRetryAndUnknownBlocksOnlyNetworkClaims() = executorSchema3Case {
        val a = owner()
        val (id, _) = runtimeRetry(a)
        val deadline = assertNotNull(join(a, id).job.nextRetryEpochMs)
        assertEquals(clock.epochMs() + 2_000, deadline)
        val transfersBefore = a.gateway.transfers.size
        for (state in listOf(DownloadNetworkState.UNKNOWN, DownloadNetworkState.DISCONNECTED, DownloadNetworkState.CONNECTED)) {
            a.network.value = state
            val plan = a.coordinator.schedulingPlan()
            assertEquals(deadline, plan.nextNetworkEpochMs)
            assertFalse(plan.hasDueWork)
        }
        delay(120)
        assertEquals(transfersBefore, a.gateway.transfers.size)
        a.network.value = DownloadNetworkState.DISCONNECTED
        advance(2_000)
        assertIs<DownloadIdleDecision.Deferred>(a.coordinator.idleDecision())
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, a.coordinator.schedulingPlan().blockedReason?.code)
        delay(120)
        assertEquals(transfersBefore, a.gateway.transfers.size)
        a.network.value = DownloadNetworkState.CONNECTED
        complete(a, id)
        assertEquals(transfersBefore + 1, a.gateway.transfers.size)
        assertEquals(1, a.selector.requests.size)
    }

    @Test fun unactivatedCancelAndRemovePersistIntentButCannotStartMaintenanceBeforeCoverageActivation() = executorSchema3Case {
        // Retained partial/COMPLETE bytes come from actual typed execution before the cold host.
        val setup = owner()
        val entered = CompletableDeferred<Unit>()
        setup.gateway.transfer = { call -> call.write(); entered.complete(Unit); awaitCancellation() }
        val (cancelId, _) = enqueue(setup, item("cancel"), "CANCEL00001")
        setup.coordinator.start(DownloadActivation.DURABLE_WORK)
        entered.await(); setup.coordinator.pause(cancelId)
        val part = assertNotNull(join(setup, cancelId).job.stagingPath)
        setup.gateway.transfer = { it.complete() }
        val (removeId, _) = enqueue(setup, item("remove"), "REMOVE00001")
        val completed = complete(setup, removeId)
        val asset = assertNotNull(completed.asset)
        setup.coordinator.close()
        val a = owner(setup.store)
        a.network.value = DownloadNetworkState.DISCONNECTED
        a.coordinator.initializeScheduling()
        a.coordinator.cancel(cancelId)
        a.coordinator.remove(removeId)
        assertTrue(join(a, cancelId).job.cleanupPending)
        assertTrue(join(a, removeId).job.removeWhenClean)
        assertTrue(Files.exists(Path.of(part)))
        assertTrue(Files.exists(Path.of(asset.uri)))
        assertEquals(0, a.gate.acquires.get())
        assertTrue(a.validations.isEmpty())
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitJob(a, cancelId) { !it.job.cleanupPending && it.job.status == DownloadStatus.CANCELLED }
        val removed = awaitJob(a, removeId) { !it.job.cleanupPending && it.asset == null && it.job.status == DownloadStatus.CANCELLED }
        assertFalse(Files.exists(Path.of(part)))
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertEquals(completed.attempts, removed.attempts)
        assertEquals(2, a.gate.acquires.get())
        assertTrue(a.gateway.prepareCalls.isEmpty())
        assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(a.selector.requests.isEmpty())
        a.legacy.assertUnused()
    }

    @Test fun acquisitionIsOwnedBeforeGateAckThroughActualResourceAndAllowanceClose() = executorSchema3Case {
        // Actual typed allowance + LocalMediaValidator replace the nonexistent download PlaybackResource.
        val a = owner()
        val acquiring = barrier(); val validating = barrier(); val allowanceClosing = barrier()
        a.gate.beforeAcquire = { acquiring.stop() }
        a.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        a.gate.beforeClose = { withContext(NonCancellable) { allowanceClosing.stop() } }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        assertNotNull(join(a, id).job.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, a.coordinator.idleDecision())
        assertTrue(a.gateway.prepareCalls.isEmpty())
        acquiring.release()
        validating.entered.await()
        assertEquals(DownloadStatus.VERIFYING, join(a, id).job.status)
        assertEquals(DownloadIdleDecision.OwnedWork, a.coordinator.idleDecision())
        assertNull(probeFiles.tryLeasePaths(listOf(a.gateway.transfers.single().path)))
        validating.release()
        allowanceClosing.entered.await()
        assertEquals(DownloadStatus.COMPLETE, join(a, id).job.status)
        assertNull(join(a, id).job.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, a.coordinator.idleDecision())
        assertNull(probeFiles.tryTransferSlot(1))
        allowanceClosing.release()
        val candidate = idle(a)
        assertTrue(a.coordinator.confirmIdle(candidate.ticket) { true })
        assertEquals(selected.context, complete(a, id).producerContext)
        assertEquals(1, a.gateway.transfers.size)
        assertEquals(1, a.validations.size)
    }

    @Test fun maintenanceIsOwnedBeforeGateAckAndAfterRoomCompletionUntilNativeCloseReturns() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val acquiring = barrier(); val closing = barrier()
        owner.gate.beforeAcquire = { acquiring.stop() }
        owner.gate.beforeClose = { withContext(NonCancellable) { closing.stop() } }
        owner.validation = { AssetValidation.Valid(31_234) }
        owner.denyFinalizationSchema3Acquisition()
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        assertNotNull(owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
        assertEquals(0, owner.validations.size)
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        acquiring.release()
        closing.entered.await()
        val completed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seeded.record.id)
        assertEquals(DownloadStatus.COMPLETE, completed.status)
        assertNull(completed.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        closing.release()
        assertTrue(scheduler.confirmIdle(scheduler.awaitDeferred().ticket) { true })
        owner.assertFinalizationSchema3Recovered(seeded)
        scheduler.close()
        assertTrue(scheduler.dependencyReleaseSafeAfterClose)
    }

    @Test fun independentBackgroundDrainWaitsForResourceAllowanceAndRoomFinishEvenAfterNativeLeasesReachZero() = executorSchema3Case {
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeClosing = barrier(); val allowanceClosing = barrier(); val roomFinishing = barrier()
        val nativeLeases = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                nativeLeases.incrementAndGet()
                return DownloadExecutionLease {
                    withContext(NonCancellable) { allowanceClosing.stop(); nativeLeases.decrementAndGet() }
                }
            }
        }
        val a = owner(executionGate = gate)
        a.validation = {
            nativeEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { nativeClosing.stop() } }
        }
        val finishOnce = AtomicBoolean(true)
        a.repository.afterAttemptFinish = { command, actual ->
            if (command.status == DownloadStatus.NEEDS_FOREGROUND && actual is PhysicalDownloadCommit.Committed &&
                finishOnce.compareAndSet(true, false)) roomFinishing.stop()
        }
        val (id, selected) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        nativeEntered.await()
        val claimed = join(a, id)
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { a.coordinator.drainForBackground() }
            try {
                nativeClosing.entered.await()
                assertFalse(draining.isCompleted)
                // Canonical finish is AFTER physical unwind, not the removed legacy bulk write.
                assertEquals(claimed.job, join(a, id).job)
                assertTrue(a.repository.finishCalls.isEmpty())
                assertEquals(1, nativeLeases.get())
                assertNull(probeFiles.tryLeasePaths(listOf(assertNotNull(claimed.job.stagingPath))))
                nativeClosing.release()
                allowanceClosing.entered.await()
                assertFalse(draining.isCompleted)
                assertTrue(a.repository.finishCalls.isEmpty())
                allowanceClosing.release()
                roomFinishing.entered.await()
                assertEquals(0, nativeLeases.get())
                assertFalse(draining.isCompleted, "Zero native leases cannot stand in for actual Room return/owner join")
                val committed = join(a, id)
                assertNull(committed.job.leaseOwner)
                assertNull(committed.activeAttempt)
                assertEquals(DownloadStatus.NEEDS_FOREGROUND, committed.job.status)
                assertNotNull(probeFiles.tryTransferSlot(1)).close()
                roomFinishing.release()
                assertNull(draining.await().checkpointFailure)
            } finally { nativeClosing.release(); allowanceClosing.release(); roomFinishing.release() }
        }
        val drained = join(a, id)
        assertNull(drained.job.leaseOwner)
        assertNull(drained.activeAttempt)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, drained.job.status)
        assertEquals(selected.context, drained.producerContext)
        assertIs<DownloadIdleDecision.Deferred>(a.coordinator.idleDecision())
        assertEquals(1, a.gateway.transfers.size)
        assertEquals(1, a.repository.finishCalls.size)
    }

    @Test fun failedBackgroundCheckpointAcknowledgesOnlyAfterAcquisitionAndMaintenanceOwnersDrain() = executorSchema3Case {
        val a = owner()
        val (maintenanceId, _) = enqueue(a, item("maintaining"), "MAINTAIN001")
        val intent = recoveryIntent(a, maintenanceId)
        advance(1_100) // Authored process-loss setup expires only its real canonical lease.
        val (acquisitionId, selected) = enqueue(a, item("acquiring"), "ACQUIRE0001")
        val transferEntered = CompletableDeferred<Unit>(); val validationEntered = CompletableDeferred<Unit>()
        val transferClosing = barrier(); val validatorClosing = barrier()
        a.gateway.transfer = { call ->
            call.write(); transferEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { transferClosing.stop() } }
        }
        a.validation = {
            validationEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { validatorClosing.stop() } }
        }
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_background_checkpoint_failure")
        a.expectedCloseFailure = failure
        val once = AtomicBoolean(true)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        transferEntered.await(); validationEntered.await()
        val beforeAcquisition = join(a, acquisitionId)
        faults(a.store).beforeCommit = { before, next ->
            if (before.state().physicalDownloads[acquisitionId]?.leaseOwner != null &&
                next.state().physicalDownloads[acquisitionId]?.let {
                    it.status == DownloadStatus.NEEDS_FOREGROUND && it.leaseOwner == null
                } == true && once.compareAndSet(true, false)) throw VeyraException(failure)
        }
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { a.coordinator.drainForBackground() }
            try {
                transferClosing.entered.await(); validatorClosing.entered.await()
                assertTrue(once.get(), "After-drain Room finish must not be invented before native/transfer unwind")
                assertFalse(draining.isCompleted)
                transferClosing.release()
                awaitCondition { !once.get() }
                assertEquals(beforeAcquisition.job.leaseOwner, join(a, acquisitionId).job.leaseOwner,
                    "Pre-COMMIT failure preserves the real unconfirmed durable fence")
                assertFalse(draining.isCompleted, "Separate maintenance still requires its own physical join")
                validatorClosing.release()
                assertEquals(failure, draining.await().checkpointFailure)
            } finally { transferClosing.release(); validatorClosing.release() }
        }
        val acquiring = join(a, acquisitionId)
        val maintaining = join(a, maintenanceId)
        assertEquals(beforeAcquisition.job, acquiring.job)
        assertNotNull(acquiring.activeAttempt)
        assertNull(acquiring.activeAttempt?.finishedEpochMs)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, maintaining.job.status)
        assertNull(maintaining.job.leaseOwner)
        assertEquals(intent, maintaining.finalization)
        assertEquals(selected.context, acquiring.producerContext)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(assertNotNull(acquiring.job.stagingPath))))
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        assertNotNull(probeFiles.tryLeasePaths(listOf(assertNotNull(acquiring.job.stagingPath), intent.stagingPath))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        assertEquals(failure, a.coordinator.lastFailure.value)
        assertFalse(a.coordinator.schedulingPlan().hasDueWork)
        assertEquals(2, a.gate.acquires.get())
        assertEquals(1, a.gateway.transfers.size)
        assertEquals(1, a.repository.finishCalls.size)
        assertEquals(1, a.repository.maintenanceFinishCalls.size)
        assertEquals(failure, assertFailsWith<VeyraException> { a.coordinator.pauseForBackground() }.failure)
        assertEquals(1, a.repository.finishCalls.size, "Typed diagnostic ACK cannot silently retry the failed transaction")
    }

    @Test fun resumeDuringSuspendedFailingCheckpointIsRejectedWithoutAdmittingAnUncapturedOwnerOrReplayingLater() = executorSchema3Case {
        val a = owner()
        val transferring = CompletableDeferred<Unit>()
        val physicalClosing = barrier(); val checkpoint = barrier()
        val calls = AtomicInteger()
        a.gateway.transfer = { call ->
            if (calls.incrementAndGet() == 1) { call.write(); transferring.complete(Unit); awaitCancellation() }
            else call.complete()
        }
        a.gate.beforeClose = { if (a.gate.closes.get() == 1) withContext(NonCancellable) { physicalClosing.stop() } }
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_checkpoint_result_failure")
        a.expectedCloseFailure = failure
        val once = AtomicBoolean(true)
        a.repository.afterAttemptFinish = { command, actual ->
            if (command.status == DownloadStatus.NEEDS_FOREGROUND && actual is PhysicalDownloadCommit.Committed &&
                once.compareAndSet(true, false)) {
                checkpoint.stop()
                throw VeyraException(failure)
            }
        }
        val (firstId, _) = enqueue(a)
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        val queued = select(a, item("queued-during-drain"), "QUEUED00001")
        var queuedId: String? = null
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { a.coordinator.drainForBackground() }
            try {
                physicalClosing.entered.await()
                val resuming = async(start = CoroutineStart.UNDISPATCHED) { runCatching { a.coordinator.resumePending() } }
                assertTrue(resuming.isCompleted, "Denied resume cannot queue behind an accepted drain")
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, assertIs<VeyraException>(resuming.await().exceptionOrNull()).failure.code)
                // Schema3 accepts cold metadata independently; accepted metadata is NOT physical work.
                queuedId = enqueue(a, queued)
                val pending = join(a, assertNotNull(queuedId))
                assertEquals(DownloadStatus.QUEUED, pending.job.status)
                assertNull(pending.job.leaseOwner)
                assertNull(pending.activeAttempt)
                assertEquals(1, a.gate.acquires.get())
                physicalClosing.release()
                checkpoint.entered.await()
                assertFalse(draining.isCompleted)
                assertEquals(FailureCode.BACKGROUND_RESTRICTED,
                    assertFailsWith<VeyraException> { a.coordinator.resumePending() }.failure.code)
                a.coordinator.start(DownloadActivation.DURABLE_WORK)
                assertEquals(DownloadIdleDecision.OwnedWork, a.coordinator.idleDecision())
                assertEquals(1, a.gateway.transfers.size)
                checkpoint.release()
                assertEquals(failure, draining.await().checkpointFailure)
            } finally { physicalClosing.release(); checkpoint.release() }
        }
        assertIs<DownloadIdleDecision.Deferred>(a.coordinator.idleDecision())
        delay(120)
        assertEquals(1, a.gate.acquires.get())
        assertEquals(1, a.gateway.transfers.size, "Rejected resume must not replay itself after ACK")
        a.coordinator.resumePending() // A genuinely NEW foreground permission, only after returned drain.
        complete(a, firstId)
        complete(a, assertNotNull(queuedId))
        assertEquals(3, a.gateway.transfers.size)
        assertEquals(2, a.selector.requests.size)
    }

    @Test fun cancellingIndependentBackgroundDrainCannotSkipJoiningPendingNativeAcquisition() = executorSchema3Case {
        val owner = owner()
        val transferring = CompletableDeferred<Unit>()
        val nativeClosing = barrier()
        owner.gateway.transfer = { call ->
            call.write(); transferring.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { nativeClosing.stop() } }
        }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        coroutineScope {
            val callerFinished = AtomicBoolean()
            val draining = launch(start = CoroutineStart.UNDISPATCHED) {
                try { owner.coordinator.drainForBackground() }
                finally { callerFinished.set(true) }
            }
            try {
                nativeClosing.entered.await()
                draining.cancel()
                assertNull(withTimeoutOrNull(100) { draining.join(); true }, "Cancelled caller still owes the accepted join")
                assertFalse(callerFinished.get())
                assertEquals(DownloadIdleDecision.OwnedWork, owner.coordinator.idleDecision())
                nativeClosing.release()
                draining.join()
                assertTrue(draining.isCancelled)
                assertTrue(callerFinished.get())
            } finally { nativeClosing.release() }
        }
        val drained = join(owner, id)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, drained.job.status)
        assertNull(drained.job.leaseOwner)
        assertIs<DownloadIdleDecision.Deferred>(owner.coordinator.idleDecision())
        owner.legacy.assertUnused()
    }

    @Test fun unknownOrCancelledCheckpointStillJoinsOwnersButNeverAcknowledgesOrSilentlyRetriesForegroundResume() {
        for (unknown in listOf(CancellationException("authored checkpoint cancellation"), AssertionError("authored unknown checkpoint"))) {
            executorSchema3Case {
                val owner = owner()
                val transferring = CompletableDeferred<Unit>()
                val resourceClosing = barrier()
                val resourceClosed = AtomicBoolean(); val allowanceClosed = AtomicBoolean()
                val finishAttempted = AtomicBoolean()
                owner.gateway.transfer = { call -> call.write(); transferring.complete(Unit); awaitCancellation() }
                owner.gate.beforeClose = {
                    withContext(NonCancellable) { resourceClosing.stop(); resourceClosed.set(true); allowanceClosed.set(true) }
                }
                val once = AtomicBoolean(true)
                owner.repository.afterAttemptFinish = { command, actual ->
                    if (command.status == DownloadStatus.NEEDS_FOREGROUND && actual is PhysicalDownloadCommit.Committed &&
                        once.compareAndSet(true, false)) {
                        finishAttempted.set(true)
                        throw unknown
                    }
                }
                val (id, _) = enqueue(owner)
                owner.coordinator.start(DownloadActivation.DURABLE_WORK)
                transferring.await()
                coroutineScope {
                    val draining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.drainForBackground() } }
                    try {
                        resourceClosing.entered.await()
                        assertFalse(finishAttempted.get())
                        assertFalse(draining.isCompleted, "Even an unknown checkpoint error must first join native ownership")
                        assertFalse(resourceClosed.get())
                        assertFalse(allowanceClosed.get())
                        resourceClosing.release()
                        assertEquals(unknown::class, assertNotNull(draining.await().exceptionOrNull())::class)
                    } finally { resourceClosing.release() }
                }
                assertTrue(resourceClosed.get())
                assertTrue(allowanceClosed.get())
                assertNull(join(owner, id).job.leaseOwner)
                assertNotNull(probeFiles.tryTransferSlot(1)).close()
                val restriction = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
                assertEquals(restriction, owner.coordinator.lastFailure.value)
                assertEquals(restriction, owner.coordinator.schedulingPlan().blockedReason)
                for (retry in listOf<suspend () -> Unit>(
                    { owner.coordinator.resumePending() }, { owner.coordinator.drainForBackground(); Unit }, { owner.coordinator.idleDecision(); Unit },
                )) {
                    assertEquals(restriction, assertFailsWith<VeyraException> { retry() }.failure)
                }
                assertEquals(1, owner.gateway.transfers.size)
                assertEquals(restriction, owner.coordinator.lastFailure.value, "Rejected retries retain the first visible typed failure")
            }
        }
    }

    @Test fun backgroundDrainJoinsAlreadyClosingOwnersWithoutReopeningOrCheckpointingClosedCoordinator() = executorSchema3Case {
        val owner = owner()
        val fault = faults(owner.store)
        val transferring = CompletableDeferred<Unit>()
        val resourceClosing = barrier()
        val written = AtomicInteger()
        owner.gateway.transfer = { call -> call.write(); transferring.complete(Unit); awaitCancellation() }
        owner.gate.beforeClose = { withContext(NonCancellable) { resourceClosing.stop() } }
        val original = fault.beforeCommit
        fault.beforeCommit = { before, next -> original(before, next); written.incrementAndGet() }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        coroutineScope {
            val closing = async { owner.coordinator.close() }
            try {
                resourceClosing.entered.await()
                assertFailsWith<IllegalStateException> { owner.coordinator.schedulingPlan() }
                val writesBeforeDrain = written.get()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { owner.coordinator.drainForBackground() }
                assertFalse(draining.isCompleted, "The closed latch is not the registry drain")
                assertFalse(closing.isCompleted)
                assertEquals(writesBeforeDrain, written.get())
                resourceClosing.release()
                assertNull(draining.await().checkpointFailure)
                closing.await()
            } finally { resourceClosing.release() }
        }
        assertNull(join(owner, id).job.leaseOwner)
        closeStore(owner.store)
        assertNull(owner.coordinator.drainForBackground().checkpointFailure)
        owner.coordinator.pauseForBackground()
    }

    @Test fun ticketsAreExactLatestInstancesAndEveryConfirmationAttemptIsSingleUse() = acquisitionDownloadRoomCase {
        val scheduler = owner().coordinator
        scheduler.initializeScheduling()
        val first = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val calls = AtomicInteger()
        assertFalse(scheduler.confirmIdle(DownloadIdleTicket(first.ticket.key)) { calls.incrementAndGet(); true })
        val latest = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        assertEquals(first.plan.key, latest.plan.key)
        assertFalse(scheduler.confirmIdle(first.ticket) { calls.incrementAndGet(); true })
        assertTrue(scheduler.confirmIdle(latest.ticket) { calls.incrementAndGet(); true })
        assertFalse(scheduler.confirmIdle(latest.ticket) { calls.incrementAndGet(); true })
        val refused = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        assertFalse(scheduler.confirmIdle(refused.ticket) { calls.incrementAndGet(); false })
        assertFalse(scheduler.confirmIdle(refused.ticket) { calls.incrementAndGet(); true })
        assertEquals(2, calls.get())
    }

    @Test fun unrelatedPlaybackProgressAndPresentationChangesDoNotStarveIdleRetirement() = acquisitionDownloadRoomCase {
        val host = owner()
        val (id, _) = runtimeRetry(host, retryAfterMs = 86_400_000)
        val scheduler = host.coordinator
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val priorRevision = host.store.acquisitionView().snapshot.revision
        repeat(3) { index ->
            // Typed scheduling deliberately zeroes progress/bytes from its canonical inputs.
            // A separate real Room connection still publishes unrelated presentation rows.
            mutateUnrelatedDownloadPresentation(host, id, index)
            host.store.update { snapshot -> snapshot.copy(
                settings = snapshot.settings.copy(textScale = 1.5f, theme = ThemeMode.DARK),
                navigation = snapshot.navigation + ("authored-view" to index.toString()),
            ) }.getOrThrow()
            assertEquals(candidate.plan, scheduler.schedulingPlan())
        }
        assertTrue(host.store.acquisitionView().snapshot.revision > priorRevision)
        assertTrue(scheduler.confirmIdle(candidate.ticket) { true })
    }

    /** Direct SQL models another real writer's non-identity progress row, never a legal snapshot edit. */
    private fun mutateUnrelatedDownloadPresentation(owner: DownloadAcquisitionRoomOwner, id: String, index: Int) {
        androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(owner.fixture.databasePath.toString()).use { connection ->
            connection.execSQL("PRAGMA busy_timeout = 5000")
            connection.execSQL("BEGIN IMMEDIATE")
            try {
                connection.prepare("UPDATE downloads SET bytesDownloaded = ?, totalBytes = 10, updatedEpochMs = ?, lastProgressEpochMs = ? WHERE id = ?").use {
                    it.bindLong(1, index.toLong())
                    it.bindLong(2, owner.fixture.clock.epochMs() + index)
                    it.bindLong(3, owner.fixture.clock.epochMs() + index)
                    it.bindText(4, id)
                    it.step()
                }
                connection.execSQL("UPDATE store_meta SET revision = revision + 1 WHERE id = 0")
                connection.execSQL("COMMIT")
            } catch (error: Throwable) {
                connection.execSQL("ROLLBACK")
                throw error
            }
        }
    }

    @Test fun anotherRoomConnectionCanInvalidateTicketEvenWhenItsInvalidationHintIsUnavailable() = acquisitionDownloadRoomCase {
        val writer = owner()
        val (id, _) = runtimeRetry(writer, retryAfterMs = 86_400_000, label = "authored-cross-connection")
        val originalDeadline = assertNotNull(join(writer, id).job.nextRetryEpochMs)
        val reader = owner(store(suppressInvalidationHints = true))
        val scheduler = reader.coordinator
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        // Another real Room connection publishes a genuinely earlier retry through one actual
        // failing typed transfer; no row is fabricated.
        writer.gateway.transfer = { prepared, path, _ ->
            Files.write(Path.of(path), authoredBytes)
            throw VeyraException(AppFailure(FailureCode.RATE_LIMITED, "authored_cross_connection_retry", retryAfterMs = 2_000))
        }
        writer.coordinator.resume(id)
        val earlier = awaitState(writer.store) { state ->
            state.physicalDownloads[id]?.let { it.status == DownloadStatus.WAITING_NETWORK && it.leaseOwner == null &&
                it.nextRetryEpochMs == writer.fixture.clock.epochMs() + 2_000 } == true
        }
        val earlierDeadline = assertNotNull(earlier.physicalDownloads.getValue(id).nextRetryEpochMs)
        assertEquals(originalDeadline, reader.store.acquisitionViews.value.acquisition.physicalDownloads[id]?.nextRetryEpochMs,
            "The reader is deliberately stale; only an authoritative Room read can notice this write")
        assertFalse(scheduler.confirmIdle(candidate.ticket) { error("A stale wake plan cannot retire") })
        val fresh = scheduler.schedulingPlan()
        assertEquals(earlierDeadline, fresh.nextNetworkEpochMs)
        assertNotEquals(candidate.plan.key, fresh.key)
    }

    @Test fun enqueueResumeCancelAndPolicyChangesDuringWakeAckInvalidateOldTicket() = acquisitionDownloadRoomCase {
        val setup = owner()
        val waiting = (0 until 3).map { index ->
            runtimeRetry(setup, retryAfterMs = 86_400_000, label = "authored-wake-mutation-$index").first
        }
        setup.coordinator.close()
        val host = owner(setup.store)
        val scheduler = host.coordinator
        scheduler.initializeScheduling()
        suspend fun checkMutation(mutate: suspend () -> Unit) {
            val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            val publishing = barrier()
            val calls = AtomicInteger()
            coroutineScope {
                val retired = async { publishing.stop(); scheduler.confirmIdle(candidate.ticket) { calls.incrementAndGet(); true } }
                publishing.entered.await()
                mutate()
                publishing.release()
                assertFalse(retired.await())
            }
            assertEquals(0, calls.get())
        }
        val newItem = acceptNeutral(host, "authored-wake-enqueue")
        checkMutation { enqueue(host, newItem) }
        checkMutation { host.coordinator.resume(waiting[0]) }
        checkMutation { host.coordinator.cancel(waiting[1]) }
        checkMutation { host.store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow() }
        checkMutation { host.coordinator.pauseForBackground() }
    }

    @Test fun connectedDueWorkAndHostEpochChangesInvalidateDeferredTickets() = acquisitionDownloadRoomCase {
        val host = owner()
        queued(host) // Accepted durable intent, but the host is never started.
        val scheduler = host.coordinator
        scheduler.initializeScheduling()
        val beforeConnection = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        host.network.value = DownloadNetworkState.CONNECTED
        assertFalse(scheduler.confirmIdle(beforeConnection.ticket) { error("Due new network claim cannot retire") })
        host.network.value = DownloadNetworkState.DISCONNECTED
        val beforeHostChange = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        scheduler.pauseForBackground()
        scheduler.resumePending()
        assertFalse(scheduler.confirmIdle(beforeHostChange.ticket) { error("Changed host generation cannot retire") })
    }

    @Test fun dueCrossingInsideFreshReadCannotConfirmEarlierFutureTicket() = acquisitionDownloadRoomCase {
        val setup = owner()
        val (id, _) = runtimeRetry(setup, retryAfterMs = 2_000, label = "authored-crossing-read")
        assertEquals(setup.fixture.clock.epochMs() + 2_000, join(setup, id).job.nextRetryEpochMs)
        setup.coordinator.close()
        val nextRead = AtomicReference<DownloadBarrier?>()
        val reader = owner(store(suppressInvalidationHints = true))
        val reading = barrier()
        faults(reader.store).afterRead = { nextRead.getAndSet(null)?.stop() }
        val scheduler = reader.coordinator
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        nextRead.set(reading)
        coroutineScope {
            val retired = async { scheduler.confirmIdle(candidate.ticket) { error("Clock crossed during read") } }
            reading.entered.await()
            reader.fixture.clock.advance(2_000)
            reading.release()
            assertFalse(retired.await())
        }
    }

    @Test fun secondTimeCheckRejectsCrossingAfterTheFreshPlanWasBuilt() = acquisitionDownloadRoomCase {
        val baseClock = clock
        val deadline = baseClock.epochMs() + 2_000
        val armed = AtomicBoolean()
        val reads = AtomicInteger()
        val crossingClock = object : Clock {
            override fun epochMs(): Long = if (armed.get() && reads.incrementAndGet() >= 2) deadline else baseClock.epochMs()
            override fun monotonicMs(): Long = baseClock.monotonicMs()
        }
        val setup = owner()
        runtimeRetry(setup, retryAfterMs = 2_000, label = "authored-crossing-plan")
        setup.coordinator.close()
        val host = owner(setup.store, schedulerClock = crossingClock)
        val scheduler = host.coordinator
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        armed.set(true)
        assertFalse(scheduler.confirmIdle(candidate.ticket) { error("Time crossed after planning") })
        assertEquals(2, reads.get())
    }

    @Test fun freshReadFailureThrowsInsteadOfCertifyingCachedIdleAndConsumesTicket() = downloadCase {
        val real = persistence()
        val failRead = AtomicBoolean()
        val persistence = object : LibraryPersistence by real {
            override suspend fun read(): AppSnapshot {
                if (failRead.get()) throw IOException("Authored Room read failure")
                return real.read()
            }
        }
        val scheduler = coordinator(store(persistence))
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val calls = AtomicInteger()
        failRead.set(true)
        assertEquals(FailureCode.STORAGE_IO, assertFailsWith<VeyraException> {
            scheduler.confirmIdle(candidate.ticket) { calls.incrementAndGet(); true }
        }.failure.code)
        assertFailsWith<VeyraException> { scheduler.schedulingPlan() }
        assertFailsWith<VeyraException> { scheduler.idleDecision() }
        failRead.set(false)
        assertFalse(scheduler.confirmIdle(candidate.ticket) { calls.incrementAndGet(); true })
        assertEquals(0, calls.get())
        assertTrue(scheduler.confirmIdle(scheduler.awaitDeferred().ticket) { true })
    }

    @Test fun refusedPhysicalTransferSlotHasBoundedLocalBackoffWithoutAnyGateOrSourceClaim() = executorSchema3Case {
        val owner = owner()
        parallelism(owner, 1)
        val (id, _) = enqueue(owner)
        files.initialize()
        val held = assertNotNull(probeFiles.tryTransferSlot(1))
        releaseOnClose += { held.close() }
        val calls = AtomicInteger()
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        val delays = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        for ((index, delay) in delays.withIndex()) {
            val deadline = clock.epochMs() + delay
            val deferred = scheduler.awaitPlan { !it.hasDueWork && it.nextLocalEpochMs == deadline }
            assertNull(deferred.nextNetworkEpochMs)
            assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            assertEquals(0, owner.gate.acquires.get())
            assertEquals(0, join(owner, id).job.attemptCount)
            assertTrue(owner.gateway.prepareCalls.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
            if (index != delays.lastIndex) advance(delay)
        }
        assertNull(probeFiles.tryTransferSlot(1), "Failed contenders must not release the existing slot owner")
        held.close()
        advance(delays.last() - 1)
        assertFalse(scheduler.schedulingPlan().hasDueWork)
        delay(120)
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        advance(1)
        complete(owner, id)
        assertEquals(1, owner.gate.acquires.get())
        assertEquals(1, owner.gateway.transfers.size)
    }

    @Test fun refusedFinalizationFileLeaseDefersLocallyBeforeValidationOrNativeAcquisition() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val held = assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath)))
        try {
            owner.validation = { AssetValidation.Valid(31_234) }
            owner.denyFinalizationSchema3Acquisition()
            val scheduler = owner.coordinator
            scheduler.start(DownloadActivation.DURABLE_WORK)
            val deferred = scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
            assertNull(deferred.nextNetworkEpochMs)
            assertEquals(0, owner.validations.size)
            assertEquals(0, owner.gate.acquires.get())
            assertNull(owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
            held.close()
            advance(5_000)
            awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
            idle(owner)
            assertEquals(1, owner.validations.size)
            assertEquals(1, owner.gate.acquires.get())
            owner.assertFinalizationSchema3Recovered(seeded)
            scheduler.close()
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
        } finally { held.close() }
    }

    @Test fun rolledBackAcquisitionClaimReleasesFilesAndDefersInsteadOfKeepingAnEmptyOwnerAlive() = executorSchema3Case {
        val owner = owner()
        val (id, _) = enqueue(owner)
        val blocked = AtomicBoolean(true)
        val fault = faults(owner.store)
        fault.beforeCommit = { before, next ->
            if (blocked.get() && before.state().physicalDownloads[id]?.leaseOwner == null &&
                next.state().physicalDownloads[id]?.activeAttemptId != null)
                throw IOException("Authored acquisition claim rollback")
        }
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        val unchanged = owner.store.acquisitionView().acquisition
        assertEquals(DownloadStatus.QUEUED, unchanged.physicalDownloads.getValue(id).status)
        assertTrue(unchanged.physicalAttempts.values.none { it.downloadId == id })
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        assertEquals(0, owner.gate.acquires.get())
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertTrue(scheduler.confirmIdle(scheduler.awaitDeferred().ticket) { true })
        blocked.set(false)
        advance(5_000)
        complete(owner, id)
        assertEquals(1, owner.gateway.transfers.size)
    }

    @Test fun failedExpiredLeaseRecoveryIsDeferredWithoutForgingAcknowledgementOrStartingSource() = executorSchema3Case {
        val owner = owner()
        val (id, _) = enqueue(owner)
        val foreign = foreignClaim(owner, id, leaseDurationMs = 1_000)
        foreign.releasePhysicalAsAuthoredCrash()
        advance(1_100)
        val before = join(owner, id)
        assertEquals("authored-foreign-owner", before.job.leaseOwner)
        val blocked = AtomicBoolean(true)
        faults(owner.store).beforeCommit = { current, next ->
            if (blocked.get() && current.state().physicalDownloads[id]?.leaseOwner == "authored-foreign-owner" &&
                next.state().physicalDownloads[id]?.leaseOwner == null)
                throw IOException("Authored expired-owner recovery rollback")
        }
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        val unchanged = join(owner, id)
        assertEquals(before.job, unchanged.job)
        assertNull(unchanged.attempts.single().finishedEpochMs)
        assertEquals(0, owner.gate.acquires.get())
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        blocked.set(false)
        advance(5_000)
        complete(owner, id)
        assertEquals(1, owner.gateway.transfers.size)
    }

    @Test fun rolledBackMaintenanceClaimKeepsIntentAndHasLocalCooldownBeforeRetry() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val blocked = AtomicBoolean(true)
        val rollbacks = AtomicInteger()
        faults(owner.store).beforeCommit = { before, next ->
            if (blocked.get() && before.state().physicalDownloads[seeded.record.id]?.leaseOwner == null &&
                next.state().physicalDownloads[seeded.record.id]?.leaseOwner != null) {
                rollbacks.incrementAndGet()
                throw IOException("Authored maintenance claim rollback")
            }
        }
        owner.validation = { AssetValidation.Valid(31_234) }
        owner.denyFinalizationSchema3Acquisition()
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        val deferred = scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        assertNull(deferred.nextNetworkEpochMs)
        val unchanged = owner.store.acquisitionView().acquisition
        assertEquals(seeded.record, unchanged.physicalDownloads[seeded.record.id])
        assertEquals(seeded.intent, unchanged.physicalFinalizations[seeded.record.id])
        assertEquals(seeded.attempt, unchanged.physicalAttempts[seeded.attempt.id])
        assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
        assertEquals(1, rollbacks.get(), "The actual Room maintenance transaction must have rolled back")
        assertEquals(0, owner.gate.acquires.get())
        assertEquals(0, owner.validations.size)
        blocked.set(false)
        clock.advance(5_000)
        awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        idle(owner)
        assertEquals(1, owner.gate.acquires.get())
        owner.assertFinalizationSchema3Recovered(seeded)
        scheduler.close()
        assertTrue(scheduler.dependencyReleaseSafeAfterClose)
    }

    @Test fun failedCleanupBackoffPersistenceStillDefersLocallyAndRetainsAllDurableReferences() {
        val target = AtomicReference<String?>()
        val blocked = AtomicBoolean(true)
        val filesWithFailure = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun onPathParameter(path: okio.Path, functionName: String, parameterName: String): okio.Path {
                if (blocked.get() && functionName == "delete" && path.toString() == target.get())
                    throw IOException("Authored cleanup deletion failure")
                return path
            }
        }
        acquisitionDownloadRoomCase(filesWithFailure) {
            val owner = owner()
            val (id, _) = complete(owner, "authored-cleanup-backoff")
            val asset = assertNotNull(join(owner, id).asset)
            target.set(asset.uri)
            // Accepted removable intent through the same canonical commands as remove(), but
            // BEFORE any cleanup owner runs, so the first actual deletion is scheduler-driven.
            val before = join(owner, id)
            val claim = before.claims.single { it.owner is DownloadClaimOwner.Standalone }
            val updated = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
                owner.repository.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(id, before.job.producer,
                    before.job.ownershipGeneration, claim.id, claim.generation, claim.ownerIntentRevision,
                    DownloadBatchIntent.CANCELLED, retain = false, clock.epochMs(), allowRetryFailure = false))).value
            assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
                owner.repository.reconcilePhysicalDownloadIntent(ReconcilePhysicalDownloadIntent(id, updated.job.producer,
                    updated.job.ownershipGeneration, updated.claims, clock.epochMs())))
            val fault = faults(owner.store)
            fault.beforeCommit = { _, next ->
                if (blocked.get() && next.state().physicalDownloads[id]?.failure != null)
                    throw IOException("Authored rollback of cleanup error/backoff persistence")
            }
            val scheduler = owner.coordinator
            scheduler.start(DownloadActivation.DURABLE_WORK)
            scheduler.awaitPlan { it.nextLocalEpochMs != null && !it.hasDueWork }
            assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            val retained = join(owner, id)
            assertTrue(retained.job.cleanupPending)
            assertTrue(retained.job.removeWhenClean)
            assertEquals(asset, retained.asset)
            assertTrue(Files.exists(Path.of(asset.uri)))
            blocked.set(false)
            clock.advance(5_000)
            awaitJob(owner, id) { !it.job.cleanupPending && it.asset == null }
            assertFalse(Files.exists(Path.of(asset.uri)))
            assertTrue(owner.gateway.preparations.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
            owner.legacy.assertUnused()
        }
    }
}

private fun countingGate(calls: AtomicInteger) = object : DownloadExecutionGate {
    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        calls.incrementAndGet()
        return DownloadExecutionLease { }
    }
}

private suspend fun DownloadCoordinator.awaitDeferred(): DownloadIdleDecision.Deferred = withTimeout(15_000) {
    var decision = idleDecision()
    while (decision !is DownloadIdleDecision.Deferred) { delay(20); decision = idleDecision() }
    decision
}

private suspend fun DownloadCoordinator.awaitPlan(predicate: (DownloadWakePlan) -> Boolean): DownloadWakePlan = withTimeout(15_000) {
    var plan = schedulingPlan()
    while (!predicate(plan)) { delay(20); plan = schedulingPlan() }
    plan
}
