package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlin.test.*

/** Real Room/Coordinator/file leases; fake source/native and authored NON-AUDIO. AUTHORED0RUN. */
class DownloadMemberRetryLifetimeRoomTest {
    @Test fun coldReopenRetainsRetryAndLaterActivatedExecutionUsesTheOriginalAcceptedContextAndKey() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old)
        val admitted = attachRetryMember(old, batch)
        failRetryJob(old, admitted.fence.job.physicalJobId)
        offline(old)
        val command = retryRoomCommand(old, batch.id)
        val retryResult = old.adapter.retryMember(command)
        assertIs<AcquisitionDownloadRetryResult.Retried>(retryResult)
        val retried = old.store.acquisitionView()
        val retriedJob = retried.acquisition.physicalDownloads.getValue(command.expectedJob.id)
        assertEquals(DownloadStatus.QUEUED, retriedJob.status, "Explicit Retry must commit a queued reset row")
        assertNull(retriedJob.failure)
        assertNull(retriedJob.nextRetryEpochMs)
        assertNull(retriedJob.leaseOwner)
        assertNull(retriedJob.activeAttemptId)
        assertEquals(command.expectedJob.producer, retriedJob.producer)
        val retriedClaim = retried.acquisition.claims.getValue(command.expectedClaim.id)
        assertEquals(command.expectedClaim.generation + 1, retriedClaim.generation)
        assertEquals(command.expectedClaim.ownerIntentRevision, retriedClaim.ownerIntentRevision)
        val accepted = retried.acquisition.executionContext(command.member.requestedBindingId)
        val priorAttempts = retried.acquisition.physicalAttempts
        val priorRequests = retried.acquisition.requests.keys
        val priorBindings = retried.acquisition.bindings.keys
        val priorReceipts = retried.acquisition.receipts.keys
        val priorRevision = retried.snapshot.revision
        assertNoRetryWork(old)
        old.coordinator.close()
        old.store.close()
        val restored = owner()
        restored.coordinator.initializeScheduling()
        val reopened = restored.store.acquisitionView()
        assertEquals(retried.snapshot, reopened.snapshot,
            "Cold reopen changed the snapshot at revision $priorRevision -> ${reopened.snapshot.revision}")
        assertEquals(retried.acquisition, reopened.acquisition,
            "Cold reopen changed the canonical graph at library revision " +
                "${retried.acquisition.libraryRevision} -> ${reopened.acquisition.libraryRevision}")
        assertNoRetryWork(restored)
        restored.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(restored)
        val stillOffline = restored.store.acquisitionView().acquisition.physicalDownloads.getValue(command.expectedJob.id)
        assertEquals(DownloadStatus.QUEUED, stillOffline.status,
            "Explicit Retry remains metadata-only while offline; no new owner is admitted")
        assertNull(stillOffline.leaseOwner)
        assertNull(stillOffline.activeAttemptId)
        assertNull(stillOffline.failure)
        assertNull(stillOffline.nextRetryEpochMs)
        assertEquals(0, restored.gate.acquires.get())
        assertEquals(FailureCode.OFFLINE_POLICY, restored.coordinator.schedulingPlan().blockedReason?.code)
        assertTrue(restored.gateway.preparations.isEmpty(), "Explicit retry does not waive the offline execution gate")
        assertTrue(restored.gateway.transfers.isEmpty())
        assertTrue(restored.selector.requests.isEmpty())
        offline(restored, false)
        val complete = awaitState(restored.store) { it.physicalDownloads[command.expectedJob.id]?.status == DownloadStatus.COMPLETE }
        idle(restored)
        assertEquals(1, restored.gateway.preparations.size)
        assertEquals(1, restored.gateway.transfers.size)
        assertEquals(accepted, restored.gateway.preparations.single().context)
        assertEquals(command.expectedJob.representationId, restored.gateway.preparations.single().requiredRepresentationId)
        assertTrue(restored.selector.requests.isEmpty(), "No new first-result request on Retry or restart")
        assertEquals(priorRequests, complete.requests.keys, "Retry/restart must not create a new acquisition request")
        assertEquals(priorBindings, complete.bindings.keys, "Retry/restart must not create a new execution binding")
        assertEquals(priorReceipts, complete.receipts.keys, "Retry/restart must not create a new first-result receipt")
        assertEquals(priorAttempts, complete.physicalAttempts.filterKeys { it in priorAttempts })
        assertEquals(command.expectedJob.producer, complete.physicalDownloads.getValue(command.expectedJob.id).producer)
        assertEquals(command.expectedJob.attemptCount + 1, complete.physicalDownloads.getValue(command.expectedJob.id).attemptCount)
        restored.legacy.assertUnused()
    }

    @Test fun physicalOwnerCannotBeBypassedEvenAfterAnotherCanonicalWriterClearsItsDurableLease() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val admitted = attachRetryMember(owner, batch)
        val transferring = barrier()
        owner.gateway.transfer = { _, _, _ ->
            // This intentionally uncooperative authored source owner outlives durable finish.
            withContext(NonCancellable) { transferring.stop() }
            throw CancellationException("Authored transfer owner released")
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { transferring.entered.await() }
        val held = assertNotNull(owner.repository.physicalDownload(admitted.fence.job.physicalJobId))
        val attempt = assertNotNull(held.activeAttempt)
        assertTrue(leases.open.get() > 0)
        val fence = PhysicalDownloadFence(held.job.id, held.job.producer, held.job.ownershipGeneration,
            assertNotNull(held.job.leaseOwner), attempt.id, attempt.number)
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
            owner.repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(fence, DownloadStatus.FAILED,
                DownloadAttemptStatus.FAILED, AppFailure(FailureCode.STORAGE_IO), null, 5, clock.epochMs())))
        val command = retryRoomCommand(owner, batch.id)
        assertTrue(isExplicitMemberRetryCandidate(command.expectedJob), "Durable candidate alone is not physical admission")
        assertNull(command.expectedJob.leaseOwner)
        assertNull(command.expectedJob.activeAttemptId)
        val before = owner.store.acquisitionView()
        val rejected = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
        assertEquals(FailureCode.RECOVERY_REQUIRED, rejected.failure.code)
        assertEquals(before, owner.store.acquisitionView())
        assertTrue(leases.open.get() > 0, "Failed retry cannot silently consume the retained physical owner")
        assertEquals(1, owner.gateway.preparations.size)
        transferring.release()
        idle(owner)
        assertTrue(owner.selector.requests.isEmpty())
    }

    @Test fun unrelatedInFlightHostDrainRejectsRetryUntilTheActualAllowanceOwnerReturns() = acquisitionDownloadRoomCase {
        val owner = owner()
        val targetBatch = batch(owner)
        val target = attachRetryMember(owner, targetBatch, video = "LMNOPQRSTUV")
        failRetryJob(owner, target.fence.job.physicalJobId)
        val activeBatch = batch(owner)
        val active = attachRetryMember(owner, activeBatch, video = "ABCDEFGHIJK")
        val transferring = barrier()
        val closing = barrier()
        owner.gateway.transfer = { _, _, _ -> transferring.stop(); error("Authored transfer must be cancelled by host drain") }
        owner.gate.beforeClose = { closing.stop() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { transferring.entered.await() }
        coroutineScope {
            val draining = async { owner.coordinator.drainForBackground() }
            try {
                withTimeout(10_000) { closing.entered.await() }
                assertFalse(draining.isCompleted, "Drain still owns the returned native allowance")
                val command = retryRoomCommand(owner, targetBatch.id)
                val before = owner.store.acquisitionView()
                val rejected = assertIs<AcquisitionDownloadRetryResult.Unavailable>(owner.adapter.retryMember(command))
                assertEquals(FailureCode.BACKGROUND_RESTRICTED, rejected.failure.code)
                val after = owner.store.acquisitionView()
                assertEquals(before.acquisition.claims[command.expectedClaim.id], after.acquisition.claims[command.expectedClaim.id])
                assertEquals(before.acquisition.physicalDownloads[command.expectedJob.id], after.acquisition.physicalDownloads[command.expectedJob.id])
                assertEquals(before.acquisition.batches[targetBatch.id], after.acquisition.batches[targetBatch.id])
            } finally {
                // Failure must release the non-cancellable drain child before coroutineScope can return.
                closing.release()
                transferring.release()
            }
            draining.await()
        }
        assertEquals(active.fence.job.recordingId, owner.gateway.preparations.single().context.recording.id)
        assertTrue(owner.selector.requests.isEmpty())
    }
}
