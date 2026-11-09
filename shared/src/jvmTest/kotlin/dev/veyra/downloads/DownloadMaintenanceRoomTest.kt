package dev.veyra.downloads

import dev.veyra.data.LibraryPersistence
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Room/files, controlled OS-gate/native seams. These are NOT device/background/audio proofs. */
class DownloadMaintenanceRoomTest {
    @Test fun startupReturnsWithHonestCheckingProjectionAndDoesNotBlockAnotherAcquisition() = downloadCase {
        val first = store()
        val old = seedComplete(first)
        val checking = barrier()
        val gate = MaintenanceTestGate()
        val native = AssetValidator { asset ->
            if (asset.mediaId == old.item.id) checking.stop()
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway()
        val a = coordinator(first, gateway, native, gate)
        assertEquals(DownloadStatus.VERIFYING, a.presentation.value.downloads[old.record.id]?.status)
        withTimeout(3_000) { a.start() }
        checking.entered.await()
        assertEquals(DownloadStatus.COMPLETE, first.flush().getOrThrow().downloads[old.record.id]?.status)
        assertEquals(DownloadStatus.VERIFYING, a.presentation.value.downloads[old.record.id]?.status)
        withTimeout(3_000) { a.hasPendingWork.first { it } }
        assertTrue(gate.callbacks.isNotEmpty(), "Native validation must have an execution allowance")
        val fresh = item("new-acquisition")
        val id = a.enqueue(fresh)
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(listOf(fresh.title), gateway.queries.toList())
        assertFalse(checking.released.isCompleted, "A large existing library must not block newly requested acquisition")
        checking.release()
        withTimeout(3_000) { a.presentation.first { it.downloads[old.record.id]?.status == DownloadStatus.COMPLETE } }
    }

    @Test fun deniedRecoveryAllowanceKeepsJournalAndRetriesOnlyAfterForegroundResume() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first)
        first.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        val denied = AtomicBoolean(true)
        val gate = MaintenanceTestGate().apply {
            acquiring = { if (denied.get()) throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED)) }
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        val blocked = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.NEEDS_FOREGROUND && d.leaseOwner == null
        } == true }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.downloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, blocked.downloadFinalizations[seeded.record.id])
        assertEquals(0, validationCount.get())
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent!!.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        delay(160)
        assertEquals(1, gate.callbacks.size, "A denied allowance must not be polled in a retry loop")
        withTimeout(3_000) { a.hasPendingWork.first { !it } }
        denied.set(false)
        a.resumePending()
        val ready = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertNull(ready.downloads[seeded.record.id]?.failure)
        assertFalse(seeded.record.id in ready.downloadFinalizations)
        gateway.assertUnused()
    }

    @Test fun deniedCompletedCheckKeepsDurableCompleteButPresentsNeedsForegroundUntilAnAllowedRecheck() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        first.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        val denied = AtomicBoolean(true)
        val checkingCallbacks = CopyOnWriteArrayList<suspend () -> Unit>()
        val checkingCloses = AtomicInteger()
        val gate = MaintenanceTestGate().apply {
            acquiring = { expiration ->
                // A moved-from staging directory is also swept under its own allowance. Count
                // this exact completed-file claim, not unrelated (but correctly gated) cleanup.
                if (first.flush().getOrThrow().downloads[seeded.record.id]?.leaseOwner != null) checkingCallbacks += expiration
                if (denied.get()) throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED))
            }
            closing = { expiration -> if (checkingCallbacks.any { it === expiration }) checkingCloses.incrementAndGet() }
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        assertEquals(DownloadStatus.VERIFYING, a.presentation.value.downloads[seeded.record.id]?.status)
        a.start()
        val blocked = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.COMPLETE && d.failure?.code == FailureCode.BACKGROUND_RESTRICTED && d.leaseOwner == null
        } == true }
        val presented = withTimeout(3_000) { a.presentation.first {
            it.downloads[seeded.record.id]?.status == DownloadStatus.NEEDS_FOREGROUND
        } }
        assertEquals(seeded.record.asset, blocked.downloads[seeded.record.id]?.asset)
        assertEquals(seeded.record.asset, presented.downloads[seeded.record.id]?.asset)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, presented.downloads[seeded.record.id]?.failure?.code)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        assertEquals(0, validationCount.get())
        delay(160)
        assertEquals(1, gate.callbacks.size, "Denied verification must not acquire another OS allowance until foreground resume")
        assertEquals(0, gate.closes.get(), "A denied acquire did not return a lease to close")
        assertEquals(1, checkingCallbacks.size)
        withTimeout(3_000) { a.hasPendingWork.first { !it } }
        denied.set(false)
        a.resumePending()
        withTimeout(3_000) { a.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
        val verified = eventually(first) { it.downloads[seeded.record.id]?.leaseOwner == null && checkingCloses.get() == 1 }
        assertNull(verified.downloads[seeded.record.id]?.failure)
        assertEquals(seeded.record.asset, verified.downloads[seeded.record.id]?.asset)
        assertEquals(1, validationCount.get())
        assertEquals(2, checkingCallbacks.size)
        gateway.assertUnused()
    }

    @Test fun inlineMaintenanceAcquireExpirationCannotMissItsRegisteredChildOrSelfJoin() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first, published = true)
        val gate = MaintenanceTestGate().apply { acquiring = { expiration -> expiration() } }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        val blocked = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.NEEDS_FOREGROUND && d.leaseOwner == null
        } == true && gate.closes.get() == 1 }
        assertEquals(1, gate.callbacks.size)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.downloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, blocked.downloadFinalizations[seeded.record.id])
        assertEquals(0, validationCount.get())
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent!!.finalPath)))
        gateway.assertUnused()
    }

    @Test fun expiredCheckpointRollbackStillCancelsMaintenanceAndFinishRetainsTypedJournal() = downloadCase {
        val faults = persistence()
        val first = store(faults)
        val seeded = seedFinalization(first)
        val failOnce = AtomicBoolean(true)
        faults.afterTransform = { before, next ->
            if (next.downloads[seeded.record.id]?.status == DownloadStatus.NEEDS_FOREGROUND &&
                before.downloads[seeded.record.id]?.status != DownloadStatus.NEEDS_FOREGROUND && failOnce.compareAndSet(true, false))
                throw VeyraException(AppFailure(FailureCode.STORAGE_IO, "authored_maintenance_checkpoint_rollback"))
        }
        val gate = MaintenanceTestGate().apply { acquiring = { expiration -> expiration() } }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        val blocked = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.NEEDS_FOREGROUND && d.leaseOwner == null
        } == true && gate.closes.get() == 1 }
        assertFalse(failOnce.get())
        assertEquals(FailureCode.STORAGE_IO, a.lastFailure.value?.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.downloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, blocked.downloadFinalizations[seeded.record.id])
        assertEquals(0, validationCount.get())
        gateway.assertUnused()
    }

    @Test fun cleanupCannotReleaseNativeReadersOrDeleteBeforeItsAllowanceIsGranted() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        first.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(
            status = DownloadStatus.CANCELLED, cleanupPending = true, removeWhenClean = true, userPaused = true))) }.getOrThrow()
        val permission = barrier()
        val nativeReleases = AtomicInteger()
        val gate = MaintenanceTestGate().apply { acquiring = { permission.stop() } }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate, beforeDelete = { nativeReleases.incrementAndGet() })
        a.start(); permission.entered.await()
        assertEquals(0, nativeReleases.get())
        assertTrue(first.flush().getOrThrow().downloads.getValue(seeded.record.id).cleanupPending)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        permission.release()
        eventually(first) { seeded.record.id !in it.downloads }
        assertEquals(1, nativeReleases.get())
        assertFalse(Files.exists(Path.of(seeded.record.asset.uri)))
        gateway.assertUnused()
    }

    @Test fun cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        first.update { it.copy(downloads = it.downloads + (seeded.record.id to seeded.record.copy(
            status = DownloadStatus.CANCELLED, cleanupPending = true, removeWhenClean = true, userPaused = true))) }.getOrThrow()
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val releases = AtomicInteger()
        val gate = MaintenanceTestGate()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate, beforeDelete = {
            if (releases.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { unwinding.stop() } }
            }
        })
        a.start(); entered.await()
        val expire: suspend () -> Unit = gate.callbacks[0]
        withTimeout(3_000) { expire() }
        unwinding.entered.await()
        val blocked = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
        assertTrue(blocked.cleanupPending && blocked.removeWhenClean)
        assertEquals(seeded.record.asset, blocked.asset)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.failure?.code)
        assertEquals(0, gate.closes.get(), "The OS lease must cover actual noncancellable release unwind")
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        a.resumePending()
        expire() // queued OLD foreground epoch must not suspend the resumed host
        unwinding.release()
        eventually(first) { seeded.record.id !in it.downloads }
        eventually(first) { gate.closes.get() == 2 }
        assertEquals(2, releases.get())
        assertFalse(Files.exists(Path.of(seeded.record.asset.uri)))
        gateway.assertUnused()
    }

    @Test fun cancelOnlyExpirationAllowsExplicitForegroundResumeWithoutKillingRecoveryScheduler() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first)
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val nativeCalls = AtomicInteger()
        val native = AssetValidator {
            if (nativeCalls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { unwinding.stop() } }
            }
            AssetValidation.Valid(31_234)
        }
        val gate = MaintenanceTestGate()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native, gate)
        a.start(); entered.await()
        coroutineScope {
            val oldExpiration: suspend () -> Unit = gate.callbacks[0]
            val expiration = async { oldExpiration() }
            try {
                unwinding.entered.await()
                expiration.await() // Callback completion is NOT the independent native/Room owner drain.
                val checkpoint = first.flush().getOrThrow()
                assertEquals(DownloadStatus.NEEDS_FOREGROUND, checkpoint.downloads[seeded.record.id]?.status)
                assertEquals(seeded.intent, checkpoint.downloadFinalizations[seeded.record.id])
                assertEquals(0, gate.closes.get())
                a.resumePending()
                oldExpiration()
            } finally { unwinding.release() }
        }
        eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(2, nativeCalls.get())
        gateway.assertUnused()
    }

    @Test fun completedMaintenanceCloseAndQueuedCallbackCannotSuspendAFreshTransfer() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first)
        val gate = MaintenanceTestGate().apply { closing = { expiration -> withContext(NonCancellable) { expiration() } } }
        val holding = barrier()
        val gateway = DownloadTestGateway().apply { transfer = { call -> holding.stop(); call.complete() } }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE && gate.closes.get() >= 1 }
        val oldExpiration: suspend () -> Unit = gate.callbacks[0]
        val id = a.enqueue(item("after-recovery"))
        holding.entered.await()
        val before = first.flush().getOrThrow().downloads.getValue(id)
        oldExpiration()
        delay(160)
        val after = first.flush().getOrThrow().downloads.getValue(id)
        assertEquals(DownloadStatus.RUNNING, after.status)
        assertEquals(before.activeAttemptId, after.activeAttemptId)
        assertEquals(before.leaseOwner, after.leaseOwner)
        holding.release()
        eventually(first) { it.downloads[id]?.status == DownloadStatus.COMPLETE }
    }

    @Test fun idempotentForegroundAnnouncementKeepsAnActiveMaintenanceEpoch() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first)
        val validation = barrier()
        val native = AssetValidator { validation.stop(); AssetValidation.Valid(31_234) }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native, MaintenanceTestGate())
        a.start(); validation.entered.await()
        val before = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
        a.resumePending()
        delay(160)
        val after = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
        assertEquals(DownloadStatus.VERIFYING, after.status)
        assertEquals(before.leaseOwner, after.leaseOwner)
        assertEquals(before.activeAttemptId, after.activeAttemptId)
        validation.release()
        eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        gateway.assertUnused()
    }

    @Test fun cancelledDispatcherReturnAfterRealExecutionClaimCommitReleasesItsExactLease() = downloadCase {
        val faults = persistence()
        val first = store(faults)
        val once = AtomicBoolean(true)
        val committed = barrier()
        faults.afterCommit = { snapshot ->
            if (snapshot.downloads.values.any { it.status == DownloadStatus.RESOLVING && it.leaseOwner != null } && once.compareAndSet(true, false)) {
                committed.stop()
                parent.cancel(CancellationException("authored parent shutdown after actual claim commit"))
            }
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway)
        val id = a.enqueue(item()); a.start()
        committed.entered.await(); committed.release()
        val released = eventually(first) { !once.get() && it.downloads[id]?.let { d ->
            d.leaseOwner == null && d.activeAttemptId == null
        } == true && it.downloadAttempts.values.singleOrNull()?.finishedEpochMs != null }
        assertEquals(DownloadStatus.QUEUED, released.downloads[id]?.status)
        assertEquals(1, released.downloadAttempts.size)
        val slot = assertNotNull(files.tryTransferSlot(1)); slot.close()
        val path = released.downloads.getValue(id).stagingPath!!
        val lease = assertNotNull(files.tryLeasePaths(listOf(path))); lease.close()
        gateway.assertUnused()
    }

    @Test fun cancelledDispatcherReturnAfterRealMaintenanceCommitReleasesBeforeGateAcquisition() = downloadCase {
        val faults = persistence()
        val first = store(faults)
        val seeded = seedFinalization(first)
        val once = AtomicBoolean(true)
        val committed = barrier()
        faults.afterCommit = { snapshot ->
            if (snapshot.downloads[seeded.record.id]?.leaseOwner?.startsWith("download-maintenance") == true && once.compareAndSet(true, false)) {
                committed.stop()
                parent.cancel(CancellationException("authored shutdown after actual maintenance commit"))
            }
        }
        val gate = MaintenanceTestGate()
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, gate = gate)
        a.start()
        committed.entered.await(); committed.release()
        val released = eventually(first) { !once.get() && it.downloads[seeded.record.id]?.leaseOwner == null }
        assertEquals(seeded.intent, released.downloadFinalizations[seeded.record.id])
        assertTrue(gate.callbacks.isEmpty())
        assertEquals(0, validationCount.get())
        val lease = assertNotNull(files.tryLeasePaths(listOf(seeded.intent!!.stagingPath, seeded.intent.finalPath))); lease.close()
        gateway.assertUnused()
    }

    @Test fun closingARealFailedUnstartedRoomHostDoesNotImplicitlyReopenOrWriteIt() = runBlocking {
        val fixture = DownloadFixture()
        try {
            val damaged = "Authored non-SQLite startup corruption".encodeToByteArray()
            Files.write(fixture.databasePath, damaged)
            val room = fixture.persistence()
            val opens = AtomicInteger()
            val persistence = object : LibraryPersistence by room {
                override suspend fun open() = try { room.open() } finally { opens.incrementAndGet() }
            }
            val first = fixture.store(persistence)
            val gateway = DownloadTestGateway().apply { denyAcquisition() }
            val a = fixture.coordinator(first, gateway)
            assertFailsWith<VeyraException> { a.start() }
            val attempted = opens.get()
            assertTrue(attempted > 0)
            a.close(); a.close()
            assertEquals(attempted, opens.get(), "Close must not retry corrupt startup by checkpointing an unstarted store")
            assertContentEquals(damaged, Files.readAllBytes(fixture.databasePath))
            gateway.assertUnused()
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}

private class MaintenanceTestGate : DownloadExecutionGate {
    val callbacks = CopyOnWriteArrayList<suspend () -> Unit>()
    val closes = AtomicInteger()
    var acquiring: suspend (suspend () -> Unit) -> Unit = {}
    var closing: suspend (suspend () -> Unit) -> Unit = {}
    override suspend fun acquire(onExpiration: suspend () -> Unit): DownloadExecutionLease {
        callbacks += onExpiration
        acquiring(onExpiration)
        return DownloadExecutionLease { try { closing(onExpiration) } finally { closes.incrementAndGet() } }
    }
}
