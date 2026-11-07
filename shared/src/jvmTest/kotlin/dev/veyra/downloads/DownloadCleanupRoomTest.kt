package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.model.*
import dev.veyra.playback.LocalMediaValidationReaderRelease
import dev.veyra.playback.LocalMediaValidationReaderUnacknowledged
import dev.veyra.data.PhysicalDownloadCommit
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.PlaybackResource
import dev.veyra.playback.ResolvedPlayable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** REAL SQLite and OS file leases; fake native/central handles and authored NON-AUDIO bytes. */
class DownloadCleanupRoomTest {
    @Test fun resourceCloseFailureRetainsNativeFileSlotAndRoomOwnershipUntilExplicitClose() = executorSchema3Case {
        // EX16: the removed PlaybackResource is now the EXACT original local reader receipt.
        val owner = owner()
        owner.expectedCloseFailure = executorRestartRequired
        val validating = barrier()
        val nativeReleased = AtomicBoolean()
        releaseOnClose += { nativeReleased.set(true) }
        val originalDestructionAttempts = AtomicInteger()
        val releaseObservations = AtomicInteger()
        val reader = LocalMediaValidationReaderRelease { releaseObservations.incrementAndGet(); nativeReleased.get() }
        owner.validation = { input ->
            withContext(NonCancellable) { validating.stop() }
            originalDestructionAttempts.incrementAndGet()
            throw LocalMediaValidationReaderUnacknowledged(input, reader)
        }
        owner.coordinator.initializeScheduling()
        val oldTicket = assertIs<DownloadIdleDecision.Deferred>(owner.coordinator.idleDecision()).ticket
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val claimed = join(owner, id)
        coroutineScope {
            val paused = async { runCatching { owner.coordinator.pause(id) } }
            awaitJob(owner, id) { it.claims.single().intent == DownloadBatchIntent.PAUSED }
            validating.release()
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
                assertIs<VeyraException>(paused.await().exceptionOrNull()).failure)
        }
        awaitCondition { owner.coordinator.lastFailure.value == executorRestartRequired }
        assertBlocked(owner.coordinator)
        val retained = join(owner, id)
        assertEquals(claimed.job.leaseOwner, retained.job.leaseOwner)
        assertEquals(claimed.job.activeAttemptId, retained.job.activeAttemptId)
        assertEquals(selected.context, retained.producerContext)
        assertNull(retained.activeAttempt?.finishedEpochMs)
        val path = assertNotNull(retained.job.stagingPath)
        assertNull(probeFiles.tryLeasePaths(listOf(path)))
        assertNull(probeFiles.tryTransferSlot(1))
        assertEquals(1, originalDestructionAttempts.get())
        assertEquals(1, releaseObservations.get())
        assertEquals(0, owner.gate.closes.get())
        assertTrue(owner.repository.finishCalls.isEmpty())
        var retired = false
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> {
            owner.coordinator.confirmIdle(oldTicket) { retired = true; true }
        }.failure)
        assertFalse(retired)
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.start() }.failure)
        // STRICT production hold EX16: no fixture callback or closed-host guard may mask admission.
        val secondSelection = select(owner, item("rejected"), "REJECTED001")
        val beforeRejected = owner.store.acquisitionView().acquisition
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.resume(id) }.failure)
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { enqueue(owner, secondSelection) }.failure)
        val afterRejected = owner.store.acquisitionView().acquisition
        assertEquals(beforeRejected.physicalDownloads, afterRejected.physicalDownloads)
        assertEquals(beforeRejected.claims, afterRejected.claims)
        assertEquals(1, afterRejected.physicalDownloads.size)
        nativeReleased.set(true) // Independent original-reader receipt; explicit close only OBSERVES it.
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, originalDestructionAttempts.get(), "Never destroy the original reader a second time")
        assertEquals(2, releaseObservations.get())
        assertEquals(1, owner.gate.closes.get())
        assertEquals(1, owner.repository.finishCalls.size)
        val released = join(owner, id)
        assertNull(released.job.leaseOwner)
        assertNull(released.activeAttempt)
        assertEquals(DownloadStatus.PAUSED, released.job.status)
        assertNotNull(probeFiles.tryLeasePaths(listOf(path))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertEquals(2, releaseObservations.get())
        assertEquals(1, owner.gate.closes.get())
        assertEquals(1, owner.repository.finishCalls.size)
    }

    @Test fun resourceCancellationAndAssertionFailuresNeverAcknowledgeOrAutomaticallyRetry() {
        for (failure in listOf<() -> Throwable>(
            { CancellationException("Authored original-reader receipt cancellation") },
            { AssertionError("Authored original-reader receipt failure") },
        )) executorSchema3Case {
            // EX15: throwing release OBSERVATION retains the native owner; no native re-close.
            val owner = owner()
            owner.expectedCloseFailure = executorRestartRequired
            val validating = barrier()
            val nativeReleased = AtomicBoolean()
            releaseOnClose += { nativeReleased.set(true) }
            val destructions = AtomicInteger()
            val observations = AtomicInteger()
            val originalFailure = failure()
            val reader = LocalMediaValidationReaderRelease {
                observations.incrementAndGet()
                if (!nativeReleased.get()) throw originalFailure
                true
            }
            owner.validation = { input ->
                withContext(NonCancellable) { validating.stop() }
                destructions.incrementAndGet()
                throw LocalMediaValidationReaderUnacknowledged(input, reader)
            }
            val (id, _) = enqueue(owner)
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            validating.entered.await()
            coroutineScope {
                val draining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.drainForBackground() } }
                assertFalse(draining.isCompleted)
                validating.release()
                assertEquals(executorRestartRequired, assertIs<VeyraException>(draining.await().exceptionOrNull()).failure)
            }
            assertBlocked(owner.coordinator)
            owner.coordinator.onAcquisitionBatchChanged()
            delay(180)
            assertEquals(1, observations.get(), "Wake/reconciliation cannot retry a failed reader observation")
            repeat(2) { retry ->
                assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
                assertEquals(retry + 2, observations.get(), "One observation per explicit close, never native destruction")
                assertEquals(1, destructions.get())
                assertTrue(owner.coordinator.hasPendingCleanupOwners)
                assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
                assertEquals(0, owner.gate.closes.get())
                val retained = join(owner, id)
                assertNotNull(retained.job.leaseOwner)
                assertNotNull(retained.activeAttempt)
                assertNull(probeFiles.tryLeasePaths(listOf(assertNotNull(retained.job.stagingPath))))
                assertNull(probeFiles.tryTransferSlot(1))
            }
            nativeReleased.set(true)
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertFalse(owner.coordinator.hasPendingCleanupOwners)
            assertEquals(1, destructions.get())
            assertEquals(4, observations.get())
            assertEquals(1, owner.gate.closes.get())
            assertEquals(1, owner.repository.finishCalls.size)
        }
    }

    @Test fun allowanceFailureAfterNativeLeaseZeroRetainsFilesWithoutReclosingConsumedResource() = executorSchema3Case {
        // EX08: positive local validation is consumed once; native allowance count0 is NOT an ACK.
        val nativeLeases = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val callback = AtomicReference<suspend () -> Unit>()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                callback.set(onExpiration); nativeLeases.incrementAndGet()
                return DownloadExecutionLease {
                    if (allowanceCloses.incrementAndGet() == 1) {
                        nativeLeases.decrementAndGet()
                        throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_close_failure"))
                    }
                }
            }
        }
        val owner = owner(executionGate = gate)
        owner.expectedCloseFailure = executorRestartRequired
        val validating = barrier()
        owner.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val claimed = join(owner, id)
        coroutineScope {
            val paused = async { runCatching { owner.coordinator.pause(id) } }
            awaitJob(owner, id) { it.claims.single().intent == DownloadBatchIntent.PAUSED }
            validating.release()
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
                assertIs<VeyraException>(paused.await().exceptionOrNull()).failure)
        }
        assertEquals(0, nativeLeases.get())
        assertBlocked(owner.coordinator)
        assertEquals(1, owner.validations.size)
        assertEquals(1, allowanceCloses.get())
        val path = assertNotNull(claimed.job.stagingPath)
        assertNull(probeFiles.tryLeasePaths(listOf(path)))
        assertNull(probeFiles.tryTransferSlot(1))
        assertEquals(claimed.job.activeAttemptId, join(owner, id).job.activeAttemptId)
        callback.get().invoke()
        assertBlocked(owner.coordinator)
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, owner.validations.size, "A successfully released local reader is never reopened")
        assertEquals(2, allowanceCloses.get())
        assertNull(join(owner, id).job.leaseOwner)
        assertNotNull(probeFiles.tryLeasePaths(listOf(path))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        val revision = owner.store.acquisitionView().snapshot.revision
        callback.get().invoke()
        assertEquals(revision, owner.store.acquisitionView().snapshot.revision)
    }

    @Test fun maintenanceAllowanceFailureRetainsFinalizationFilesAndRoomClaimThroughIndependentDrain() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val validating = CompletableDeferred<Unit>()
        val allowConsumption = AtomicBoolean()
        val allowanceCloses = AtomicInteger()
        owner.gate.beforeClose = {
            if (allowanceCloses.incrementAndGet() == 1 && !allowConsumption.get())
                throw IOException("Authored unconsumed maintenance allowance")
        }
        owner.validation = { validating.complete(Unit); awaitCancellation() }
        owner.denyFinalizationSchema3Acquisition()
        val scheduler = owner.coordinator
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            validating.await()
            val claimed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seeded.record.id)
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.drainForBackground() }.failure)
            assertMaintenanceBlocked(scheduler)
            val retained = owner.store.acquisitionView().acquisition
            assertEquals(claimed.leaseOwner, retained.physicalDownloads[seeded.record.id]?.leaseOwner)
            assertEquals(claimed.activeAttemptId, retained.physicalDownloads[seeded.record.id]?.activeAttemptId)
            assertEquals(seeded.intent, retained.physicalFinalizations[seeded.record.id])
            assertNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath)))
            assertNull(files.tryLeasePaths(listOf(seeded.intent.finalPath)))
            assertNotNull(files.tryTransferSlot(1)).close() // Maintenance never owned a transfer slot.
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, allowanceCloses.get())
            assertNull(owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
            assertEquals(seeded.intent, owner.store.acquisitionView().acquisition.physicalFinalizations[seeded.record.id])
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
            owner.assertFinalizationSchema3SourceUnused()
        } finally { allowConsumption.set(true) }
    }

    @Test fun maintenanceFileCloseFailureKeepsRoomClaimWithoutRepeatingConsumedAllowance() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val validating = CompletableDeferred<Unit>()
        val allowanceCloses = AtomicInteger()
        val closeAttempts = CopyOnWriteArrayList<String>()
        val failedPath = AtomicReference<String>()
        owner.gate.beforeClose = { allowanceCloses.incrementAndGet() }
        owner.validation = { validating.complete(Unit); awaitCancellation() }
        owner.denyFinalizationSchema3Acquisition()
        leases.beforeClose = { name, _ ->
            closeAttempts += name
            if (name.startsWith("path-") && failedPath.compareAndSet(null, name))
                throw IOException("Authored failure before real file-lease consumption")
        }
        val scheduler = owner.coordinator
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            validating.await()
            val claimed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seeded.record.id)
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
                assertFailsWith<VeyraException> { scheduler.pause(seeded.record.id) }.failure)
            assertMaintenanceBlocked(scheduler)
            assertEquals(1, allowanceCloses.get())
            assertEquals(claimed.leaseOwner, owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
            assertEquals(seeded.intent, owner.store.acquisitionView().acquisition.physicalFinalizations[seeded.record.id])
            assertNull(tryAcquireDownloadFileLease(assertNotNull(failedPath.get())))
            // The aggregate path lease may have closed independent sibling locks. Its failed
            // member remains REAL-OS-held, and Room release must not run before the aggregate ACK.
            assertEquals(1, closeAttempts.count { it == failedPath.get() })
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, allowanceCloses.get())
            assertEquals(2, closeAttempts.count { it == failedPath.get() })
            assertNull(owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
            owner.assertFinalizationSchema3SourceUnused()
        } finally {
            owner.gate.beforeClose = { }
            leases.beforeClose = { _, _ -> }
        }
    }

    @Test fun generationCloseFailureRetainsTransferSlotAndRoomAttemptAfterNativeStagesConsumed() = executorSchema3Case {
        // EX12: actual generation OS-close failure, after the exact validator and allowance.
        val owner = owner()
        owner.expectedCloseFailure = executorRestartRequired
        val validating = barrier()
        owner.validation = { withContext(NonCancellable) { validating.stop() }; AssetValidation.Valid(733) }
        val failedName = AtomicReference<String>()
        leases.beforeClose = { name, call ->
            if (name.startsWith("path-") && call == 1 && failedName.compareAndSet(null, name))
                throw IOException("Authored failure before real generation lock release")
        }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        validating.entered.await()
        val claimed = join(owner, id)
        coroutineScope {
            val paused = async { runCatching { owner.coordinator.pause(id) } }
            awaitJob(owner, id) { it.claims.single().intent == DownloadBatchIntent.PAUSED }
            validating.release()
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
                assertIs<VeyraException>(paused.await().exceptionOrNull()).failure)
        }
        assertBlocked(owner.coordinator)
        assertEquals(1, owner.validations.size)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(0, events.count { it == "close:slot-0" })
        assertEquals(claimed.job.activeAttemptId, join(owner, id).job.activeAttemptId)
        val path = assertNotNull(claimed.job.stagingPath)
        assertNull(probeFiles.tryLeasePaths(listOf(path)))
        assertNull(probeFiles.tryTransferSlot(1))
        assertTrue(owner.repository.finishCalls.isEmpty())
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, owner.validations.size)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(2, events.count { it == "close:${failedName.get()}" })
        assertEquals(1, events.count { it == "close:slot-0" })
        assertEquals(1, owner.repository.finishCalls.size)
        assertNull(join(owner, id).activeAttempt)
        assertNotNull(probeFiles.tryLeasePaths(listOf(path))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
    }

    @Test fun publicationCloseFailureRetainsGenerationAndSlotWithoutReclosingSuccessfulNativeStages() = executorSchema3Case {
        // EX14: actual COMPLETE commits first; failed publication-lock close cannot invent FAILED.
        val owner = owner()
        owner.expectedCloseFailure = executorRestartRequired
        val failedName = AtomicReference<String>()
        leases.beforeClose = { name, call ->
            if (name.startsWith("path-") && call == 1 && failedName.compareAndSet(null, name))
                throw IOException("Authored failure before real publication lock release")
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { owner.coordinator.lastFailure.value == executorRestartRequired }
        assertBlocked(owner.coordinator)
        val completed = join(owner, id)
        assertEquals(DownloadStatus.COMPLETE, completed.job.status)
        val asset = assertNotNull(completed.asset)
        assertEquals(selected.context, completed.producerContext)
        assertNull(probeFiles.tryLeasePaths(listOf(asset.uri)))
        assertNull(probeFiles.tryLeasePaths(listOf(owner.gateway.transfers.single().path)))
        assertNull(probeFiles.tryTransferSlot(1))
        assertEquals(1, owner.validations.size)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, owner.validations.size)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(asset, join(owner, id).asset)
        assertTrue(join(owner, id).attempts.all { it.status == DownloadAttemptStatus.SUCCEEDED })
        assertNotNull(probeFiles.tryLeasePaths(listOf(asset.uri, owner.gateway.transfers.single().path))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        assertTrue(owner.repository.finishCalls.isEmpty(), "Committed publication is not rewritten by cleanup")
    }

    @Test fun concurrentExplicitClosesSerializeRetryAndRetainFirstFailureAfterSuccessfulConsumption() = executorSchema3Case {
        // EX09: suspension is at the actual allowance-close seam, never a side-effecting reader probe.
        val firstClosing = barrier(); val retryClosing = barrier()
        val allowanceCloses = AtomicInteger()
        val owner = owner(executionGate = closingGate {
            when (allowanceCloses.incrementAndGet()) {
                1 -> { firstClosing.stop(); throw IOException("Authored first allowance close failure") }
                2 -> retryClosing.stop()
                else -> error("A consumed allowance must not be closed again")
            }
        })
        owner.expectedCloseFailure = executorRestartRequired
        val transferred = CompletableDeferred<Unit>()
        owner.gateway.transfer = { call -> call.write(); transferred.complete(Unit); awaitCancellation() }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        transferred.await()
        val path = assertNotNull(join(owner, id).job.stagingPath)
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.close() } }
            try {
                firstClosing.entered.await()
                assertTrue(owner.coordinator.hasPendingCleanupOwners)
                assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
                assertNull(probeFiles.tryLeasePaths(listOf(path)))
                assertNull(probeFiles.tryTransferSlot(1))
                val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.close() } }
                assertFalse(second.isCompleted)
                firstClosing.release()
                assertEquals(executorRestartRequired, assertIs<VeyraException>(first.await().exceptionOrNull()).failure)
                retryClosing.entered.await()
                assertEquals(2, allowanceCloses.get())
                assertTrue(owner.repository.finishCalls.isEmpty())
                assertTrue(owner.coordinator.hasPendingCleanupOwners)
                assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
                val third = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.close() } }
                assertFalse(third.isCompleted)
                retryClosing.release()
                assertEquals(executorRestartRequired, assertIs<VeyraException>(second.await().exceptionOrNull()).failure)
                assertEquals(executorRestartRequired, assertIs<VeyraException>(third.await().exceptionOrNull()).failure)
            } finally { firstClosing.release(); retryClosing.release() }
        }
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(2, allowanceCloses.get())
        assertEquals(1, owner.repository.finishCalls.size)
        assertTrue(owner.validations.isEmpty(), "Cancelled transfer never invents a native reader")
        assertNotNull(probeFiles.tryLeasePaths(listOf(path))).close()
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
    }

    @Test fun emptyCleanupMapDuringPendingAcquisitionNeverAuthorizesDependencyRelease() = executorSchema3Case {
        // EX10: real Room/slot/path admission precedes a still-pending actual allowance acquisition.
        val owner = owner()
        val acquiring = barrier(); val allowanceClosing = barrier()
        owner.gate.beforeAcquire = { withContext(NonCancellable) { acquiring.stop() } }
        owner.gate.beforeClose = { allowanceClosing.stop() }
        val (id, _) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        val claimed = join(owner, id)
        assertNotNull(claimed.activeAttempt)
        coroutineScope {
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.coordinator.close() }
            try {
                assertFalse(owner.coordinator.hasPendingCleanupOwners)
                assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
                assertFalse(closing.isCompleted)
                acquiring.release()
                allowanceClosing.entered.await()
                assertTrue(owner.coordinator.hasPendingCleanupOwners)
                assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
                assertFalse(closing.isCompleted)
                assertEquals(claimed.job.activeAttemptId, join(owner, id).job.activeAttemptId)
                assertNull(probeFiles.tryLeasePaths(listOf(assertNotNull(claimed.job.stagingPath))))
                allowanceClosing.release()
                closing.await()
            } finally { acquiring.release(); allowanceClosing.release() }
        }
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, owner.gate.acquires.get())
        assertEquals(1, owner.gate.closes.get())
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertEquals(1, owner.selector.requests.size)
        owner.legacy.assertUnused()
    }

    @Test fun lateIndependentDrainStillJoinsOtherLiveOwnersAfterOneCleanupFailureIsSticky() = executorSchema3Case {
        // EX13: genuinely independent subject/C owners, one retained reader and one live allowance.
        val firstValidating = barrier(); val secondClosing = barrier()
        val firstReleased = AtomicBoolean()
        releaseOnClose += { firstReleased.set(true) }
        val firstObservations = AtomicInteger()
        val firstDestructions = AtomicInteger()
        val secondTransfer = CompletableDeferred<Unit>()
        val acquisitions = AtomicInteger()
        val firstAllowanceCloses = AtomicInteger(); val secondAllowanceCloses = AtomicInteger()
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                val number = acquisitions.incrementAndGet()
                return DownloadExecutionLease {
                    if (number == 1) firstAllowanceCloses.incrementAndGet()
                    else { secondAllowanceCloses.incrementAndGet(); secondClosing.stop() }
                }
            }
        }
        val owner = owner(executionGate = gate)
        owner.expectedCloseFailure = executorRestartRequired
        parallelism(owner, 2)
        owner.gateway.transfer = { call ->
            if (call.context.subject.id.value == "first") call.complete()
            else { call.write(); secondTransfer.complete(Unit); awaitCancellation() }
        }
        val reader = LocalMediaValidationReaderRelease { firstObservations.incrementAndGet(); firstReleased.get() }
        owner.validation = { input ->
            withContext(NonCancellable) { firstValidating.stop() }
            firstDestructions.incrementAndGet()
            throw LocalMediaValidationReaderUnacknowledged(input, reader)
        }
        val (firstId, _) = enqueue(owner, item("first"), "FIRST000001")
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        firstValidating.entered.await() // Freeze actual acquisition order, not a guessed gate-to-job map.
        val (secondId, _) = enqueue(owner, item("second"), "SECOND00001")
        secondTransfer.await()
        assertNotEquals(join(owner, firstId).job.producer.recordingId, join(owner, secondId).job.producer.recordingId)
        coroutineScope {
            val paused = async { runCatching { owner.coordinator.pause(firstId) } }
            awaitJob(owner, firstId) { it.claims.single().intent == DownloadBatchIntent.PAUSED }
            firstValidating.release()
            assertEquals(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_unconfirmed"),
                assertIs<VeyraException>(paused.await().exceptionOrNull()).failure)
        }
        secondClosing.entered.await()
        assertEquals(executorRestartRequired, owner.coordinator.lastFailure.value)
        coroutineScope {
            val draining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { owner.coordinator.drainForBackground() } }
            try {
                assertNull(withTimeoutOrNull(120) { draining.join(); true }, "Sticky failure cannot skip another real owner")
                assertFalse(draining.isCompleted)
                assertEquals(1, firstObservations.get(), "Independent drain cannot retry the failed reader")
                assertEquals(0, firstAllowanceCloses.get())
                secondClosing.release()
                assertEquals(executorRestartRequired, assertIs<VeyraException>(draining.await().exceptionOrNull()).failure)
            } finally { secondClosing.release() }
        }
        assertTrue(owner.coordinator.hasPendingCleanupOwners)
        assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, secondAllowanceCloses.get())
        firstReleased.set(true)
        assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(1, firstDestructions.get())
        assertEquals(2, firstObservations.get())
        assertEquals(1, firstAllowanceCloses.get())
        assertEquals(1, secondAllowanceCloses.get())
        assertEquals(2, owner.repository.finishCalls.size)
    }

    @Test fun executionClaimFailureBeforeAndAfterCommitRetainsRollbackFilesUntilExplicitClose() {
        for (afterCommit in listOf(false, true)) executorSchema3Case {
            // EX11: real rejected transform vs real committed-but-lost returned claim, never a seed.
            val owner = owner()
            owner.expectedCloseFailure = executorRestartRequired
            val (id, selected) = enqueue(owner)
            val before = join(owner, id)
            val proposed = AtomicReference<PhysicalDownloadJob>()
            val once = AtomicBoolean(true)
            fun failClaim(job: PhysicalDownloadJob?) {
                if (job?.activeAttemptId != null && once.compareAndSet(true, false)) {
                    proposed.set(job)
                    throw IOException("Authored execution claim return failure")
                }
            }
            if (afterCommit) owner.repository.afterAttemptClaim = { _, actual ->
                if (actual is PhysicalDownloadCommit.Committed) failClaim(actual.value.physical.job)
            } else faults(owner.store).beforeCommit = { _, next -> failClaim(next.state().physicalDownloads[id]) }
            val failedName = AtomicReference<String>()
            leases.beforeClose = { name, call ->
                if (name.startsWith("path-") && call == 1 && failedName.compareAndSet(null, name))
                    throw IOException("Authored rollback path release failure")
            }
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            awaitCondition { owner.coordinator.lastFailure.value == executorRestartRequired }
            assertBlocked(owner.coordinator)
            val claim = assertNotNull(proposed.get())
            val retained = join(owner, id)
            assertEquals(selected.context, retained.producerContext)
            if (afterCommit) {
                assertEquals(claim.leaseOwner, retained.job.leaseOwner)
                assertEquals(claim.activeAttemptId, retained.job.activeAttemptId)
                assertNull(retained.activeAttempt?.finishedEpochMs)
            } else {
                assertEquals(before.job, retained.job)
                assertTrue(retained.attempts.isEmpty(), "Rolled-back claim cannot fabricate an attempt")
            }
            val path = assertNotNull(claim.stagingPath)
            assertNull(probeFiles.tryLeasePaths(listOf(path)))
            assertNull(probeFiles.tryTransferSlot(1))
            assertEquals(1, events.count { it == "close:${failedName.get()}" })
            assertEquals(0, owner.gate.acquires.get())
            assertTrue(owner.gateway.prepareCalls.isEmpty())
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
            assertFalse(owner.coordinator.hasPendingCleanupOwners)
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(2, events.count { it == "close:${failedName.get()}" })
            val released = join(owner, id)
            assertNull(released.job.leaseOwner)
            assertNull(released.activeAttempt)
            if (afterCommit) assertNotNull(released.attempts.single().finishedEpochMs)
            else assertTrue(released.attempts.isEmpty())
            assertNotNull(probeFiles.tryLeasePaths(listOf(path))).close()
            assertNotNull(probeFiles.tryTransferSlot(1)).close()
            owner.legacy.assertUnused()
        }
    }

    @Test fun maintenanceClaimFailureBeforeAndAfterCommitKeepsFileAndRoomOwnershipUntilExplicitClose() {
        for (afterCommit in listOf(false, true)) acquisitionDownloadRoomCase {
            val (store, port) = maintenancePortStore()
            val owner = owner(store)
            val seeded = prepareFinalizationSchema3(owner)
            val proposed = AtomicReference<PhysicalDownloadJob>()
            val once = AtomicBoolean(true)
            fun failClaim(state: AcquisitionRepositoryState) {
                val job = state.physicalDownloads[seeded.record.id] ?: return
                if (state.maintenanceLease(seeded.record.id) && once.compareAndSet(true, false)) {
                    proposed.set(job)
                    throw IOException("Authored maintenance claim return failure")
                }
            }
            if (afterCommit) port.afterCommit = { failClaim(it) }
            else port.beforeCommit = { _, next -> failClaim(next.state()) }
            val closeAttempts = CopyOnWriteArrayList<String>()
            val failedPath = AtomicReference<String>()
            leases.beforeClose = { name, _ ->
                closeAttempts += name
                if (name.startsWith("path-") && failedPath.compareAndSet(null, name))
                    throw IOException("Authored maintenance claim rollback path release failure")
            }
            val scheduler = owner.coordinator
            try {
                scheduler.start(DownloadActivation.DURABLE_WORK)
                scheduler.awaitFailure(executorRestartRequired)
                assertMaintenanceBlocked(scheduler)
                val claim = assertNotNull(proposed.get())
                val retained = owner.store.acquisitionView().acquisition
                assertEquals(if (afterCommit) claim.leaseOwner else seeded.record.leaseOwner,
                    retained.physicalDownloads[seeded.record.id]?.leaseOwner)
                assertEquals(seeded.intent, retained.physicalFinalizations[seeded.record.id])
                assertNull(tryAcquireDownloadFileLease(assertNotNull(failedPath.get())))
                assertEquals(1, closeAttempts.count { it == failedPath.get() })
                assertEquals(0, owner.gate.acquires.get())
                assertEquals(0, owner.validations.size)
                assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.close() }.failure)
                assertFalse(scheduler.hasPendingCleanupOwners)
                assertTrue(scheduler.dependencyReleaseSafeAfterClose)
                assertEquals(2, closeAttempts.count { it == failedPath.get() })
                assertNull(owner.store.acquisitionView().acquisition.physicalDownloads[seeded.record.id]?.leaseOwner)
                assertEquals(seeded.intent, owner.store.acquisitionView().acquisition.physicalFinalizations[seeded.record.id])
                assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
                owner.assertFinalizationSchema3SourceUnused()
            } finally {
                port.clearFaults()
                owner.gate.beforeClose = { }
                leases.beforeClose = { _, _ -> }
                store.close()
            }
        }
    }

    @Test fun temporarySlotProbeAndAdmissionCloseFailuresRetainUnhandedSelectedSlotWithoutAutomaticRetry() {
        for (failedName in listOf("slot-3", "slot-admission")) executorSchema3Case {
            // EX17: actual OS temporary/admission locks fail BEFORE selected slot handoff.
            val owner = owner()
            owner.expectedCloseFailure = executorRestartRequired
            val (id, _) = enqueue(owner)
            val before = join(owner, id)
            val once = AtomicBoolean(true)
            leases.beforeClose = { name, _ ->
                if (name == failedName && once.compareAndSet(true, false))
                    throw IOException("Authored temporary slot close failure")
            }
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            awaitCondition { owner.coordinator.lastFailure.value == executorRestartRequired }
            assertBlocked(owner.coordinator)
            assertEquals(before.job, join(owner, id).job)
            assertTrue(join(owner, id).attempts.isEmpty())
            assertNull(tryAcquireDownloadFileLease((files.root / ".locks" / "slot-0").toString()),
                "Unhanded selected slot must remain physically owned")
            assertNull(probeFiles.tryTransferSlot(1))
            assertEquals(1, events.count { it == "close:$failedName" })
            assertEquals(0, events.count { it == "close:slot-0" })
            delay(160)
            assertEquals(1, events.count { it == "close:$failedName" }, "Wake cannot retry the failed temporary close")
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
            assertFalse(owner.coordinator.hasPendingCleanupOwners)
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(2, events.count { it == "close:$failedName" })
            assertEquals(1, events.count { it == "close:slot-0" })
            assertNotNull(probeFiles.tryTransferSlot(1)).close()
            assertEquals(0, owner.gate.acquires.get())
            assertTrue(owner.gateway.prepareCalls.isEmpty())
            assertTrue(owner.gateway.transfers.isEmpty())
            owner.legacy.assertUnused()
        }
    }

    @Test fun partialMaintenancePathAcquisitionFailureRetainsRealFirstLeaseBeforeRoomClaim() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val paths = AtomicInteger()
        val closeAttempts = CopyOnWriteArrayList<String>()
        val failedPath = AtomicReference<String>()
        leases.refuse = { name -> name.startsWith("path-") && paths.incrementAndGet() == 2 }
        leases.beforeClose = { name, _ ->
            closeAttempts += name
            if (name.startsWith("path-") && failedPath.compareAndSet(null, name))
                throw IOException("Authored failure before real first-path consumption")
        }
        owner.denyFinalizationSchema3Acquisition()
        val scheduler = owner.coordinator
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            scheduler.awaitFailure(executorRestartRequired)
            assertMaintenanceBlocked(scheduler)
            val unchanged = owner.store.acquisitionView().acquisition
            assertEquals(seeded.record, unchanged.physicalDownloads[seeded.record.id])
            assertEquals(seeded.intent, unchanged.physicalFinalizations[seeded.record.id])
            // Second-path contention is deliberately injected; the first path's lease is REAL.
            assertNull(tryAcquireDownloadFileLease(assertNotNull(failedPath.get())))
            assertEquals(1, closeAttempts.count { it == failedPath.get() })
            assertEquals(0, owner.validations.size)
            assertEquals(executorRestartRequired, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, closeAttempts.count { it == failedPath.get() })
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
            owner.assertFinalizationSchema3SourceUnused()
        } finally {
            leases.refuse = { false }
            leases.beforeClose = { _, _ -> }
        }
    }

    private suspend fun assertBlocked(scheduler: DownloadCoordinator) {
        assertTrue(scheduler.hasPendingCleanupOwners)
        assertFalse(scheduler.dependencyReleaseSafeAfterClose)
        assertEquals(restartFailure, scheduler.lastFailure.value)
        assertEquals(restartFailure, scheduler.schedulingPlan().blockedReason)
        assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.drainForBackground() }.failure)
        assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.idleDecision() }.failure)
        assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.resumePending() }.failure)
    }

    /** Expected sticky close errors are accepted ONLY after the actual cleanup/scope receipt. */
    private suspend fun DownloadFixture.releaseFaulted(scheduler: DownloadCoordinator, releaseFault: () -> Unit) {
        releaseFault()
        withContext(NonCancellable) {
            val failure = runCatching { scheduler.close() }.exceptionOrNull()
            if (failure != null) assertEquals(restartFailure, assertIs<VeyraException>(failure).failure)
            assertFalse(scheduler.hasPendingCleanupOwners, "Never discard a test owner's live native/file dependencies")
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            coordinators.remove(scheduler) // Its sticky outcome has already been asserted, not hidden by teardown.
        }
    }

    private fun planWithResource(binding: PlaybackBinding, resource: PlaybackResource) = ResolvedPlayable(
        binding.mediaId, "https://fixture.example.test/non-audio", mimeType = "audio/mpeg", codecs = "mp3",
        representationId = authoredRepresentation, executionBinding = binding, resource = resource,
    )

    private fun closingGate(close: suspend () -> Unit) = object : DownloadExecutionGate {
        override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease = DownloadExecutionLease { close() }
    }

    /**
     * Throws BEFORE the real OS lease close, never after consuming it. A later explicit attempt
     * can therefore truthfully close this exact still-held lease. This does not model an unknown
     * kernel close result or permit a real failed native descriptor close to be called successful.
     */
    private class LeaseCloseFaults(
        private val failPath: (String) -> Boolean = { Path.of(it).fileName.toString().startsWith("path-") },
        private val refuse: (String) -> Boolean = { false },
    ) : DownloadFileLeaseFactory {
        val armed = AtomicBoolean()
        private val failedOnce = AtomicBoolean()
        val failedPath = AtomicReference<String>()
        val attempts = CopyOnWriteArrayList<String>()
        val consumed = CopyOnWriteArrayList<String>()
        override fun acquire(path: String): DownloadFileLease? {
            if (refuse(path)) return null
            val native = tryAcquireDownloadFileLease(path) ?: return null
            return object : DownloadFileLease {
                private var closed = false
                override fun close() = synchronized(this) {
                    if (!closed) {
                        attempts += path
                        if (armed.get() && failPath(path) &&
                            failedOnce.compareAndSet(false, true)) {
                            failedPath.set(path)
                            throw IOException("Authored failure before real file-lease consumption")
                        }
                        native.close()
                        closed = true
                        consumed += path
                    }
                }
            }
        }
    }

    private companion object {
        val restartFailure = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "download_owner_drain_failed_restart_required")
    }
}
