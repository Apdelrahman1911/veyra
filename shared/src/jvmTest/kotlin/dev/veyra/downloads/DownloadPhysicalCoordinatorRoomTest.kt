package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Existing Coordinator + REAL canonical Room/OS locks; deliberately fake source/native media. */
class DownloadPhysicalCoordinatorRoomTest {
    @Test fun pauseDuringHeldLocalValidationFinalizesAgainstFreshCanonicalClaimGenerations() =
        validationIntentChange(DownloadBatchIntent.PAUSED, retain = true)

    @Test fun removeDuringHeldLocalValidationFinalizesFreshClaimsBeforeTheLaterFileCleanup() =
        validationIntentChange(DownloadBatchIntent.CANCELLED, retain = false)

    @Test fun acceptedCheckpointFailureBeforeAnyHostDrainSurvivesForgottenJobAndEmptyCleanupRegistry() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_real_room_finish_rollback")
        val failedFinish = CompletableDeferred<Unit>()
        val finishCalls = AtomicInteger()
        owner.gateway.transfer = { _, _, _ -> throw VeyraException(AppFailure(FailureCode.NETWORK_UNAVAILABLE)) }
        faults(owner.store).beforeCommit = { before, after ->
            val old = before.state().physicalDownloads[id]
            val next = after.state().physicalDownloads[id]
            if (old?.leaseOwner != null && next?.leaseOwner == null) {
                finishCalls.incrementAndGet()
                failedFinish.complete(Unit)
                throw VeyraException(failure)
            }
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { failedFinish.await() }
        idle(owner) // The failed finalizer has ALREADY retired its real Job and physical owner.
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertEquals(0, leases.open.get())
        assertEquals(1, owner.gate.closes.get())
        val persisted = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertNotNull(persisted.leaseOwner, "Real SQLite rollback must preserve the unconfirmed durable claim")
        assertNotNull(persisted.activeAttemptId)
        assertEquals(failure, owner.coordinator.drainForBackground().checkpointFailure)
        assertEquals(failure, owner.coordinator.drainForBackground().checkpointFailure)
        assertEquals(1, finishCalls.get(), "Host drains do not silently replay an old failed canonical finish")
        assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(failure, assertFailsWith<VeyraException> { owner.coordinator.close() }.failure)
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun refusedNativeSlotAndStaleNoResourceRollbackNeverCreateAStickyCheckpointObligation() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val refused = AtomicInteger()
        val rollbackCalls = AtomicInteger()
        leases.refuse = { name -> (name == "slot-admission").also { if (it) refused.incrementAndGet() } }
        owner.repository.beforeAttemptFinish = { rollbackCalls.incrementAndGet() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { refused.get() > 0 && rollbackCalls.get() > 0 }
        idle(owner)
        val unclaimed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(0, unclaimed.attemptCount)
        assertNull(unclaimed.leaseOwner)
        assertEquals(0, owner.gate.acquires.get())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertNull(owner.coordinator.drainForBackground().checkpointFailure)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(0, leases.open.get())
    }

    @Test fun actuallyAcceptedReturnedPortMismatchAndFailedRollbackRemainAStickyCheckpointFailure() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val claims = AtomicInteger()
        val finishes = AtomicInteger()
        val failure = AppFailure(FailureCode.STORAGE_IO, "authored_accepted_rollback_failure")
        owner.repository.afterAttemptClaim = { returned ->
            if (returned is PhysicalDownloadCommit.Committed) {
                claims.incrementAndGet()
                returned.copy(value = returned.value.copy(fence = returned.value.fence.copy(leaseOwner = "wrong-returned-port-owner")))
            } else returned
        }
        owner.repository.beforeAttemptFinish = { finishes.incrementAndGet(); throw VeyraException(failure) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { claims.get() == 1 && finishes.get() == 1 && leases.open.get() == 0 }
        idle(owner)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        val actual = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
        assertEquals(1, actual.attemptCount)
        assertNotEquals("wrong-returned-port-owner", assertNotNull(actual.leaseOwner))
        assertEquals(0, owner.gate.acquires.get(), "The bad returned join must fail before native/source admission")
        assertTrue(owner.gateway.preparations.isEmpty())
        assertEquals(failure, owner.coordinator.drainForBackground().checkpointFailure)
        assertEquals(1, finishes.get())
    }

    @Test fun inlinePhysicalAllowanceExpirationCapturesReturnedNativeOwnerBeforeAnySourcePreparation() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        owner.gate.beforeAcquire = { expire -> expire() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { it.physicalDownloads[id]?.status == DownloadStatus.NEEDS_FOREGROUND }
        idle(owner)
        assertEquals(1, owner.gate.acquires.get())
        assertEquals(1, owner.gate.closes.get())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.validations.isEmpty())
        assertEquals(0, leases.open.get())
        assertNull(owner.coordinator.drainForBackground().checkpointFailure)
    }

    @Test fun oldConsumedPhysicalAllowanceCallbackCannotCancelOrSuspendASuccessor() = acquisitionDownloadRoomCase {
        val owner = owner()
        complete(owner, "first", "LMNOPQRSTUV")
        val retiredExpiration = owner.gate.expirations.single()
        val transferred = barrier()
        val actualTransfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, progress ->
            transferred.stop()
            actualTransfer(prepared, path, progress)
        }
        val second = enqueue(owner, acceptNeutral(owner, "successor", "ABCDEFGHIJK"))
        withTimeout(10_000) { transferred.entered.await() }
        retiredExpiration()
        delay(90)
        val running = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(second)
        assertEquals(DownloadStatus.RUNNING, running.status)
        assertNotNull(running.leaseOwner)
        assertEquals(1, owner.gate.closes.get(), "A consumed owner's callback must not close the successor allowance")
        transferred.release()
        awaitState(owner.store) { it.physicalDownloads[second]?.status == DownloadStatus.COMPLETE }
        idle(owner)
        assertEquals(2, owner.gateway.transfers.size)
        assertEquals(2, owner.gate.closes.get())
    }

    @Test fun offlinePolicyCancelsAnActualTransferButNotAnOwnedCompletedAssetValidation() = acquisitionDownloadRoomCase {
        val old = owner()
        val (completedId, _) = complete(old)
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        val validating = barrier()
        resumed.validation = { validating.stop(); AssetValidation.Valid(733) }
        val transferring = barrier()
        resumed.gateway.transfer = { _, _, _ -> transferring.stop(); error("Cancelled transfer cannot complete") }
        val unfinishedId = enqueue(resumed, acceptNeutral(resumed, "other-recording", "ABCDEFGHIJK"))
        resumed.coordinator.start(DownloadActivation.USER_SESSION)
        withTimeout(10_000) { validating.entered.await(); transferring.entered.await() }
        offline(resumed)
        awaitState(resumed.store) { it.physicalDownloads[unfinishedId]?.status == DownloadStatus.WAITING_NETWORK &&
            it.physicalDownloads[unfinishedId]?.leaseOwner == null }
        val held = resumed.store.acquisitionView().acquisition.physicalDownloads.getValue(completedId)
        assertEquals(PhysicalDownloadMaintenanceKind.VALIDATE, held.maintenanceKind)
        assertNotNull(held.leaseOwner)
        assertEquals(1, resumed.gate.closes.get(), "Offline must not cancel the local validation owner")
        validating.release()
        awaitState(resumed.store) { it.physicalDownloads[completedId]?.leaseOwner == null }
        idle(resumed)
        awaitCondition { resumed.coordinator.acquisitionPresentation.value.states[completedId]?.status == DownloadStatus.COMPLETE }
        assertEquals(1, resumed.validations.size)
        assertEquals(1, resumed.gateway.transfers.size)
    }

    @Test fun restartedCheckingDoesNotGrantR2ReadyUntilThatExactBindingGetsItsOwnAssetUse() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old, count = 2)
        old.coordinator.start(DownloadActivation.DURABLE_WORK)
        val completed = awaitState(old.store) { state -> state.batches.getValue(batch.id).members.all { it.claimId != null } &&
            state.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE }
        idle(old)
        val job = completed.physicalDownloads.values.single()
        val asset = completed.materializations.getValue(assertNotNull(job.assetId))
        val second = completed.batches.getValue(batch.id).members.single { it.requiredBindingId != job.producer.bindingId }
        assertTrue(completed.assetUses.none { it.requestedBindingId == second.requiredBindingId })
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        offline(resumed)
        resumed.coordinator.initializeScheduling()
        awaitCondition { resumed.coordinator.acquisitionPresentation.value.states[job.id]?.status == DownloadStatus.VERIFYING }
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(resumed)
        assertTrue(resumed.validations.isEmpty())
        assertEquals(DownloadStatus.VERIFYING, resumed.coordinator.acquisitionPresentation.value.states.getValue(job.id).status)
        resumed.coordinator.start(DownloadActivation.USER_SESSION)
        awaitCondition { resumed.coordinator.acquisitionPresentation.value.states[job.id]?.status == DownloadStatus.COMPLETE }
        idle(resumed)
        val physicallyChecked = resumed.store.acquisitionView().acquisition
        assertTrue(physicallyChecked.assetUses.none { it.requestedBindingId == second.requiredBindingId })
        val checked = assertIs<AcquisitionDownloadAssetCheck.Validated>(resumed.adapter.validateMemberAsset(batch.id,
            second.position, batch.intentRevision, asset.id))
        assertEquals(second.requiredBindingId, checked.validation.use.requestedBindingId)
        assertEquals(asset.id, checked.validation.use.materializationAssetId)
        assertEquals(asset, resumed.store.acquisitionView().acquisition.materializations[asset.id])
        assertEquals(2, resumed.validations.size)
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
    }

    private fun validationIntentChange(intent: DownloadBatchIntent, retain: Boolean) = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old)
        old.coordinator.start(DownloadActivation.DURABLE_WORK)
        val complete = awaitState(old.store) { it.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE }
        idle(old)
        val originalJob = complete.physicalDownloads.values.single()
        val originalAsset = complete.materializations.getValue(assertNotNull(originalJob.assetId))
        val originalAttempt = complete.physicalAttempts.getValue(assertNotNull(originalAsset.materializerAttemptId))
        val originalClaim = complete.claims.values.single()
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        val validating = barrier()
        resumed.validation = { validating.stop(); AssetValidation.Valid(733) }
        val finalized = CompletableDeferred<PhysicalDownloadJob>()
        faults(resumed.store).beforeCommit = { before, after ->
            val a = before.state().physicalDownloads[originalJob.id]
            val b = after.state().physicalDownloads[originalJob.id]
            if (a?.maintenanceKind == PhysicalDownloadMaintenanceKind.VALIDATE && a.leaseOwner != null &&
                b != null && b.leaseOwner == null)
                finalized.complete(b)
        }
        resumed.coordinator.start(DownloadActivation.USER_SESSION)
        withTimeout(10_000) { validating.entered.await() }
        val held = resumed.store.acquisitionView().acquisition.physicalDownloads.getValue(originalJob.id)
        assertEquals(PhysicalDownloadMaintenanceKind.VALIDATE, held.maintenanceKind)
        val changed = resumed.batches.setIntent(batch.id, batch.intentRevision, intent, retainCompletedAssets = retain)
        resumed.coordinator.onAcquisitionBatchChanged()
        val latestClaim = resumed.store.acquisitionView().acquisition.claims.getValue(originalClaim.id)
        assertTrue(latestClaim.generation > originalClaim.generation)
        assertEquals(changed.intentRevision, latestClaim.ownerIntentRevision)
        assertTrue(Files.exists(Path.of(originalAsset.uri)), "No deletion before the original validator returns")
        validating.release()
        withTimeout(10_000) { finalized.await() }
        val after = awaitState(resumed.store) { state ->
            val record = state.physicalDownloads.getValue(originalJob.id)
            record.leaseOwner == null && record.maintenanceKind == null &&
                if (retain) record.assetId == originalAsset.id else record.assetId == null && !record.cleanupPending
        }
        idle(resumed)
        assertEquals(originalAttempt, after.physicalAttempts.getValue(originalAttempt.id))
        assertEquals(latestClaim, after.claims.getValue(originalClaim.id))
        assertEquals(retain, Files.exists(Path.of(originalAsset.uri)))
        assertEquals(originalAsset.receipt(), after.materializationReceipts[originalAsset.id])
        assertNull(resumed.coordinator.drainForBackground().checkpointFailure,
            "A changed claim cannot leave a stale VALIDATE finalizer diagnostic")
        assertTrue(resumed.gateway.preparations.isEmpty())
        assertTrue(resumed.selector.requests.isEmpty())
    }
}
