package dev.veyra.downloads

import dev.veyra.data.LibraryPersistence
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
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
        downloadCase(observedFiles) {
            val library = store()
            val historical = seedComplete(library, item("historical"))
            val finishing = seedFinalization(library, item("finishing"))
            val waiting = seedPending(library, item("waiting"), DownloadStatus.WAITING_NETWORK)
            val cleaning = seedPending(library, item("cleaning"), DownloadStatus.CANCELLED, bytes = authoredBytes)
            library.update { snapshot -> snapshot.copy(downloads = snapshot.downloads + mapOf(
                finishing.record.id to finishing.record.copy(nextRetryEpochMs = clock.epochMs() + 2_000),
                waiting.record.id to waiting.record.copy(nextRetryEpochMs = clock.epochMs() + 86_400_000),
                cleaning.record.id to cleaning.record.copy(cleanupPending = true, userPaused = true,
                    nextRetryEpochMs = clock.epochMs() + 3_000),
            )) }.getOrThrow()
            val gateCalls = AtomicInteger()
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val scheduler = coordinator(library, gateway, gate = countingGate(gateCalls))
            armed.set(true)
            try {
                scheduler.initializeScheduling()
                val plan = scheduler.schedulingPlan()
                assertEquals(clock.epochMs() + 2_000, plan.nextLocalEpochMs)
                assertEquals(clock.epochMs() + 86_400_000, plan.nextNetworkEpochMs)
                assertFalse(plan.hasDueWork)
                assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
                withTimeout(5_000) { scheduler.states.first { it[historical.record.id]?.status == DownloadStatus.VERIFYING } }
                assertEquals(DownloadStatus.COMPLETE, library.state.value.downloads[historical.record.id]?.status)
                assertEquals(0, validationCount.get())
                assertEquals(0, gateCalls.get())
                assertEquals(0, fileCalls.get())
                gateway.assertUnused()
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
        downloadCase(observedFiles) {
            val library = store()
            val historical = seedComplete(library)
            val gateCalls = AtomicInteger()
            val validating = barrier()
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val scheduler = coordinator(library, gateway, AssetValidator {
                validationCount.incrementAndGet(); validating.stop(); AssetValidation.Valid(31_234)
            }, countingGate(gateCalls))
            armed.set(true)
            scheduler.start(DownloadActivation.DURABLE_WORK)
            assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            delay(160) // Bounded observation; the eventual user check below proves the worker is active.
            assertEquals(0, fileReadsAndLists.get())
            assertEquals(0, validationCount.get())
            assertEquals(0, gateCalls.get())
            scheduler.start(DownloadActivation.USER_SESSION)
            validating.entered.await()
            assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
            scheduler.start(DownloadActivation.DURABLE_WORK) // Must not downgrade admitted checks.
            validating.release()
            withTimeout(5_000) { scheduler.states.first { it[historical.record.id]?.status == DownloadStatus.COMPLETE } }
            scheduler.awaitDeferred()
            scheduler.start(DownloadActivation.USER_SESSION)
            delay(120)
            assertEquals(1, validationCount.get())
            // USER_SESSION also admits the moved-from staging-directory sweep. Its separate
            // legitimate allowance is not a second validation of this historical asset.
            assertTrue(gateCalls.get() >= 1)
            assertTrue(fileReadsAndLists.get() > 0)
            gateway.assertUnused()
            armed.set(false)
        }
    }

    @Test fun futureTwentyFourHourRetryRemainsPendingRetiresAndReopensAtItsOriginalDeadline() = downloadCase {
        val first = store()
        val seeded = seedPending(first, status = DownloadStatus.WAITING_NETWORK)
        val deadline = clock.epochMs() + 86_400_000
        first.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(
            nextRetryEpochMs = deadline, failure = AppFailure(FailureCode.RATE_LIMITED, retryAfterMs = 86_400_000)))) }.getOrThrow()
        val beforeGateway = DownloadTestGateway().apply { denyAcquisition() }
        val beforeGates = AtomicInteger()
        val before = coordinator(first, beforeGateway, gate = countingGate(beforeGates))
        before.start(DownloadActivation.DURABLE_WORK)
        withTimeout(5_000) { before.hasPendingWork.first { it } }
        val candidate = assertIs<DownloadIdleDecision.Deferred>(before.idleDecision())
        assertEquals(deadline, candidate.plan.nextNetworkEpochMs)
        assertNull(candidate.plan.nextLocalEpochMs)
        assertTrue(before.confirmIdle(candidate.ticket) { true })
        assertEquals(0, beforeGates.get())
        beforeGateway.assertUnused()
        before.close(); first.close()
        val reopened = store()
        val gateway = DownloadTestGateway()
        val afterGates = AtomicInteger()
        val after = coordinator(reopened, gateway, gate = countingGate(afterGates))
        after.initializeScheduling()
        assertEquals(deadline, after.schedulingPlan().nextNetworkEpochMs)
        assertNotEquals(candidate.plan.key.process, after.schedulingPlan().key.process)
        clock.advance(86_399_999)
        after.start(DownloadActivation.DURABLE_WORK)
        assertFalse(after.schedulingPlan().hasDueWork)
        delay(120)
        gateway.assertUnused()
        clock.advance(1)
        eventually(reopened) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.queries.size)
        assertEquals(1, gateway.transfers.size)
        assertEquals(1, afterGates.get())
    }

    @Test fun reconnectDoesNotBypassTwoSecondRetryAndUnknownBlocksOnlyNetworkClaims() = downloadCase {
        val library = store()
        val waiting = seedPending(library, status = DownloadStatus.WAITING_NETWORK)
        val deadline = clock.epochMs() + 2_000
        library.update { it.copy(downloads = it.downloads + (waiting.record.id to waiting.record.copy(nextRetryEpochMs = deadline))) }.getOrThrow()
        val network = MutableStateFlow(DownloadNetworkState.UNKNOWN)
        val gateway = DownloadTestGateway()
        val scheduler = coordinator(library, gateway, network = network)
        scheduler.start(DownloadActivation.DURABLE_WORK)
        for (state in listOf(DownloadNetworkState.UNKNOWN, DownloadNetworkState.DISCONNECTED, DownloadNetworkState.CONNECTED)) {
            network.value = state
            val plan = scheduler.schedulingPlan()
            assertEquals(deadline, plan.nextNetworkEpochMs)
            assertFalse(plan.hasDueWork)
        }
        delay(120)
        gateway.assertUnused()
        network.value = DownloadNetworkState.DISCONNECTED
        clock.advance(2_000)
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        assertEquals(FailureCode.NETWORK_UNAVAILABLE, scheduler.schedulingPlan().blockedReason?.code)
        delay(120)
        gateway.assertUnused()
        network.value = DownloadNetworkState.CONNECTED
        eventually(library) { it.downloads[waiting.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun unactivatedCancelAndRemovePersistIntentButCannotStartMaintenanceBeforeCoverageActivation() = downloadCase {
        val library = store()
        val cancelling = seedPending(library, item("cancel"), bytes = authoredBytes)
        val removing = seedComplete(library, item("remove"))
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val gateCalls = AtomicInteger()
        val network = MutableStateFlow(DownloadNetworkState.DISCONNECTED)
        val scheduler = coordinator(library, gateway, gate = countingGate(gateCalls), network = network)
        scheduler.initializeScheduling()
        scheduler.cancel(cancelling.record.id)
        scheduler.remove(removing.record.id)
        val pending = library.flush().getOrThrow()
        assertTrue(pending.downloads.getValue(cancelling.record.id).cleanupPending)
        assertTrue(pending.downloads.getValue(removing.record.id).removeWhenClean)
        assertTrue(Files.exists(Path.of(cancelling.record.stagingPath!!)))
        assertTrue(Files.exists(Path.of(removing.record.asset!!.uri)))
        assertEquals(0, gateCalls.get())
        assertEquals(0, validationCount.get())
        scheduler.start(DownloadActivation.DURABLE_WORK)
        eventually(library) { snapshot -> removing.record.id !in snapshot.downloads &&
            snapshot.downloads[cancelling.record.id]?.let { !it.cleanupPending && it.status == DownloadStatus.CANCELLED } == true }
        assertFalse(Files.exists(Path.of(cancelling.record.stagingPath)))
        assertFalse(Files.exists(Path.of(removing.record.asset.uri)))
        assertEquals(2, gateCalls.get())
        gateway.assertUnused()
    }

    @Test fun acquisitionIsOwnedBeforeGateAckThroughActualResourceAndAllowanceClose() = downloadCase {
        val library = store()
        val acquiring = barrier(); val resourceClosing = barrier(); val allowanceClosing = barrier()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource {
                    override suspend fun close() { withContext(NonCancellable) { resourceClosing.stop() } }
                }) }
        }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                acquiring.stop()
                return DownloadExecutionLease { withContext(NonCancellable) { allowanceClosing.stop() } }
            }
        }
        val scheduler = coordinator(library, gateway, gate = gate)
        val id = scheduler.enqueue(item())
        scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        assertNotNull(library.flush().getOrThrow().downloads[id]?.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        gateway.assertUnused()
        acquiring.release()
        resourceClosing.entered.await()
        assertEquals(DownloadStatus.COMPLETE, library.flush().getOrThrow().downloads[id]?.status)
        assertNull(library.state.value.downloads[id]?.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        resourceClosing.release()
        allowanceClosing.entered.await()
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        allowanceClosing.release()
        val candidate = scheduler.awaitDeferred()
        assertTrue(scheduler.confirmIdle(candidate.ticket) { true })
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun maintenanceIsOwnedBeforeGateAckAndAfterRoomCompletionUntilNativeCloseReturns() = downloadCase {
        val library = store()
        val seeded = seedFinalization(library)
        val acquiring = barrier(); val closing = barrier()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                acquiring.stop()
                return DownloadExecutionLease { withContext(NonCancellable) { closing.stop() } }
            }
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val scheduler = coordinator(library, gateway, gate = gate)
        scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        assertNotNull(library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
        assertEquals(0, validationCount.get())
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        acquiring.release()
        closing.entered.await()
        val completed = library.flush().getOrThrow().downloads.getValue(seeded.record.id)
        assertEquals(DownloadStatus.COMPLETE, completed.status)
        assertNull(completed.leaseOwner)
        assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
        closing.release()
        assertTrue(scheduler.confirmIdle(scheduler.awaitDeferred().ticket) { true })
        gateway.assertUnused()
    }

    @Test fun independentBackgroundDrainWaitsForResourceAllowanceAndRoomFinishEvenAfterNativeLeasesReachZero() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val seeded = seedPending(library)
        val transferring = CompletableDeferred<Unit>()
        val resourceClosing = barrier(); val allowanceClosing = barrier(); val roomFinishing = barrier()
        val nativeLeases = AtomicInteger()
        val finishOnce = AtomicBoolean(true)
        fault.afterCommit = { snapshot ->
            val record = snapshot.downloads[seeded.record.id]
            if (record?.status == DownloadStatus.NEEDS_FOREGROUND && record.leaseOwner == null &&
                record.activeAttemptId == null && finishOnce.compareAndSet(true, false)) roomFinishing.stop()
        }
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource {
                    override suspend fun close() { withContext(NonCancellable) { resourceClosing.stop() } }
                }) }
            transfer = { call -> call.write(); transferring.complete(Unit); awaitCancellation() }
        }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                nativeLeases.incrementAndGet()
                return DownloadExecutionLease {
                    withContext(NonCancellable) { allowanceClosing.stop(); nativeLeases.decrementAndGet() }
                }
            }
        }
        val scheduler = coordinator(library, gateway, gate = gate)
        scheduler.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { scheduler.drainForBackground() }
            try {
                resourceClosing.entered.await()
                assertFalse(draining.isCompleted)
                assertEquals(DownloadStatus.NEEDS_FOREGROUND, library.flush().getOrThrow().downloads[seeded.record.id]?.status)
                assertEquals(1, nativeLeases.get())
                resourceClosing.release()
                allowanceClosing.entered.await()
                assertFalse(draining.isCompleted)
                allowanceClosing.release()
                roomFinishing.entered.await()
                assertEquals(0, nativeLeases.get(), "The native allowance closed before shared Room unwind finished")
                assertFalse(draining.isCompleted, "Zero native leases must not stand in for the registered-owner join")
                assertNotNull(files.tryTransferSlot(1)).close()
                roomFinishing.release()
                assertNull(draining.await().checkpointFailure)
            } finally {
                resourceClosing.release(); allowanceClosing.release(); roomFinishing.release()
            }
        }
        val drained = library.flush().getOrThrow().downloads.getValue(seeded.record.id)
        assertNull(drained.leaseOwner)
        assertNull(drained.activeAttemptId)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, drained.status)
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun failedBackgroundCheckpointAcknowledgesOnlyAfterAcquisitionAndMaintenanceOwnersDrain() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val acquiring = seedPending(library, item("acquiring"))
        val maintaining = seedFinalization(library, item("maintaining"))
        val transferEntered = CompletableDeferred<Unit>(); val validationEntered = CompletableDeferred<Unit>()
        val resourceClosing = barrier(); val validatorClosing = barrier()
        val failedOnce = AtomicBoolean(true)
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_background_checkpoint_failure")
        val gateCalls = AtomicInteger()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource {
                    override suspend fun close() { withContext(NonCancellable) { resourceClosing.stop() } }
                }) }
            transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
        }
        val validator = AssetValidator {
            validationEntered.complete(Unit)
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { validatorClosing.stop() } }
        }
        val scheduler = coordinator(library, gateway, validator, countingGate(gateCalls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        transferEntered.await(); validationEntered.await()
        fault.afterTransform = { _, next ->
            if (next.downloads[acquiring.record.id]?.status == DownloadStatus.NEEDS_FOREGROUND &&
                next.downloads[maintaining.record.id]?.status == DownloadStatus.NEEDS_FOREGROUND &&
                failedOnce.compareAndSet(true, false)) throw VeyraException(failure)
        }
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { scheduler.drainForBackground() }
            try {
                resourceClosing.entered.await(); validatorClosing.entered.await()
                assertFalse(failedOnce.get(), "The real SQLite checkpoint must have rolled back")
                assertFalse(draining.isCompleted, "Checkpoint failure cannot acknowledge undrained native owners")
                resourceClosing.release()
                eventually(library) { it.downloads[acquiring.record.id]?.leaseOwner == null }
                assertFalse(draining.isCompleted, "The separate maintenance owner still requires a join")
                validatorClosing.release()
                assertEquals(failure, draining.await().checkpointFailure)
            } finally { resourceClosing.release(); validatorClosing.release() }
        }
        val drained = library.flush().getOrThrow()
        for (id in listOf(acquiring.record.id, maintaining.record.id)) {
            assertEquals(DownloadStatus.NEEDS_FOREGROUND, drained.downloads[id]?.status)
            assertNull(drained.downloads[id]?.leaseOwner)
        }
        assertNull(drained.downloads.getValue(acquiring.record.id).activeAttemptId)
        assertEquals(maintaining.record.activeAttemptId, drained.downloads.getValue(maintaining.record.id).activeAttemptId)
        assertEquals(maintaining.intent, drained.downloadFinalizations[maintaining.record.id])
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(drained.downloads.getValue(acquiring.record.id).stagingPath!!)))
        assertTrue(Files.exists(Path.of(maintaining.intent!!.stagingPath)))
        assertEquals(failure, scheduler.lastFailure.value)
        assertFalse(scheduler.schedulingPlan().hasDueWork)
        assertEquals(2, gateCalls.get())
        assertEquals(1, gateway.transfers.size)
        // Existing UI/iOS callers keep typed pause failure, but only after the same drain seam.
        fault.afterTransform = { _, _ -> throw VeyraException(failure) }
        try { assertEquals(failure, assertFailsWith<VeyraException> { scheduler.pauseForBackground() }.failure) }
        finally { fault.afterTransform = { _, _ -> } }
    }

    @Test fun resumeDuringSuspendedFailingCheckpointIsRejectedWithoutAdmittingAnUncapturedOwnerOrReplayingLater() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val first = seedPending(library)
        val queuedItem = item("queued-during-drain")
        val transferring = CompletableDeferred<Unit>()
        val checkpoint = barrier(); val resourceClosing = barrier()
        val failOnce = AtomicBoolean(true)
        val calls = AtomicInteger(); val gateCalls = AtomicInteger()
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_checkpoint_result_failure")
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource {
                    override suspend fun close() {
                        if (binding.mediaId == first.item.id) withContext(NonCancellable) { resourceClosing.stop() }
                    }
                }) }
            transfer = { call ->
                if (calls.incrementAndGet() == 1) { call.write(); transferring.complete(Unit); awaitCancellation() }
                else call.complete()
            }
        }
        val scheduler = coordinator(library, gateway, gate = countingGate(gateCalls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        fault.afterCommit = { snapshot ->
            val record = snapshot.downloads[first.record.id]
            if (record?.status == DownloadStatus.NEEDS_FOREGROUND && record.leaseOwner != null && failOnce.compareAndSet(true, false)) {
                // Real commit, then a suspended/failed acknowledgment. The independent owner
                // drain must remain fenced even when publication success is ambiguous to its caller.
                checkpoint.stop()
                throw VeyraException(failure)
            }
        }
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { scheduler.drainForBackground() }
            try {
                checkpoint.entered.await()
                val resuming = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.resumePending() } }
                val enqueuing = async(start = CoroutineStart.UNDISPATCHED) { scheduler.enqueue(queuedItem) }
                assertTrue(resuming.isCompleted, "Resume must fail at admission, not wait behind the checkpoint")
                assertEquals(FailureCode.BACKGROUND_RESTRICTED,
                    assertIs<VeyraException>(resuming.await().exceptionOrNull()).failure.code)
                assertFalse(enqueuing.isCompleted)
                checkpoint.release()
                resourceClosing.entered.await()
                val queuedId = enqueuing.await()
                assertEquals(DownloadStatus.NEEDS_FOREGROUND, library.flush().getOrThrow().downloads[queuedId]?.status)
                assertEquals(FailureCode.BACKGROUND_RESTRICTED,
                    assertFailsWith<VeyraException> { scheduler.resumePending() }.failure.code)
                scheduler.start(DownloadActivation.DURABLE_WORK) // Pump/start cannot clear the fence either.
                assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
                assertEquals(1, gateCalls.get())
                assertEquals(1, gateway.transfers.size)
                resourceClosing.release()
                assertEquals(failure, draining.await().checkpointFailure)
            } finally { checkpoint.release(); resourceClosing.release() }
        }
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        delay(120)
        assertEquals(1, gateCalls.get(), "Rejected foreground resume must not be replayed after ACK")
        assertEquals(1, gateway.transfers.size)
        scheduler.resumePending() // A NEW explicit foreground announcement after the drain acknowledgment.
        eventually(library) { snapshot -> listOf(first.record.id, files.key(queuedItem.id)).all {
            snapshot.downloads[it]?.status == DownloadStatus.COMPLETE
        } }
        assertEquals(3, gateway.transfers.size)
    }

    @Test fun cancellingIndependentBackgroundDrainCannotSkipJoiningPendingNativeAcquisition() = downloadCase {
        val library = store()
        val acquiring = CompletableDeferred<Unit>()
        val nativeClosing = barrier()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                acquiring.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { nativeClosing.stop() } }
            }
        }
        val scheduler = coordinator(library, gateway, gate = gate)
        val id = scheduler.enqueue(item())
        scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.await()
        coroutineScope {
            val callerFinished = AtomicBoolean()
            val draining = launch(start = CoroutineStart.UNDISPATCHED) {
                try { scheduler.drainForBackground() }
                finally { callerFinished.set(true) }
            }
            try {
                nativeClosing.entered.await()
                draining.cancel()
                assertNull(withTimeoutOrNull(100) { draining.join(); true }, "Cancelled caller still owes the accepted join")
                assertFalse(callerFinished.get())
                assertEquals(DownloadIdleDecision.OwnedWork, scheduler.idleDecision())
                nativeClosing.release()
                draining.join()
                assertTrue(draining.isCancelled)
                assertTrue(callerFinished.get())
            } finally { nativeClosing.release() }
        }
        val drained = library.flush().getOrThrow().downloads.getValue(id)
        assertEquals(DownloadStatus.NEEDS_FOREGROUND, drained.status)
        assertNull(drained.leaseOwner)
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        gateway.assertUnused()
    }

    @Test fun unknownOrCancelledCheckpointStillJoinsOwnersButNeverAcknowledgesOrSilentlyRetriesForegroundResume() {
        for (unknown in listOf(CancellationException("authored checkpoint cancellation"), AssertionError("authored unknown checkpoint"))) {
            downloadCase {
                val fault = persistence()
                val library = store(fault)
                val transferring = CompletableDeferred<Unit>()
                val resourceClosing = barrier()
                val resourceClosed = AtomicBoolean(); val allowanceClosed = AtomicBoolean()
                val failedOnce = AtomicBoolean(true)
                val gateway = DownloadTestGateway().apply {
                    plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                        mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                        resource = object : PlaybackResource {
                            override suspend fun close() = withContext(NonCancellable) {
                                resourceClosing.stop(); resourceClosed.set(true)
                            }
                        }) }
                    transfer = { call -> call.write(); transferring.complete(Unit); awaitCancellation() }
                }
                val gate = object : DownloadExecutionGate {
                    override suspend fun acquire(onExpiration: suspend () -> Unit) = DownloadExecutionLease { allowanceClosed.set(true) }
                }
                val scheduler = coordinator(library, gateway, gate = gate)
                val id = scheduler.enqueue(item())
                scheduler.start(DownloadActivation.DURABLE_WORK)
                transferring.await()
                fault.afterTransform = { _, next ->
                    if (next.downloads[id]?.status == DownloadStatus.NEEDS_FOREGROUND && failedOnce.compareAndSet(true, false))
                        throw unknown
                }
                coroutineScope {
                    val draining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.drainForBackground() } }
                    try {
                        resourceClosing.entered.await()
                        assertFalse(failedOnce.get())
                        assertFalse(draining.isCompleted, "Even an unknown checkpoint error must first join native ownership")
                        assertFalse(resourceClosed.get())
                        assertFalse(allowanceClosed.get())
                        resourceClosing.release()
                        assertEquals(unknown::class, assertNotNull(draining.await().exceptionOrNull())::class)
                    } finally { resourceClosing.release() }
                }
                assertTrue(resourceClosed.get())
                assertTrue(allowanceClosed.get())
                assertNull(library.flush().getOrThrow().downloads[id]?.leaseOwner)
                assertNotNull(files.tryTransferSlot(1)).close()
                val restriction = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
                assertEquals(restriction, scheduler.lastFailure.value)
                assertEquals(restriction, scheduler.schedulingPlan().blockedReason)
                for (retry in listOf<suspend () -> Unit>(
                    { scheduler.resumePending() }, { scheduler.drainForBackground(); Unit }, { scheduler.idleDecision(); Unit },
                )) {
                    assertEquals(restriction, assertFailsWith<VeyraException> { retry() }.failure)
                }
                assertEquals(1, gateway.transfers.size)
                assertEquals(restriction, scheduler.lastFailure.value, "Rejected retries retain the first visible typed failure")
            }
        }
    }

    @Test fun backgroundDrainJoinsAlreadyClosingOwnersWithoutReopeningOrCheckpointingClosedCoordinator() = downloadCase {
        val fault = persistence()
        val writes = AtomicInteger()
        fault.afterTransform = { _, _ -> writes.incrementAndGet() }
        val library = store(fault)
        val transferring = CompletableDeferred<Unit>()
        val resourceClosing = barrier()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> ResolvedPlayable(binding.mediaId, "https://fixture.example.test/non-audio",
                mimeType = "audio/mpeg", codecs = "mp3", representationId = authoredRepresentation, executionBinding = binding,
                resource = object : PlaybackResource {
                    override suspend fun close() { withContext(NonCancellable) { resourceClosing.stop() } }
                }) }
            transfer = { call -> call.write(); transferring.complete(Unit); awaitCancellation() }
        }
        val scheduler = coordinator(library, gateway)
        val id = scheduler.enqueue(item())
        scheduler.start(DownloadActivation.DURABLE_WORK)
        transferring.await()
        coroutineScope {
            val closing = async { scheduler.close() }
            try {
                resourceClosing.entered.await()
                assertFailsWith<IllegalStateException> { scheduler.schedulingPlan() }
                val writesBeforeDrain = writes.get()
                val draining = async(start = CoroutineStart.UNDISPATCHED) { scheduler.drainForBackground() }
                assertFalse(draining.isCompleted, "The closed latch is not the registry drain")
                assertFalse(closing.isCompleted)
                assertEquals(writesBeforeDrain, writes.get())
                resourceClosing.release()
                assertNull(draining.await().checkpointFailure)
                closing.await()
            } finally { resourceClosing.release() }
        }
        assertNull(library.flush().getOrThrow().downloads[id]?.leaseOwner)
        library.close()
        assertNull(scheduler.drainForBackground().checkpointFailure)
        scheduler.pauseForBackground()
    }

    @Test fun ticketsAreExactLatestInstancesAndEveryConfirmationAttemptIsSingleUse() = downloadCase {
        val scheduler = coordinator(store())
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

    @Test fun unrelatedPlaybackProgressAndPresentationChangesDoNotStarveIdleRetirement() = downloadCase {
        val library = store()
        val seeded = seedPending(library, status = DownloadStatus.WAITING_NETWORK)
        library.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(nextRetryEpochMs = clock.epochMs() + 86_400_000))) }.getOrThrow()
        val scheduler = coordinator(library)
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val priorRevision = library.state.value.revision
        repeat(3) { index ->
            library.update { snapshot -> snapshot.copy(
                progress = snapshot.progress + (seeded.item.id to MediaProgress(positionMs = index * 1_000L)),
                settings = snapshot.settings.copy(textScale = 1.5f, theme = ThemeMode.DARK),
                downloads = snapshot.downloads + (seeded.record.id to snapshot.downloads.getValue(seeded.record.id).copy(
                    bytesDownloaded = index.toLong(), totalBytes = 10, updatedEpochMs = clock.epochMs() + index,
                    lastProgressEpochMs = clock.epochMs() + index)),
                navigation = snapshot.navigation + ("authored-view" to index.toString()),
            ) }.getOrThrow()
            assertEquals(candidate.plan, scheduler.schedulingPlan())
        }
        assertTrue(library.state.value.revision > priorRevision)
        assertTrue(scheduler.confirmIdle(candidate.ticket) { true })
    }

    @Test fun anotherRoomConnectionCanInvalidateTicketEvenWhenItsInvalidationHintIsUnavailable() = downloadCase {
        val writer = store()
        val seeded = seedPending(writer, status = DownloadStatus.WAITING_NETWORK)
        val originalDeadline = clock.epochMs() + 86_400_000
        writer.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(nextRetryEpochMs = originalDeadline))) }.getOrThrow()
        val room = persistence()
        val noHints = object : LibraryPersistence by room {
            override fun changes() = emptyFlow<Unit>()
        }
        val reader = store(noHints)
        val scheduler = coordinator(reader)
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val earlierDeadline = clock.epochMs() + 2_000
        writer.update { snapshot -> snapshot.copy(downloads = snapshot.downloads +
            (seeded.record.id to snapshot.downloads.getValue(seeded.record.id).copy(nextRetryEpochMs = earlierDeadline))) }.getOrThrow()
        assertEquals(originalDeadline, reader.state.value.downloads[seeded.record.id]?.nextRetryEpochMs,
            "The reader is deliberately stale; only an authoritative Room read can notice this write")
        assertFalse(scheduler.confirmIdle(candidate.ticket) { error("A stale wake plan cannot retire") })
        val fresh = scheduler.schedulingPlan()
        assertEquals(earlierDeadline, fresh.nextNetworkEpochMs)
        assertNotEquals(candidate.plan.key, fresh.key)
    }

    @Test fun enqueueResumeCancelAndPolicyChangesDuringWakeAckInvalidateOldTicket() = downloadCase {
        val library = store()
        val seeded = seedPending(library, status = DownloadStatus.WAITING_NETWORK)
        val scheduler = coordinator(library)
        scheduler.initializeScheduling()
        suspend fun checkMutation(mutate: suspend () -> Unit) {
            library.update { snapshot -> snapshot.copy(downloads = snapshot.downloads.mapValues { (_, record) ->
                record.copy(status = DownloadStatus.WAITING_NETWORK, cleanupPending = false, userPaused = false,
                    nextRetryEpochMs = clock.epochMs() + 86_400_000)
            }, settings = snapshot.settings.copy(offlineOnly = false)) }.getOrThrow()
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
        checkMutation { scheduler.enqueue(item("new-item")) }
        checkMutation { scheduler.resume(seeded.record.id) }
        checkMutation { scheduler.cancel(seeded.record.id) }
        checkMutation { library.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow() }
        checkMutation { scheduler.pauseForBackground() }
    }

    @Test fun connectedDueWorkAndHostEpochChangesInvalidateDeferredTickets() = downloadCase {
        val library = store()
        seedPending(library)
        val network = MutableStateFlow(DownloadNetworkState.DISCONNECTED)
        val scheduler = coordinator(library, network = network)
        scheduler.initializeScheduling()
        val beforeConnection = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        network.value = DownloadNetworkState.CONNECTED
        assertFalse(scheduler.confirmIdle(beforeConnection.ticket) { error("Due new network claim cannot retire") })
        network.value = DownloadNetworkState.DISCONNECTED
        val beforeHostChange = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        scheduler.pauseForBackground()
        scheduler.resumePending()
        assertFalse(scheduler.confirmIdle(beforeHostChange.ticket) { error("Changed host generation cannot retire") })
    }

    @Test fun dueCrossingInsideFreshReadCannotConfirmEarlierFutureTicket() = downloadCase {
        val real = persistence()
        val nextRead = AtomicReference<DownloadBarrier?>()
        val persistence = object : LibraryPersistence by real {
            // Only this authored fixture disables invalidation hints, so the explicit read barrier
            // has a deterministic owner. Every actual read/transaction is still real Room.
            override fun changes() = emptyFlow<Unit>()
            override suspend fun read(): AppSnapshot {
                val snapshot = real.read()
                nextRead.getAndSet(null)?.stop()
                return snapshot
            }
        }
        val library = store(persistence)
        val seeded = seedPending(library, status = DownloadStatus.WAITING_NETWORK)
        library.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(nextRetryEpochMs = clock.epochMs() + 2_000))) }.getOrThrow()
        val scheduler = coordinator(library)
        scheduler.initializeScheduling()
        val candidate = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        val reading = barrier()
        nextRead.set(reading)
        coroutineScope {
            val retired = async { scheduler.confirmIdle(candidate.ticket) { error("Clock crossed during read") } }
            reading.entered.await()
            clock.advance(2_000)
            reading.release()
            assertFalse(retired.await())
        }
    }

    @Test fun secondTimeCheckRejectsCrossingAfterTheFreshPlanWasBuilt() = downloadCase {
        val library = store()
        val seeded = seedPending(library, status = DownloadStatus.WAITING_NETWORK)
        val deadline = clock.epochMs() + 2_000
        library.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(nextRetryEpochMs = deadline))) }.getOrThrow()
        val armed = AtomicBoolean()
        val reads = AtomicInteger()
        val baseClock = clock
        val crossingClock = object : Clock {
            override fun epochMs(): Long = if (armed.get() && reads.incrementAndGet() >= 2) deadline else baseClock.epochMs()
            override fun monotonicMs(): Long = baseClock.monotonicMs()
        }
        val scheduler = coordinator(library, schedulerClock = crossingClock)
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

    @Test fun refusedPhysicalTransferSlotHasBoundedLocalBackoffWithoutAnyGateOrSourceClaim() = downloadCase {
        val library = store()
        val seeded = seedPending(library)
        library.update { it.copy(settings = it.settings.copy(downloadParallelism = 1)) }.getOrThrow()
        val held = assertNotNull(files.tryTransferSlot(1))
        releaseOnClose += { held.close() }
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway()
        val scheduler = coordinator(library, gateway, gate = countingGate(calls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        val delays = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L)
        for ((index, delay) in delays.withIndex()) {
            val deadline = clock.epochMs() + delay
            val deferred = scheduler.awaitPlan { !it.hasDueWork && it.nextLocalEpochMs == deadline }
            assertNull(deferred.nextNetworkEpochMs)
            assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            assertEquals(0, calls.get())
            assertEquals(0, library.flush().getOrThrow().downloads[seeded.record.id]?.attemptCount)
            gateway.assertUnused()
            if (index != delays.lastIndex) clock.advance(delay)
        }
        assertNull(files.tryTransferSlot(1), "Failed contenders must not release the existing slot owner")
        held.close()
        clock.advance(delays.last() - 1)
        assertFalse(scheduler.schedulingPlan().hasDueWork)
        delay(120)
        gateway.assertUnused()
        clock.advance(1)
        eventually(library) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, calls.get())
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun refusedFinalizationFileLeaseDefersLocallyBeforeValidationOrNativeAcquisition() = downloadCase {
        val library = store()
        val seeded = seedFinalization(library)
        val held = assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath)))
        releaseOnClose += { held.close() }
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val scheduler = coordinator(library, gateway, gate = countingGate(calls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        val deferred = scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        assertNull(deferred.nextNetworkEpochMs)
        assertEquals(0, validationCount.get())
        assertEquals(0, calls.get())
        assertNull(library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
        held.close()
        clock.advance(5_000)
        eventually(library) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, validationCount.get())
        assertEquals(1, calls.get())
        gateway.assertUnused()
    }

    @Test fun rolledBackAcquisitionClaimReleasesFilesAndDefersInsteadOfKeepingAnEmptyOwnerAlive() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val seeded = seedPending(library)
        val blocked = AtomicBoolean(true)
        fault.afterTransform = { before, next ->
            if (blocked.get() && before.downloads[seeded.record.id]?.leaseOwner == null &&
                next.downloads[seeded.record.id]?.activeAttemptId != null)
                throw IOException("Authored acquisition claim rollback")
        }
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway()
        val scheduler = coordinator(library, gateway, gate = countingGate(calls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        val unchanged = library.flush().getOrThrow()
        assertEquals(seeded.record, unchanged.downloads[seeded.record.id])
        assertTrue(unchanged.downloadAttempts.isEmpty())
        assertNotNull(files.tryTransferSlot(1)).close()
        assertEquals(0, calls.get())
        gateway.assertUnused()
        assertTrue(scheduler.confirmIdle(scheduler.awaitDeferred().ticket) { true })
        blocked.set(false)
        clock.advance(5_000)
        eventually(library) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.transfers.size)
    }

    @Test fun rolledBackMaintenanceClaimKeepsIntentAndHasLocalCooldownBeforeRetry() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val seeded = seedFinalization(library)
        val blocked = AtomicBoolean(true)
        fault.afterTransform = { before, next ->
            if (blocked.get() && before.downloads[seeded.record.id]?.leaseOwner == null &&
                next.downloads[seeded.record.id]?.leaseOwner != null)
                throw IOException("Authored maintenance claim rollback")
        }
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val scheduler = coordinator(library, gateway, gate = countingGate(calls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        val unchanged = library.flush().getOrThrow()
        assertEquals(seeded.record, unchanged.downloads[seeded.record.id])
        assertEquals(seeded.intent, unchanged.downloadFinalizations[seeded.record.id])
        assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath))).close()
        assertEquals(0, calls.get())
        assertEquals(0, validationCount.get())
        blocked.set(false)
        clock.advance(5_000)
        eventually(library) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, calls.get())
        gateway.assertUnused()
    }

    @Test fun failedExpiredLeaseRecoveryIsDeferredWithoutForgingAcknowledgementOrStartingSource() = downloadCase {
        val fault = persistence()
        val library = store(fault)
        val seeded = seedPending(library, status = DownloadStatus.RUNNING, leaseOwner = "authored-dead-owner",
            leaseUntil = clock.epochMs() - 1)
        val blocked = AtomicBoolean(true)
        fault.afterTransform = { before, next ->
            if (blocked.get() && before.downloads[seeded.record.id]?.leaseOwner == "authored-dead-owner" &&
                next.downloads[seeded.record.id]?.leaseOwner == null)
                throw IOException("Authored expired-owner recovery rollback")
        }
        val calls = AtomicInteger()
        val gateway = DownloadTestGateway()
        val scheduler = coordinator(library, gateway, gate = countingGate(calls))
        scheduler.start(DownloadActivation.DURABLE_WORK)
        scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
        val unchanged = library.flush().getOrThrow()
        assertEquals(seeded.record, unchanged.downloads[seeded.record.id])
        assertNull(unchanged.downloadAttempts[seeded.attempt!!.id]?.finishedEpochMs)
        assertEquals(0, calls.get())
        gateway.assertUnused()
        assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
        blocked.set(false)
        clock.advance(5_000)
        eventually(library) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, gateway.transfers.size)
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
        downloadCase(filesWithFailure) {
            val fault = persistence()
            val library = store(fault)
            val seeded = seedComplete(library)
            target.set(seeded.record.asset!!.uri)
            val cleanup = seeded.record.copy(status = DownloadStatus.CANCELLED, userPaused = true,
                cleanupPending = true, removeWhenClean = true)
            library.update { it.copy(downloads = it.downloads + (cleanup.id to cleanup)) }.getOrThrow()
            fault.afterTransform = { _, next ->
                if (blocked.get() && next.downloads[cleanup.id]?.failure != null)
                    throw IOException("Authored rollback of cleanup error/backoff persistence")
            }
            val calls = AtomicInteger()
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val scheduler = coordinator(library, gateway, gate = countingGate(calls))
            scheduler.start(DownloadActivation.DURABLE_WORK)
            scheduler.awaitPlan { it.nextLocalEpochMs == clock.epochMs() + 5_000 && !it.hasDueWork }
            assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision())
            val retained = library.flush().getOrThrow().downloads.getValue(cleanup.id)
            assertEquals(cleanup.copy(updatedEpochMs = clock.epochMs()), retained,
                "Failed error persistence cannot erase the cleanup journal; only the admitted claim's timestamp changed")
            assertTrue(Files.exists(Path.of(cleanup.asset!!.uri)))
            assertEquals(1, calls.get())
            blocked.set(false)
            clock.advance(5_000)
            eventually(library) { cleanup.id !in it.downloads }
            assertFalse(Files.exists(Path.of(cleanup.asset.uri)))
            assertEquals(2, calls.get())
            gateway.assertUnused()
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
