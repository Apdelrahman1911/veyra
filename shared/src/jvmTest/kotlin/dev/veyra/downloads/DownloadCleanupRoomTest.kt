package dev.veyra.downloads

import dev.veyra.data.getOrThrow
import dev.veyra.model.*
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
    @Test fun resourceCloseFailureRetainsNativeFileSlotAndRoomOwnershipUntilExplicitClose() = downloadCase {
        val faults = persistence()
        val library = store(faults)
        val transferEntered = CompletableDeferred<Unit>()
        val releaseFault = AtomicBoolean()
        val resourceCloses = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val roomFinishes = AtomicInteger()
        faults.afterTransform = { before, after ->
            if (before.downloads.values.any { it.leaseOwner != null } &&
                after.downloads.values.all { it.leaseOwner == null }) roomFinishes.incrementAndGet()
        }
        val resource = object : PlaybackResource {
            override suspend fun close() {
                if (resourceCloses.incrementAndGet() == 1 && !releaseFault.get())
                    throw IOException("Authored unconsumed native resource")
            }
        }
        val gateway = DownloadTestGateway().apply {
            plan = { planWithResource(it, resource) }
            transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
        }
        val scheduler = coordinator(library, gateway, gate = closingGate { allowanceCloses.incrementAndGet() })
        try {
            scheduler.initializeScheduling()
            val oldTicket = assertIs<DownloadIdleDecision.Deferred>(scheduler.idleDecision()).ticket
            val id = scheduler.enqueue(item())
            scheduler.start(DownloadActivation.DURABLE_WORK)
            transferEntered.await()
            val claimed = library.flush().getOrThrow().downloads.getValue(id)
            scheduler.pause(id) // The live job joins; its failed cleanup owner must NOT disappear.
            assertBlocked(scheduler)
            val retained = library.flush().getOrThrow().downloads.getValue(id)
            assertEquals(claimed.leaseOwner, retained.leaseOwner)
            assertEquals(claimed.activeAttemptId, retained.activeAttemptId)
            assertNull(library.state.value.downloadAttempts[claimed.activeAttemptId]?.finishedEpochMs)
            assertNull(files.tryLeasePaths(listOf(claimed.stagingPath!!)))
            assertNull(files.tryTransferSlot(1))
            assertEquals(0, allowanceCloses.get())
            assertEquals(0, roomFinishes.get())
            var retired = false
            assertEquals(restartFailure, assertFailsWith<VeyraException> {
                scheduler.confirmIdle(oldTicket) { retired = true; true }
            }.failure)
            assertFalse(retired)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.start() }.failure)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.resume(id) }.failure)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.enqueue(item("rejected")) }.failure)
            assertEquals(1, library.flush().getOrThrow().downloads.size)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(1, roomFinishes.get())
            val released = library.flush().getOrThrow().downloads.getValue(id)
            assertNull(released.leaseOwner)
            assertNull(released.activeAttemptId)
            assertEquals(DownloadStatus.PAUSED, released.status)
            assertNotNull(files.tryLeasePaths(listOf(claimed.stagingPath))).close()
            assertNotNull(files.tryTransferSlot(1)).close()
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertEquals(2, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(1, roomFinishes.get(), "A completed cleanup stage must not be replayed")
        } finally { releaseFaulted(scheduler) { releaseFault.set(true) } }
    }

    @Test fun resourceCancellationAndAssertionFailuresNeverAcknowledgeOrAutomaticallyRetry() {
        for (failure in listOf<() -> Throwable>(
            { CancellationException("Authored explicit native close cancellation") },
            { AssertionError("Authored unknown native close error") },
        )) downloadCase {
            val library = store()
            val transferEntered = CompletableDeferred<Unit>()
            val allowConsumption = AtomicBoolean()
            val resourceCloses = AtomicInteger()
            val allowanceCloses = AtomicInteger()
            val resource = object : PlaybackResource {
                override suspend fun close() {
                    resourceCloses.incrementAndGet()
                    if (!allowConsumption.get()) throw failure()
                }
            }
            val gateway = DownloadTestGateway().apply {
                plan = { planWithResource(it, resource) }
                transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
            }
            val scheduler = coordinator(library, gateway, gate = closingGate { allowanceCloses.incrementAndGet() })
            try {
                val id = scheduler.enqueue(item())
                scheduler.start(DownloadActivation.DURABLE_WORK)
                transferEntered.await()
                assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.drainForBackground() }.failure)
                assertBlocked(scheduler)
                delay(160)
                assertEquals(1, resourceCloses.get(), "Scheduler/wake hints must not retry an unknown native close")
                repeat(2) { retry ->
                    assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
                    assertEquals(retry + 2, resourceCloses.get(), "Exactly one remaining-stage attempt per explicit close")
                    assertTrue(scheduler.hasPendingCleanupOwners)
                    assertFalse(scheduler.dependencyReleaseSafeAfterClose)
                    assertEquals(0, allowanceCloses.get())
                    val retained = library.flush().getOrThrow().downloads.getValue(id)
                    assertNotNull(retained.leaseOwner)
                    assertNotNull(retained.activeAttemptId)
                    assertNull(files.tryLeasePaths(listOf(retained.stagingPath!!)))
                    assertNull(files.tryTransferSlot(1))
                }
            } finally {
                // Test-only permission to consume the fake after all always-throw assertions.
                // This is not a production retry loop or evidence that a real unknown close heals.
                releaseFaulted(scheduler) { allowConsumption.set(true) }
            }
        }
    }

    @Test fun allowanceFailureAfterNativeLeaseZeroRetainsFilesWithoutReclosingConsumedResource() = downloadCase {
        val library = store()
        val transferEntered = CompletableDeferred<Unit>()
        val allowConsumption = AtomicBoolean()
        val resourceCloses = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val nativeLeases = AtomicInteger()
        val callback = AtomicReference<suspend () -> Unit>()
        val gateway = DownloadTestGateway().apply {
            plan = { planWithResource(it, object : PlaybackResource {
                override suspend fun close() { resourceCloses.incrementAndGet() }
            }) }
            transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
        }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                callback.set(onExpiration)
                nativeLeases.incrementAndGet()
                return DownloadExecutionLease {
                    if (allowanceCloses.incrementAndGet() == 1) {
                        nativeLeases.decrementAndGet() // Deliberately NOT a shared consumption receipt.
                        if (!allowConsumption.get()) throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_close_failure"))
                    }
                }
            }
        }
        val scheduler = coordinator(library, gateway, gate = gate)
        try {
            val id = scheduler.enqueue(item())
            scheduler.start(DownloadActivation.DURABLE_WORK)
            transferEntered.await()
            val claimed = library.flush().getOrThrow().downloads.getValue(id)
            scheduler.pause(id)
            assertEquals(0, nativeLeases.get())
            assertBlocked(scheduler)
            assertEquals(1, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertNull(files.tryLeasePaths(listOf(claimed.stagingPath!!)))
            assertNull(files.tryTransferSlot(1))
            assertEquals(claimed.activeAttemptId, library.flush().getOrThrow().downloads[id]?.activeAttemptId)
            callback.get().invoke() // A late callback cannot discard the separately retained owner.
            assertBlocked(scheduler)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, resourceCloses.get(), "The successful resource stage must not run again")
            assertEquals(2, allowanceCloses.get())
            assertNull(library.flush().getOrThrow().downloads[id]?.leaseOwner)
            assertNotNull(files.tryLeasePaths(listOf(claimed.stagingPath))).close()
            assertNotNull(files.tryTransferSlot(1)).close()
            val revision = library.state.value.revision
            callback.get().invoke()
            assertEquals(revision, library.flush().getOrThrow().revision)
        } finally { releaseFaulted(scheduler) { allowConsumption.set(true) } }
    }

    @Test fun maintenanceAllowanceFailureRetainsFinalizationFilesAndRoomClaimThroughIndependentDrain() = downloadCase {
        val library = store()
        val seeded = seedFinalization(library)
        val validating = CompletableDeferred<Unit>()
        val allowConsumption = AtomicBoolean()
        val allowanceCloses = AtomicInteger()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val validator = AssetValidator { validating.complete(Unit); awaitCancellation() }
        val scheduler = coordinator(library, gateway, validator, closingGate {
            if (allowanceCloses.incrementAndGet() == 1 && !allowConsumption.get())
                throw IOException("Authored unconsumed maintenance allowance")
        })
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            validating.await()
            val claimed = library.flush().getOrThrow().downloads.getValue(seeded.record.id)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.drainForBackground() }.failure)
            assertBlocked(scheduler)
            val retained = library.flush().getOrThrow()
            assertEquals(claimed.leaseOwner, retained.downloads[seeded.record.id]?.leaseOwner)
            assertEquals(claimed.activeAttemptId, retained.downloads[seeded.record.id]?.activeAttemptId)
            assertEquals(seeded.intent, retained.downloadFinalizations[seeded.record.id])
            assertNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath)))
            assertNull(files.tryLeasePaths(listOf(seeded.intent.finalPath)))
            assertNotNull(files.tryTransferSlot(1)).close() // Maintenance never owned a transfer slot.
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, allowanceCloses.get())
            assertNull(library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
            assertEquals(seeded.intent, library.state.value.downloadFinalizations[seeded.record.id])
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent.stagingPath, seeded.intent.finalPath))).close()
            gateway.assertUnused()
        } finally { releaseFaulted(scheduler) { allowConsumption.set(true) } }
    }

    @Test fun maintenanceFileCloseFailureKeepsRoomClaimWithoutRepeatingConsumedAllowance() = downloadCase {
        val library = store()
        val seeded = seedFinalization(library)
        val validating = CompletableDeferred<Unit>()
        val allowanceCloses = AtomicInteger()
        val leaseFaults = LeaseCloseFaults()
        val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val scheduler = coordinator(library, gateway,
            AssetValidator { validating.complete(Unit); awaitCancellation() },
            closingGate { allowanceCloses.incrementAndGet() }, files = faultingFiles)
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            validating.await()
            val claimed = library.flush().getOrThrow().downloads.getValue(seeded.record.id)
            leaseFaults.armed.set(true)
            scheduler.pause(seeded.record.id)
            assertBlocked(scheduler)
            assertEquals(1, allowanceCloses.get())
            assertEquals(claimed.leaseOwner, library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
            assertEquals(seeded.intent, library.state.value.downloadFinalizations[seeded.record.id])
            assertNull(tryAcquireDownloadFileLease(leaseFaults.failedPath.get()))
            // The aggregate path lease may have closed independent sibling locks. Its failed
            // member remains REAL-OS-held, and Room release must not run before the aggregate ACK.
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, allowanceCloses.get())
            assertNull(library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath))).close()
            gateway.assertUnused()
        } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
    }

    @Test fun generationCloseFailureRetainsTransferSlotAndRoomAttemptAfterNativeStagesConsumed() = downloadCase {
        val library = store()
        val transferEntered = CompletableDeferred<Unit>()
        val resourceCloses = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val leaseFaults = LeaseCloseFaults()
        val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
        val gateway = DownloadTestGateway().apply {
            plan = { planWithResource(it, object : PlaybackResource {
                override suspend fun close() { resourceCloses.incrementAndGet() }
            }) }
            transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
        }
        val scheduler = coordinator(library, gateway, gate = closingGate { allowanceCloses.incrementAndGet() }, files = faultingFiles)
        try {
            val id = scheduler.enqueue(item())
            scheduler.start(DownloadActivation.DURABLE_WORK)
            transferEntered.await()
            val claimed = library.flush().getOrThrow().downloads.getValue(id)
            leaseFaults.armed.set(true)
            scheduler.pause(id)
            assertBlocked(scheduler)
            assertEquals(1, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(0, leaseFaults.consumed.count { Path.of(it).fileName.toString() == "slot-0" })
            assertEquals(claimed.activeAttemptId, library.flush().getOrThrow().downloads[id]?.activeAttemptId)
            assertNull(files.tryLeasePaths(listOf(claimed.stagingPath!!)))
            assertNull(files.tryTransferSlot(1))
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(1, leaseFaults.consumed.count { Path.of(it).fileName.toString() == "slot-0" })
            assertNull(library.flush().getOrThrow().downloads[id]?.activeAttemptId)
            assertNotNull(files.tryLeasePaths(listOf(claimed.stagingPath))).close()
            assertNotNull(files.tryTransferSlot(1)).close()
        } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
    }

    @Test fun publicationCloseFailureRetainsGenerationAndSlotWithoutReclosingSuccessfulNativeStages() = downloadCase {
        val library = store()
        val resourceCloses = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val leaseFaults = LeaseCloseFaults().apply { armed.set(true) }
        val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
        val gateway = DownloadTestGateway().apply {
            plan = { planWithResource(it, object : PlaybackResource {
                override suspend fun close() { resourceCloses.incrementAndGet() }
            }) }
        }
        val scheduler = coordinator(library, gateway, gate = closingGate { allowanceCloses.incrementAndGet() }, files = faultingFiles)
        try {
            val id = scheduler.enqueue(item())
            scheduler.start(DownloadActivation.DURABLE_WORK)
            withTimeout(5_000) { scheduler.lastFailure.first { it == restartFailure } }
            assertBlocked(scheduler)
            val completed = library.flush().getOrThrow().downloads.getValue(id)
            assertEquals(DownloadStatus.COMPLETE, completed.status, "Already committed success is not overwritten with a fabricated failed attempt")
            val asset = assertNotNull(completed.asset)
            assertNull(files.tryLeasePaths(listOf(asset.uri)))
            assertNull(files.tryLeasePaths(listOf(gateway.transfers.single().path)))
            assertNull(files.tryTransferSlot(1))
            assertEquals(1, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
            assertEquals(asset, library.flush().getOrThrow().downloads[id]?.asset)
            assertTrue(library.state.value.downloadAttempts.values.all { it.status == DownloadAttemptStatus.SUCCEEDED })
            assertNotNull(files.tryLeasePaths(listOf(asset.uri, gateway.transfers.single().path))).close()
            assertNotNull(files.tryTransferSlot(1)).close()
        } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
    }

    @Test fun concurrentExplicitClosesSerializeRetryAndRetainFirstFailureAfterSuccessfulConsumption() = downloadCase {
        val library = store()
        val transferEntered = CompletableDeferred<Unit>()
        val firstClosing = barrier(); val retryClosing = barrier()
        val allowConsumption = AtomicBoolean()
        val resourceCloses = AtomicInteger()
        val allowanceCloses = AtomicInteger()
        val resource = object : PlaybackResource {
            override suspend fun close() {
                when (resourceCloses.incrementAndGet()) {
                    1 -> { firstClosing.stop(); if (!allowConsumption.get()) throw IOException("Authored first native close failure") }
                    2 -> retryClosing.stop()
                }
            }
        }
        val gateway = DownloadTestGateway().apply {
            plan = { planWithResource(it, resource) }
            transfer = { call -> call.write(); transferEntered.complete(Unit); awaitCancellation() }
        }
        val scheduler = coordinator(library, gateway, gate = closingGate { allowanceCloses.incrementAndGet() })
        try {
            scheduler.enqueue(item()); scheduler.start(DownloadActivation.DURABLE_WORK)
            transferEntered.await()
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.close() } }
                try {
                    firstClosing.entered.await()
                    assertTrue(scheduler.hasPendingCleanupOwners)
                    assertFalse(scheduler.dependencyReleaseSafeAfterClose)
                    val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.close() } }
                    assertFalse(second.isCompleted)
                    firstClosing.release()
                    assertEquals(restartFailure, assertIs<VeyraException>(first.await().exceptionOrNull()).failure)
                    retryClosing.entered.await()
                    assertEquals(2, resourceCloses.get())
                    assertEquals(0, allowanceCloses.get())
                    assertTrue(scheduler.hasPendingCleanupOwners)
                    assertFalse(scheduler.dependencyReleaseSafeAfterClose)
                    val third = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.close() } }
                    assertFalse(third.isCompleted)
                    retryClosing.release()
                    assertEquals(restartFailure, assertIs<VeyraException>(second.await().exceptionOrNull()).failure)
                    assertEquals(restartFailure, assertIs<VeyraException>(third.await().exceptionOrNull()).failure)
                } finally { firstClosing.release(); retryClosing.release() }
            }
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, resourceCloses.get())
            assertEquals(1, allowanceCloses.get())
        } finally { releaseFaulted(scheduler) { allowConsumption.set(true); firstClosing.release(); retryClosing.release() } }
    }

    @Test fun emptyCleanupMapDuringPendingAcquisitionNeverAuthorizesDependencyRelease() = downloadCase {
        val library = store()
        val acquiring = barrier(); val allowanceClosing = barrier()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val gate = object : DownloadExecutionGate {
            override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
                withContext(NonCancellable) { acquiring.stop() }
                return DownloadExecutionLease { allowanceClosing.stop() }
            }
        }
        val scheduler = coordinator(library, gateway, gate = gate)
        scheduler.enqueue(item()); scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        coroutineScope {
            val closing = async(start = CoroutineStart.UNDISPATCHED) { scheduler.close() }
            try {
                assertFalse(scheduler.hasPendingCleanupOwners, "Gate acquisition has not returned a cleanup owner yet")
                assertFalse(scheduler.dependencyReleaseSafeAfterClose)
                assertFalse(closing.isCompleted)
                acquiring.release()
                allowanceClosing.entered.await()
                assertTrue(scheduler.hasPendingCleanupOwners)
                assertFalse(scheduler.dependencyReleaseSafeAfterClose)
                assertFalse(closing.isCompleted)
                allowanceClosing.release()
                closing.await()
            } finally { acquiring.release(); allowanceClosing.release() }
        }
        assertFalse(scheduler.hasPendingCleanupOwners)
        assertTrue(scheduler.dependencyReleaseSafeAfterClose)
        gateway.assertUnused()
    }

    @Test fun lateIndependentDrainStillJoinsOtherLiveOwnersAfterOneCleanupFailureIsSticky() = downloadCase {
        val library = store()
        library.update { it.copy(settings = it.settings.copy(downloadParallelism = 2)) }.getOrThrow()
        val firstTransfer = CompletableDeferred<Unit>(); val secondTransfer = CompletableDeferred<Unit>()
        val secondClosing = barrier()
        val allowFirstConsumption = AtomicBoolean()
        val firstCloses = AtomicInteger()
        val secondCloses = AtomicInteger()
        val gateway = DownloadTestGateway().apply {
            plan = { binding -> planWithResource(binding, object : PlaybackResource {
                override suspend fun close() {
                    if (binding.mediaId == "first") {
                        firstCloses.incrementAndGet()
                        if (!allowFirstConsumption.get()) throw IOException("Authored retained first resource")
                    } else { secondCloses.incrementAndGet(); secondClosing.stop() }
                }
            }) }
            transfer = { call ->
                call.write()
                if (call.binding.mediaId == "first") firstTransfer.complete(Unit) else secondTransfer.complete(Unit)
                awaitCancellation()
            }
        }
        val scheduler = coordinator(library, gateway)
        try {
            val firstId = scheduler.enqueue(item("first")); scheduler.enqueue(item("second"))
            scheduler.start(DownloadActivation.DURABLE_WORK)
            firstTransfer.await(); secondTransfer.await()
            scheduler.pause(firstId)
            secondClosing.entered.await() // First failure also cancelled this separate live owner.
            assertEquals(restartFailure, scheduler.lastFailure.value)
            coroutineScope {
                val draining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { scheduler.drainForBackground() } }
                try {
                    assertNull(withTimeoutOrNull(100) { draining.join(); true }, "Sticky failure cannot skip another registered native unwind")
                    assertFalse(draining.isCompleted)
                    assertEquals(1, firstCloses.get(), "Independent drain must not retry the dead failed owner")
                    secondClosing.release()
                    assertEquals(restartFailure, assertIs<VeyraException>(draining.await().exceptionOrNull()).failure)
                } finally { secondClosing.release() }
            }
            assertTrue(scheduler.hasPendingCleanupOwners)
            assertFalse(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(1, secondCloses.get())
            allowFirstConsumption.set(true)
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, firstCloses.get())
            assertEquals(1, secondCloses.get())
        } finally { releaseFaulted(scheduler) { allowFirstConsumption.set(true); secondClosing.release() } }
    }

    @Test fun executionClaimFailureBeforeAndAfterCommitRetainsRollbackFilesUntilExplicitClose() {
        for (afterCommit in listOf(false, true)) downloadCase {
            val faults = persistence()
            val library = store(faults)
            val seeded = seedPending(library)
            val proposed = AtomicReference<DownloadRecord>()
            val once = AtomicBoolean(true)
            fun failClaim(snapshot: AppSnapshot) {
                val record = snapshot.downloads[seeded.record.id] ?: return
                if (record.activeAttemptId != null && once.compareAndSet(true, false)) {
                    proposed.set(record)
                    throw IOException("Authored execution claim return failure")
                }
            }
            if (afterCommit) faults.afterCommit = { failClaim(it) }
            else faults.afterTransform = { _, next -> failClaim(next) }
            val leaseFaults = LeaseCloseFaults().apply { armed.set(true) }
            val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val allowances = AtomicInteger()
            val scheduler = coordinator(library, gateway, files = faultingFiles,
                gate = closingGate { allowances.incrementAndGet() })
            try {
                scheduler.start(DownloadActivation.DURABLE_WORK)
                withTimeout(5_000) { scheduler.lastFailure.first { it == restartFailure } }
                assertBlocked(scheduler)
                val claim = assertNotNull(proposed.get())
                val retained = library.flush().getOrThrow()
                if (afterCommit) {
                    assertEquals(claim.leaseOwner, retained.downloads[seeded.record.id]?.leaseOwner)
                    assertEquals(claim.activeAttemptId, retained.downloads[seeded.record.id]?.activeAttemptId)
                    assertNull(retained.downloadAttempts[claim.activeAttemptId]?.finishedEpochMs)
                } else {
                    assertEquals(seeded.record, retained.downloads[seeded.record.id])
                    assertTrue(retained.downloadAttempts.isEmpty(), "Rollback cannot invent a committed attempt")
                }
                assertNull(files.tryLeasePaths(listOf(claim.stagingPath!!)))
                assertNull(files.tryTransferSlot(1))
                assertEquals(1, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
                assertEquals(0, allowances.get())
                assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
                assertFalse(scheduler.hasPendingCleanupOwners)
                assertTrue(scheduler.dependencyReleaseSafeAfterClose)
                assertEquals(2, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
                val released = library.flush().getOrThrow()
                assertNull(released.downloads[seeded.record.id]?.leaseOwner)
                assertNull(released.downloads[seeded.record.id]?.activeAttemptId)
                if (afterCommit) assertNotNull(released.downloadAttempts[claim.activeAttemptId]?.finishedEpochMs)
                else assertTrue(released.downloadAttempts.isEmpty())
                assertNotNull(files.tryLeasePaths(listOf(claim.stagingPath))).close()
                assertNotNull(files.tryTransferSlot(1)).close()
                gateway.assertUnused()
            } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
        }
    }

    @Test fun maintenanceClaimFailureBeforeAndAfterCommitKeepsFileAndRoomOwnershipUntilExplicitClose() {
        for (afterCommit in listOf(false, true)) downloadCase {
            val faults = persistence()
            val library = store(faults)
            val seeded = seedFinalization(library)
            val proposed = AtomicReference<DownloadRecord>()
            val once = AtomicBoolean(true)
            fun failClaim(snapshot: AppSnapshot) {
                val record = snapshot.downloads[seeded.record.id] ?: return
                if (record.leaseOwner?.startsWith("download-maintenance") == true && once.compareAndSet(true, false)) {
                    proposed.set(record)
                    throw IOException("Authored maintenance claim return failure")
                }
            }
            if (afterCommit) faults.afterCommit = { failClaim(it) }
            else faults.afterTransform = { _, next -> failClaim(next) }
            val leaseFaults = LeaseCloseFaults().apply { armed.set(true) }
            val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val allowances = AtomicInteger()
            val scheduler = coordinator(library, gateway, files = faultingFiles,
                gate = closingGate { allowances.incrementAndGet() })
            try {
                scheduler.start(DownloadActivation.DURABLE_WORK)
                withTimeout(5_000) { scheduler.lastFailure.first { it == restartFailure } }
                assertBlocked(scheduler)
                val claim = assertNotNull(proposed.get())
                val retained = library.flush().getOrThrow()
                assertEquals(if (afterCommit) claim.leaseOwner else seeded.record.leaseOwner,
                    retained.downloads[seeded.record.id]?.leaseOwner)
                assertEquals(seeded.intent, retained.downloadFinalizations[seeded.record.id])
                assertNull(tryAcquireDownloadFileLease(leaseFaults.failedPath.get()))
                assertEquals(1, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
                assertEquals(0, allowances.get())
                assertEquals(0, validationCount.get())
                assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
                assertFalse(scheduler.hasPendingCleanupOwners)
                assertTrue(scheduler.dependencyReleaseSafeAfterClose)
                assertEquals(2, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
                assertNull(library.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner)
                assertEquals(seeded.intent, library.state.value.downloadFinalizations[seeded.record.id])
                assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath))).close()
                gateway.assertUnused()
            } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
        }
    }

    @Test fun temporarySlotProbeAndAdmissionCloseFailuresRetainUnhandedSelectedSlotWithoutAutomaticRetry() {
        for (failedName in listOf("slot-3", "slot-admission")) downloadCase {
            val library = store()
            val seeded = seedPending(library)
            val leaseFaults = LeaseCloseFaults(failPath = { Path.of(it).fileName.toString() == failedName })
                .apply { armed.set(true) }
            val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val scheduler = coordinator(library, gateway, files = faultingFiles)
            try {
                scheduler.start(DownloadActivation.DURABLE_WORK)
                withTimeout(5_000) { scheduler.lastFailure.first { it == restartFailure } }
                assertBlocked(scheduler)
                assertEquals(seeded.record, library.flush().getOrThrow().downloads[seeded.record.id])
                assertTrue(library.state.value.downloadAttempts.isEmpty())
                assertNull(tryAcquireDownloadFileLease((files.root / ".locks" / "slot-0").toString()),
                    "A temporary release failure must not discard the selected but unhanded slot")
                assertNull(files.tryTransferSlot(1))
                assertEquals(1, leaseFaults.attempts.count { Path.of(it).fileName.toString() == failedName })
                assertEquals(0, leaseFaults.consumed.count { Path.of(it).fileName.toString() == "slot-0" })
                assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
                assertFalse(scheduler.hasPendingCleanupOwners)
                assertTrue(scheduler.dependencyReleaseSafeAfterClose)
                assertEquals(2, leaseFaults.attempts.count { Path.of(it).fileName.toString() == failedName })
                assertEquals(1, leaseFaults.consumed.count { Path.of(it).fileName.toString() == "slot-0" })
                assertNotNull(files.tryTransferSlot(1)).close()
                gateway.assertUnused()
            } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
        }
    }

    @Test fun partialMaintenancePathAcquisitionFailureRetainsRealFirstLeaseBeforeRoomClaim() = downloadCase {
        val library = store()
        val seeded = seedFinalization(library)
        val paths = AtomicInteger()
        val leaseFaults = LeaseCloseFaults(refuse = {
            Path.of(it).fileName.toString().startsWith("path-") && paths.incrementAndGet() == 2
        }).apply { armed.set(true) }
        val faultingFiles = DownloadFiles(files.root.toString(), files.fileSystem, leaseFaults)
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val scheduler = coordinator(library, gateway, files = faultingFiles)
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            withTimeout(5_000) { scheduler.lastFailure.first { it == restartFailure } }
            assertBlocked(scheduler)
            assertEquals(seeded.record, library.flush().getOrThrow().downloads[seeded.record.id])
            assertEquals(seeded.intent, library.state.value.downloadFinalizations[seeded.record.id])
            // Second-path contention is deliberately injected; the first path's lease is REAL.
            assertNull(tryAcquireDownloadFileLease(leaseFaults.failedPath.get()))
            assertEquals(1, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
            assertEquals(0, validationCount.get())
            assertEquals(restartFailure, assertFailsWith<VeyraException> { scheduler.close() }.failure)
            assertFalse(scheduler.hasPendingCleanupOwners)
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            assertEquals(2, leaseFaults.attempts.count { it == leaseFaults.failedPath.get() })
            assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath))).close()
            gateway.assertUnused()
        } finally { releaseFaulted(scheduler) { leaseFaults.armed.set(false) } }
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
