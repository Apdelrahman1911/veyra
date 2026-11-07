package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real Room + existing Coordinator scheduling; every source/native answer is an authored fake. */
class DownloadBatchRecoveryRoomTest {
    @Test fun dependenciesRejectForeignAcquisitionOrBatchOwnerBeforeAnyNativeWork() = acquisitionDownloadRoomCase {
        val first = owner()
        first.coordinator.initializeScheduling()
        val other = owner()
        assertFailsWith<IllegalArgumentException> {
            DownloadAcquisitionDependencies(first.repository, first.gateway, first.localValidator, { }, { }, { },
                other.acquisition, first.batches, first.materializationEvidence)
        }
        assertFailsWith<IllegalArgumentException> {
            DownloadAcquisitionDependencies(first.repository, first.gateway, first.localValidator, { }, { }, { },
                first.acquisition, other.batches, first.materializationEvidence)
        }
        assertEquals(0, first.gate.acquires.get())
        assertEquals(0, other.gate.acquires.get())
    }

    @Test fun coldPendingBatchAndWakeHintUseOnlyRoomWithoutActivationOrFileInitialization() = acquisitionDownloadRoomCase {
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val admitted = batch(owner, count = 2)
        val before = owner.store.acquisitionView()
        owner.coordinator.onAcquisitionBatchChanged()
        val plan = owner.coordinator.schedulingPlan()
        assertEquals(0L, plan.nextNetworkEpochMs)
        assertTrue(plan.hasDueWork)
        assertEquals(before, owner.store.acquisitionView())
        assertEquals(2, before.acquisition.batches.getValue(admitted.id).members.size)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.validations.isEmpty())
        assertEquals(0, owner.gate.acquires.get())
        assertEquals(0, leases.open.get())
        assertFalse(Files.exists(directory.resolve("media")))
    }

    @Test fun aDurableOfflineBatchMutationInvalidatesAnAlreadyIssuedIdleTicket() = acquisitionDownloadRoomCase {
        val owner = owner()
        owner.coordinator.initializeScheduling()
        offline(owner)
        val prior = assertIs<DownloadIdleDecision.Deferred>(owner.coordinator.idleDecision())
        batch(owner)
        owner.coordinator.onAcquisitionBatchChanged()
        var retired = false
        assertFalse(owner.coordinator.confirmIdle(prior.ticket) { retired = true; true })
        assertFalse(retired)
        val after = owner.coordinator.schedulingPlan()
        assertNotEquals(prior.plan.key, after.key)
        assertEquals(FailureCode.OFFLINE_POLICY, after.blockedReason?.code)
        assertNull(after.nextNetworkEpochMs)
        assertTrue(owner.selector.requests.isEmpty())
        assertEquals(0, owner.gate.acquires.get())
    }

    @Test fun restartResolvesCapturedMembersOnceAndSharesOnlyTheExplicitSameCPhysicalJob() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old, count = 2)
        val original = old.store.acquisitionView()
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        resumed.coordinator.initializeScheduling()
        assertTrue(resumed.selector.requests.isEmpty())
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val state = awaitState(resumed.store) { current ->
            current.batches.getValue(batch.id).members.all { it.claimId != null } &&
                current.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE
        }
        idle(resumed)
        assertEquals(batch.members.map { it.requestId }.toSet(), resumed.selector.requests.map { it.id }.toSet())
        assertEquals(2, resumed.selector.requests.size)
        resumed.selector.requests.forEach { request ->
            assertEquals(original.acquisition.requests.getValue(request.id).request.completeTitle, request.completeTitle)
            assertIs<CollectionMemberCapture>(request.metadata)
        }
        val memberBindings = state.batches.getValue(batch.id).members.map { assertNotNull(it.requiredBindingId) }
        assertEquals(2, memberBindings.toSet().size)
        assertEquals(1, state.physicalDownloads.size)
        assertEquals(1, resumed.gateway.transfers.size)
        val job = state.physicalDownloads.values.single()
        assertIs<ExecutableSubject.SourceRecordingSubject>(state.subjects.getValue(job.producer.subjectId))
        val producerBinding = assertNotNull(job.producer.bindingId)
        assertTrue(memberBindings.any { it != producerBinding })
        assertTrue(state.assetUses.none { it.requestedBindingId in memberBindings.filter { id -> id != producerBinding } })
        assertTrue(resumed.store.acquisitionView().snapshot.media.isEmpty())
        assertTrue(resumed.store.acquisitionView().snapshot.downloads.isEmpty())
        resumed.legacy.split.assertUnused()
    }

    @Test fun committedButUnattachedMemberRestoresLocallyOfflineWithoutAnotherSearch() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old)
        val accepted = acceptMember(old, batch, attach = false)
        offline(old)
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val state = awaitState(resumed.store) { it.batches.getValue(batch.id).members.single().claimId != null }
        idle(resumed)
        assertEquals(assertIs<AcquisitionOutcome.Single>(accepted.outcome).bindingId,
            state.batches.getValue(batch.id).members.single().requiredBindingId)
        assertTrue(resumed.selector.requests.isEmpty())
        assertTrue(resumed.gateway.transfers.isEmpty())
        assertTrue(resumed.validations.isEmpty())
        assertEquals(1, resumed.gate.acquires.get())
        assertEquals(1, resumed.gate.closes.get())
    }

    @Test fun alreadyBoundUnclaimedMemberUsesTheExistingExecutorOfflineWithoutSelection() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        acceptMember(owner, batch)
        offline(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val state = awaitState(owner.store) { it.batches.getValue(batch.id).members.single().claimId != null }
        idle(owner)
        val member = state.batches.getValue(batch.id).members.single()
        val claim = state.claims.getValue(assertNotNull(member.claimId))
        assertEquals(member.requiredBindingId, claim.requestedBindingId)
        assertEquals(DownloadClaimOwner.BatchMember(batch.id, 0), claim.owner)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertEquals(1, owner.gate.acquires.get())
    }

    @Test fun unknownSentSelectionIsNotQueriedAgainAfterWakeClockChangeOrRestart() = acquisitionDownloadRoomCase {
        val old = owner()
        val batch = batch(old)
        old.selector.answer = { throw IllegalStateException("Authored source uncertainty") }
        old.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(old.store) { it.requests[batch.members.single().requestId]?.state == AcquisitionJobState.OUTCOME_UNKNOWN }
        idle(old)
        assertEquals(1, old.selector.requests.size)
        repeat(3) { clock.advance(60_000); old.coordinator.onAcquisitionBatchChanged() }
        delay(90)
        assertEquals(1, old.selector.requests.size)
        old.coordinator.close()
        old.store.close()
        val resumed = owner()
        resumed.coordinator.start(DownloadActivation.DURABLE_WORK)
        val idle = idle(resumed)
        assertEquals(FailureCode.RECOVERY_REQUIRED, idle.plan.blockedReason?.code)
        assertNull(idle.plan.nextNetworkEpochMs)
        assertNull(idle.plan.nextLocalEpochMs)
        assertTrue(resumed.selector.requests.isEmpty())
        assertTrue(resumed.repository.state.value.physicalDownloads.isEmpty())
    }

    @Test fun liveForeignClaimHasOneLeaseWakeAndExpiryBlocksInsteadOfRequerying() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val request = batch.members.single().requestId
        val claim = assertIs<AcquisitionClaimResult.Claimed>(owner.repository.claim(ClaimAcquisition(request,
            AcquisitionAttemptToken("authored-foreign-sent"), "authored-old-process", clock.epochMs(), 5_000)))
        val live = owner.coordinator.schedulingPlan()
        assertEquals(claim.lease.expiresEpochMs, live.nextLocalEpochMs)
        assertNull(live.nextNetworkEpochMs)
        clock.advance(5_001)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        val stopped = idle(owner).plan
        assertNull(stopped.nextLocalEpochMs)
        assertNull(stopped.nextNetworkEpochMs)
        assertEquals(FailureCode.RECOVERY_REQUIRED, stopped.blockedReason?.code)
        // No new recovery mutation/seam is implied: expired CLAIMED remains truthfully blocked.
        assertEquals(AcquisitionJobState.CLAIMED, owner.repository.state.value.requests.getValue(request).state)
        assertTrue(owner.selector.requests.isEmpty())
        assertEquals(0, owner.gate.acquires.get())
    }

    @Test fun cancellationOfAnActiveBatchCancelsItsRegisteredSearchAndRetainsUnknownOutcome() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val entered = barrier()
        val sourceReturned = CompletableDeferred<Unit>()
        owner.selector.answer = { request ->
            try { entered.stop(); authoredSingle(request) } finally { sourceReturned.complete(Unit) }
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { entered.entered.await() }
        owner.batches.setIntent(batch.id, 0, DownloadBatchIntent.CANCELLED, retainCompletedAssets = false)
        owner.coordinator.onAcquisitionBatchChanged()
        withTimeout(10_000) { sourceReturned.await() }
        awaitState(owner.store) { it.requests.getValue(batch.members.single().requestId).state == AcquisitionJobState.OUTCOME_UNKNOWN }
        idle(owner)
        assertEquals(1, owner.selector.requests.size)
        assertTrue(owner.repository.state.value.physicalDownloads.isEmpty())
        assertEquals(1, owner.gate.closes.get())
    }

    @Test fun cancellationWhileNativeAcquireIsSuspendedCannotStartASearchOrLoseReturnedAllowance() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val acquiring = barrier()
        owner.gate.beforeAcquire = { acquiring.stop() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { acquiring.entered.await() }
        owner.batches.setIntent(batch.id, 0, DownloadBatchIntent.CANCELLED)
        owner.coordinator.onAcquisitionBatchChanged()
        acquiring.release()
        awaitCondition { owner.gate.closes.get() == 1 }
        idle(owner)
        assertTrue(owner.selector.requests.isEmpty())
        assertEquals(AcquisitionJobState.READY, owner.repository.state.value.requests.getValue(batch.members.single().requestId).state)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
    }

    @Test fun latePositiveReceiptAfterPauseIsRetainedAndResumedByLocalAttachmentNotResearch() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val querying = barrier()
        owner.selector.answer = { request ->
            // Simulates a source that returns an already-obtained positive answer while the
            // caller is being cancelled. The canonical owner must commit it before unwind.
            try { querying.stop() } catch (_: CancellationException) { }
            authoredSingle(request)
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { querying.entered.await() }
        val paused = owner.batches.setIntent(batch.id, 0, DownloadBatchIntent.PAUSED)
        owner.coordinator.onAcquisitionBatchChanged()
        awaitState(owner.store) { it.requests.getValue(batch.members.single().requestId).state == AcquisitionJobState.COMMITTED }
        idle(owner)
        assertTrue(owner.repository.state.value.physicalDownloads.isEmpty())
        offline(owner)
        val active = owner.batches.setIntent(batch.id, paused.intentRevision, DownloadBatchIntent.ACTIVE)
        owner.coordinator.onAcquisitionBatchChanged()
        val state = awaitState(owner.store) { it.batches.getValue(batch.id).members.single().claimId != null }
        idle(owner)
        assertEquals(active.intentRevision, state.batches.getValue(batch.id).intentRevision)
        assertEquals(1, owner.selector.requests.size)
        assertTrue(owner.gateway.transfers.isEmpty())
    }

    @Test fun connectivityIsRecheckedAfterNativeAcquireBeforeTheFirstSourceCall() = acquisitionDownloadRoomCase {
        val owner = owner()
        batch(owner)
        val acquiring = barrier()
        owner.gate.beforeAcquire = { acquiring.stop() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { acquiring.entered.await() }
        owner.network.value = DownloadNetworkState.DISCONNECTED
        acquiring.release()
        awaitCondition { owner.gate.closes.get() == 1 }
        assertTrue(owner.selector.requests.isEmpty())
        owner.network.value = DownloadNetworkState.CONNECTED
        clock.advance(5_001)
        owner.coordinator.onAcquisitionBatchChanged()
        awaitState(owner.store) { it.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE }
        idle(owner)
        assertEquals(1, owner.selector.requests.size)
    }

    @Test fun coverageLossDuringHeldNativeAcquireIsRecheckedBeforeAnySourceOrPhysicalAdmission() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val acquiring = barrier()
        owner.gate.beforeAcquire = { acquiring.stop() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { acquiring.entered.await() }
        val checksBeforeReturn = owner.coverageChecks.get()
        val lateDenials = AtomicInteger()
        owner.admission = {
            lateDenials.incrementAndGet()
            throw VeyraException(AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_coverage_lost"))
        }
        acquiring.release()
        awaitCondition { owner.gate.closes.get() == 1 }
        idle(owner)
        assertTrue(lateDenials.get() > 0)
        assertTrue(owner.coverageChecks.get() > checksBeforeReturn, "An initial admission check is not a recheck after acquire")
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        val state = owner.store.acquisitionView().acquisition
        assertEquals(AcquisitionJobState.READY, state.requests.getValue(batch.members.single().requestId).state)
        assertTrue(state.physicalDownloads.isEmpty())
    }

    @Test fun aHeldPhysicalTransferOccupiesTheSameCapacityAndPreventsAnyPendingMemberSearch() = acquisitionDownloadRoomCase {
        val owner = owner()
        parallelism(owner, 1)
        val transferring = barrier()
        val originalTransfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, progress ->
            transferring.stop()
            originalTransfer(prepared, path, progress)
        }
        val physicalId = enqueue(owner, acceptNeutral(owner))
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { transferring.entered.await() }
        val pending = batch(owner, count = 2)
        owner.coordinator.onAcquisitionBatchChanged()
        delay(90)
        assertEquals(DownloadStatus.RUNNING, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(physicalId).status)
        assertTrue(owner.selector.requests.isEmpty(), "Pending source selection cannot overtake a capacity-one transfer owner")
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.gate.acquires.get())
        transferring.release()
        awaitState(owner.store) { state -> state.batches.getValue(pending.id).members.all { it.claimId != null } }
        idle(owner)
        assertEquals(2, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.size, "Both selected members explicitly share the already-materialized same C")
        assertEquals(1, owner.store.acquisitionView().acquisition.physicalDownloads.size)
    }

    @Test fun noMoreThanOneMemberSelectionRunsAtATime() = acquisitionDownloadRoomCase {
        val owner = owner()
        parallelism(owner, 1)
        batch(owner, count = 2)
        val querying = barrier()
        val selecting = AtomicInteger()
        val maximum = AtomicInteger()
        owner.selector.answer = { request ->
            val count = selecting.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            try { querying.stop(); authoredSingle(request) } finally { selecting.decrementAndGet() }
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { querying.entered.await() }
        delay(90)
        assertEquals(1, owner.selector.requests.size)
        assertTrue(owner.gateway.transfers.isEmpty())
        querying.release()
        awaitState(owner.store) { it.batches.values.single().members.all { member -> member.claimId != null } }
        idle(owner)
        assertEquals(1, maximum.get())
        assertEquals(2, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.size)
    }
}
