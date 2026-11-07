package dev.veyra.downloads

import dev.veyra.source.execution.AcquisitionTransferProgress
import dev.veyra.source.execution.AcquisitionTransferResult
import dev.veyra.data.LibraryPersistence
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.data.PhysicalDownloadCommit
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
    @Test fun startupReturnsWithHonestCheckingProjectionAndDoesNotBlockAnotherAcquisition() = executorSchema3Case {
        val setup = owner()
        val (oldId, _) = enqueue(setup, item("old-completed"), authoredVideo)
        setup.coordinator.start(DownloadActivation.DURABLE_WORK)
        val seeded = complete(setup, oldId)
        val oldAsset = assertNotNull(seeded.asset)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)
        val checking = barrier()
        val a = owner(setup.store)
        a.validation = { asset ->
            if (asset.uri == oldAsset.uri) checking.stop()
            AssetValidation.Valid(31_234)
        }
        a.coordinator.start() // USER_SESSION: completed files are checked by THIS process.
        checking.entered.await()
        assertEquals(DownloadStatus.COMPLETE, join(a, oldId).job.status)
        assertEquals(DownloadStatus.VERIFYING, a.coordinator.physicalStates.value[oldId]?.status)
        withTimeout(3_000) { a.coordinator.hasPendingWork.first { it } }
        assertTrue(a.gate.expirations.isNotEmpty(), "Native validation must have an execution allowance")
        val (freshId, freshSelection) = enqueue(a, item("new-acquisition"), "NEWACQ00001")
        complete(a, freshId)
        assertEquals(listOf(freshSelection.record.request), a.selector.requests)
        assertFalse(checking.released.isCompleted, "A large existing library must not block newly requested acquisition")
        assertEquals(1, a.gateway.transfers.size)
        checking.release()
        withTimeout(3_000) { a.coordinator.physicalStates.first { it[oldId]?.status == DownloadStatus.COMPLETE } }
        assertEquals(2, a.validations.size)
        assertEquals(oldAsset, join(a, oldId).asset)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(oldAsset.uri)))
    }

    @Test fun deniedRecoveryAllowanceKeepsJournalAndRetriesOnlyAfterForegroundResume() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        offline(owner)
        val denied = AtomicBoolean(true)
        owner.gate.beforeAcquire = {
            if (denied.get()) throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED))
        }
        owner.validation = { AssetValidation.Valid(31_234) }
        owner.denyFinalizationSchema3Acquisition()
        owner.coordinator.start()
        val blocked = awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.NEEDS_FOREGROUND && d.leaseOwner == null
        } == true }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.physicalDownloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, blocked.physicalFinalizations[seeded.record.id])
        assertEquals(seeded.attempt, blocked.physicalAttempts[seeded.attempt.id])
        assertEquals(0, owner.validations.size)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        delay(160)
        assertEquals(1, owner.gate.expirations.size, "A denied allowance must not be polled in a retry loop")
        withTimeout(3_000) { owner.coordinator.hasPendingWork.first { !it } }
        denied.set(false)
        owner.coordinator.resumePending()
        val ready = awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertNull(ready.physicalDownloads[seeded.record.id]?.failure)
        assertFalse(seeded.record.id in ready.physicalFinalizations)
        idle(owner)
        owner.assertFinalizationSchema3Recovered(seeded)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun deniedCompletedCheckKeepsDurableCompleteButPresentsNeedsForegroundUntilAnAllowedRecheck() = acquisitionDownloadRoomCase {
        val setup = owner()
        val (id, _) = complete(setup)
        val completed = join(setup, id)
        val asset = assertNotNull(completed.asset)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)
        val denied = AtomicBoolean(true)
        val checkingCallbacks = AtomicInteger()
        val a = owner(setup.store)
        a.gate.beforeAcquire = {
            checkingCallbacks.incrementAndGet()
            if (denied.get()) throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED))
        }
        a.coordinator.start() // USER_SESSION: the completed file is rechecked by THIS process.
        val blocked = awaitState(a.store) { state ->
            state.physicalDownloads[id]?.let {
                it.status == DownloadStatus.NEEDS_FOREGROUND && it.failure?.code == FailureCode.BACKGROUND_RESTRICTED &&
                    it.leaseOwner == null
            } == true
        }
        assertEquals(asset.id, blocked.physicalDownloads[id]?.assetId)
        val presented = withTimeout(3_000) { a.coordinator.physicalStates.first {
            it[id]?.status == DownloadStatus.NEEDS_FOREGROUND
        } }
        assertEquals(asset.id, presented[id]?.assetId)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, presented[id]?.failure?.code)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertEquals(0, a.validations.size)
        delay(160)
        assertEquals(1, checkingCallbacks.get(), "Denied verification must not acquire another OS allowance")
        assertEquals(0, a.gate.closes.get(), "A denied acquire did not return a lease to close")
        assertEquals(1, a.gate.expirations.size)
        withTimeout(3_000) { a.coordinator.hasPendingWork.first { !it } }
        denied.set(false)
        a.coordinator.resumePending()
        withTimeout(3_000) { a.coordinator.physicalStates.first { it[id]?.status == DownloadStatus.COMPLETE } }
        val verified = awaitState(a.store) { state ->
            state.physicalDownloads[id]?.leaseOwner == null && a.gate.closes.get() == 1
        }
        assertNull(verified.physicalDownloads[id]?.failure)
        assertEquals(asset.id, verified.physicalDownloads[id]?.assetId)
        assertEquals(1, a.validations.size)
        assertEquals(2, checkingCallbacks.get())
        a.legacy.assertUnused()
        a.coordinator.close()
        assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun inlineMaintenanceAcquireExpirationCannotMissItsRegisteredChildOrSelfJoin() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner, published = true)
        owner.gate.beforeAcquire = { expiration -> expiration() }
        owner.validation = { AssetValidation.Valid(31_234) }
        owner.denyFinalizationSchema3Acquisition()
        owner.coordinator.start()
        val blocked = awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.NEEDS_FOREGROUND && d.leaseOwner == null
        } == true && owner.gate.closes.get() == 1 }
        assertEquals(1, owner.gate.expirations.size)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, blocked.physicalDownloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, blocked.physicalFinalizations[seeded.record.id])
        assertEquals(seeded.attempt, blocked.physicalAttempts[seeded.attempt.id])
        assertEquals(0, owner.validations.size)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent.finalPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.stagingPath)))
        owner.assertFinalizationSchema3SourceUnused()
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun expiredCheckpointRollbackStillCancelsMaintenanceAndFinishRetainsTypedJournal() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val acquiring = barrier()
        owner.gate.beforeAcquire = { acquiring.stop() }
        owner.denyFinalizationSchema3Acquisition()
        owner.validation = { error("An expired checkpoint must cancel before native validation") }
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_maintenance_checkpoint_rollback")
        val failOnce = AtomicBoolean(true)
        faults(owner.store).beforeCommit = { before, next ->
            if (maintenanceHeartbeat(before.state(), next.state(), seeded.record.id) && failOnce.compareAndSet(true, false))
                throw VeyraException(failure)
        }
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        acquiring.entered.await()
        awaitCondition("The real maintenance heartbeat did not roll back") { !failOnce.get() }
        awaitCondition { scheduler.lastFailure.value == failure }
        val expiration: suspend () -> Unit = owner.gate.expirations.single()
        withTimeout(3_000) { expiration() }
        acquiring.release()
        val blocked = awaitState(owner.store) { state ->
            state.physicalDownloads[seeded.record.id]?.let {
                it.status == DownloadStatus.NEEDS_FOREGROUND && it.leaseOwner == null && it.failure == failure
            } == true
        }
        assertEquals(seeded.intent, blocked.physicalFinalizations[seeded.record.id])
        assertEquals(seeded.attempt, blocked.physicalAttempts[seeded.attempt.id])
        assertNull(blocked.physicalDownloads[seeded.record.id]?.assetId)
        assertEquals(0, owner.validations.size)
        assertEquals(1, owner.gate.closes.get())
        assertEquals(failure, scheduler.lastFailure.value)
        owner.assertFinalizationSchema3SourceUnused()
        scheduler.close()
        assertTrue(scheduler.dependencyReleaseSafeAfterClose)
    }

    @Test fun cleanupCannotReleaseNativeReadersOrDeleteBeforeItsAllowanceIsGranted() = acquisitionDownloadRoomCase {
        val setup = owner()
        val (id, _) = complete(setup)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)
        val permission = barrier()
        val nativeReleases = AtomicInteger()
        val a = owner(setup.store)
        a.gate.beforeAcquire = { permission.stop() }
        a.beforeDelete = { nativeReleases.incrementAndGet() }
        a.coordinator.start(DownloadActivation.DURABLE_WORK)
        val removal = parent.async(start = CoroutineStart.UNDISPATCHED) { runCatching { a.coordinator.remove(id) } }
        permission.entered.await()
        assertEquals(0, nativeReleases.get())
        val blocked = join(a, id)
        assertTrue(blocked.job.cleanupPending)
        val asset = assertNotNull(blocked.asset)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        assertNull(files.tryLeasePaths(listOf(asset.uri)))
        permission.release()
        val removed = awaitJob(a, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertEquals(1, nativeReleases.get())
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertNull(removal.await().exceptionOrNull())
        assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(a.selector.requests.isEmpty())
        a.legacy.assertUnused()
        a.coordinator.close()
        assertTrue(a.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun cleanupExpirationRetainsAllReferencesThroughNoncancellableUnwindAndOldEpochCallback() = executorSchema3Case {
        val setup = owner()
        val (id, _) = enqueue(setup)
        setup.coordinator.start(DownloadActivation.DURABLE_WORK)
        val completed = complete(setup, id)
        val asset = assertNotNull(completed.asset)
        setup.coordinator.close()
        assertTrue(setup.coordinator.dependencyReleaseSafeAfterClose)
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val releases = AtomicInteger()
        val a = owner(setup.store)
        a.beforeDelete = {
            if (releases.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.stop() } }
            }
        }
        val removal = parent.async(start = CoroutineStart.UNDISPATCHED) { runCatching { a.coordinator.remove(id) } }
        entered.await()
        val expire: suspend () -> Unit = a.gate.expirations[0]
        withTimeout(3_000) { expire() }
        unwinding.entered.await()
        val blocked = join(a, id)
        assertTrue(blocked.job.cleanupPending && blocked.job.removeWhenClean)
        assertEquals(asset, blocked.asset)
        assertNotNull(blocked.job.leaseOwner, "The maintenance lease covers the noncancellable native unwind")
        assertEquals(0, a.gate.closes.get(), "The OS lease must cover actual noncancellable release unwind")
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(asset.uri)))
        a.coordinator.resumePending()
        // A queued OLD foreground epoch must not suspend the resumed host or close its allowance.
        withTimeout(3_000) { expire() }
        assertEquals(0, a.gate.closes.get())
        unwinding.release()
        val held = awaitJob(a, id) { it.job.status == DownloadStatus.NEEDS_FOREGROUND && it.job.leaseOwner == null }
        assertEquals(asset, held.asset)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, held.job.failure?.code)
        advance(5_000)
        val removed = awaitJob(a, id) { !it.job.cleanupPending && it.asset == null }
        assertEquals(DownloadStatus.CANCELLED, removed.job.status)
        assertEquals(2, releases.get())
        assertEquals(2, a.gate.closes.get())
        assertFalse(Files.exists(Path.of(asset.uri)))
        assertNull(removal.await().exceptionOrNull())
        a.legacy.assertUnused()
        assertTrue(a.gateway.transfers.isEmpty())
        assertTrue(a.selector.requests.isEmpty())
    }

    @Test fun cancelOnlyExpirationAllowsExplicitForegroundResumeWithoutKillingRecoveryScheduler() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val entered = CompletableDeferred<Unit>()
        val unwinding = barrier()
        val nativeCalls = AtomicInteger()
        owner.validation = {
            if (nativeCalls.incrementAndGet() == 1) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { unwinding.stop() } }
            }
            AssetValidation.Valid(31_234)
        }
        owner.denyFinalizationSchema3Acquisition()
        val scheduler = owner.coordinator
        scheduler.start(DownloadActivation.DURABLE_WORK)
        entered.await()
        val oldExpiration: suspend () -> Unit = owner.gate.expirations[0]
        coroutineScope {
            val expiration = async { oldExpiration() }
            try {
                unwinding.entered.await()
                expiration.await() // Callback completion is NOT the independent native/Room owner drain.
                val checkpoint = owner.store.acquisitionView().acquisition
                // The typed callback publishes no Room write: the maintenance lease still covers
                // the actual noncancellable native/source unwind, and the journal is unchanged.
                assertEquals(DownloadStatus.VERIFYING, checkpoint.physicalDownloads[seeded.record.id]?.status)
                assertNotNull(checkpoint.physicalDownloads[seeded.record.id]?.leaseOwner)
                assertEquals(seeded.intent, checkpoint.physicalFinalizations[seeded.record.id])
                assertEquals(0, owner.gate.closes.get())
            } finally { unwinding.release() }
        }
        val held = awaitState(owner.store) { state ->
            state.physicalDownloads[seeded.record.id]?.let { it.status == DownloadStatus.NEEDS_FOREGROUND && it.leaseOwner == null } == true
        }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, held.physicalDownloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.intent, held.physicalFinalizations[seeded.record.id])
        assertEquals(1, owner.gate.closes.get())
        val secondAcquire = barrier()
        owner.gate.beforeAcquire = { if (owner.gate.expirations.size >= 2) secondAcquire.stop() }
        scheduler.resumePending()
        secondAcquire.entered.await()
        oldExpiration() // Queued OLD foreground epoch must not suspend the resumed host.
        assertEquals(1, owner.gate.closes.get(), "The consumed old allowance is never closed twice")
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        secondAcquire.release()
        awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        assertEquals(2, nativeCalls.get())
        assertEquals(2, owner.gate.expirations.size)
        owner.assertFinalizationSchema3Recovered(seeded)
        scheduler.close()
        assertTrue(scheduler.dependencyReleaseSafeAfterClose)
    }

    @Test fun completedMaintenanceCloseAndQueuedCallbackCannotSuspendAFreshTransfer() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        owner.validation = { AssetValidation.Valid(31_234) }
        owner.gate.beforeClose = {
            val expiration: suspend () -> Unit = owner.gate.expirations[owner.gate.expirations.size - 1]
            withContext(NonCancellable) { expiration() }
        }
        owner.denyFinalizationSchema3Acquisition()
        val holding = barrier()
        owner.gateway.transfer = { prepared, path, progress ->
            holding.stop()
            Files.write(Path.of(path), authoredBytes)
            progress(AcquisitionTransferProgress(authoredBytes.size.toLong(), authoredBytes.size.toLong()))
            AcquisitionTransferResult(prepared.context, authoredBytes.size.toLong(), prepared.representation)
        }
        val a = owner.coordinator
        a.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE && owner.gate.closes.get() >= 1 }
        owner.assertFinalizationSchema3Recovered(seeded)
        val oldExpiration: suspend () -> Unit = owner.gate.expirations[0]
        val accepted = acceptNeutral(owner, "after-recovery", "AFTERRECOV1")
        val id = enqueue(owner, accepted)
        holding.entered.await()
        val before = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        oldExpiration()
        delay(160)
        val after = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(DownloadStatus.RUNNING, after.status)
        assertEquals(before.activeAttemptId, after.activeAttemptId)
        assertEquals(before.leaseOwner, after.leaseOwner)
        assertEquals(before.ownershipGeneration, after.ownershipGeneration)
        holding.release()
        awaitState(owner.store) { it.physicalDownloads[id]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(2, owner.gate.closes.get())
        assertEquals(2, owner.gate.expirations.size)
        owner.legacy.assertUnused()
        a.close()
        assertTrue(a.dependencyReleaseSafeAfterClose)
    }

    @Test fun idempotentForegroundAnnouncementKeepsAnActiveMaintenanceEpoch() = acquisitionDownloadRoomCase {
        val owner = owner()
        val seeded = prepareFinalizationSchema3(owner)
        val validation = barrier()
        owner.validation = { validation.stop(); AssetValidation.Valid(31_234) }
        owner.denyFinalizationSchema3Acquisition()
        owner.coordinator.start(); validation.entered.await()
        val beforeState = owner.store.acquisitionView().acquisition
        val before = beforeState.physicalDownloads.getValue(seeded.record.id)
        assertNotNull(before.leaseOwner)
        // The canonical seed retired its writer. Its journal, not activeAttemptId=null,
        // is the non-vacuous identity of the materializer undergoing recovery.
        assertEquals(seeded.attempt.id, beforeState.physicalFinalizations.getValue(seeded.record.id).attemptId)
        assertEquals(seeded.attempt, beforeState.physicalAttempts[seeded.attempt.id])
        owner.coordinator.resumePending()
        delay(160)
        val afterState = owner.store.acquisitionView().acquisition
        val after = afterState.physicalDownloads.getValue(seeded.record.id)
        assertEquals(DownloadStatus.VERIFYING, after.status)
        assertEquals(before.leaseOwner, after.leaseOwner)
        assertEquals(before.activeAttemptId, after.activeAttemptId)
        assertEquals(before.ownershipGeneration, after.ownershipGeneration)
        assertEquals(seeded.intent, afterState.physicalFinalizations[seeded.record.id])
        assertEquals(seeded.attempt, afterState.physicalAttempts[seeded.attempt.id])
        validation.release()
        awaitState(owner.store) { it.physicalDownloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
        idle(owner)
        owner.assertFinalizationSchema3Recovered(seeded)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun cancelledDispatcherReturnAfterRealExecutionClaimCommitReleasesItsExactLease() = executorSchema3Case {
        // EX19: returned actual COMMIT is retained across cancellation, not replaced with a fake claim.
        val owner = owner()
        val once = AtomicBoolean(true)
        val committed = barrier()
        owner.repository.afterAttemptClaim = { _, actual ->
            if (actual is PhysicalDownloadCommit.Committed && once.compareAndSet(true, false)) {
                committed.stop()
                parent.cancel(CancellationException("Authored parent shutdown after real claim COMMIT"))
            }
        }
        val (id, selected) = enqueue(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        committed.entered.await()
        val claimed = join(owner, id)
        assertNotNull(claimed.activeAttempt)
        assertNull(probeFiles.tryLeasePaths(listOf(assertNotNull(claimed.job.stagingPath))))
        committed.release()
        val released = awaitJob(owner, id) {
            !once.get() && it.job.leaseOwner == null && it.activeAttempt == null &&
                it.attempts.singleOrNull()?.finishedEpochMs != null
        }
        assertEquals(DownloadStatus.QUEUED, released.job.status)
        assertEquals(selected.context, released.producerContext)
        assertEquals(1, released.attempts.size)
        assertEquals(claimed.job.activeAttemptId, released.attempts.single().id)
        assertNotNull(probeFiles.tryTransferSlot(1)).close()
        assertNotNull(probeFiles.tryLeasePaths(listOf(assertNotNull(released.job.stagingPath)))).close()
        assertEquals(0, owner.gate.acquires.get())
        assertTrue(owner.gateway.prepareCalls.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        assertEquals(1, owner.selector.requests.size)
        owner.legacy.assertUnused()
    }

    @Test fun cancelledDispatcherReturnAfterRealMaintenanceCommitReleasesBeforeGateAcquisition() = executorSchema3Case {
        val (store, port) = maintenancePortStore()
        val ownerScope = extraScope()
        val owner = owner(store = store, parentScope = ownerScope)
        val (id, _) = enqueue(owner)
        val intent = recoveryIntent(owner, id)
        advance(1_100) // Authored process-loss setup expires only its real canonical lease.
        val once = AtomicBoolean(true)
        val committed = barrier()
        port.afterCommit = { state ->
            if (state.maintenanceLease(id) && once.compareAndSet(true, false)) {
                committed.stop()
                ownerScope.cancel(CancellationException("Authored dispatcher return after real maintenance COMMIT"))
            }
        }
        val scheduler = owner.coordinator
        try {
            scheduler.start(DownloadActivation.DURABLE_WORK)
            committed.entered.await()
            committed.release()
            val released = awaitJob(owner, id) { !once.get() && it.job.leaseOwner == null }
            assertEquals(intent, released.finalization)
            assertNull(released.asset)
            assertNull(released.job.cleanupPending)
            assertEquals(0, owner.gate.acquires.get(), "Cancellation must release the exact lease before a new gate")
            assertEquals(0, owner.validations.size)
            assertNotNull(probeFiles.tryLeasePaths(listOf(intent.stagingPath, intent.finalPath))).close()
            assertNotNull(probeFiles.tryTransferSlot(1)).close()
            assertTrue(owner.gateway.transfers.isEmpty())
            assertTrue(owner.gateway.prepareCalls.isEmpty())
            assertTrue(owner.selector.requests.size == 1)
            owner.legacy.assertUnused()
        } finally {
            port.clearFaults()
            scheduler.close()
            assertTrue(scheduler.dependencyReleaseSafeAfterClose)
            store.close()
        }
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
