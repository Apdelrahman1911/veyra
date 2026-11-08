package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Actual registered Coordinator jobs/Room/files; OS allowances and source responses are fake. */
class DownloadPendingLifetimeRoomTest {
    @Test fun backgroundDrainAndCloseBothAwaitThePendingMembersRealNativeClose() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        val querying = barrier()
        val closingNative = barrier()
        owner.selector.answer = { request -> querying.stop(); authoredSingle(request) }
        owner.gate.beforeClose = { closingNative.stop() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { querying.entered.await() }
        coroutineScope {
            val drain = async { owner.coordinator.drainForBackground() }
            withTimeout(10_000) { closingNative.entered.await() }
            assertIs<DownloadIdleDecision.OwnedWork>(owner.coordinator.idleDecision())
            val close = async { owner.coordinator.close() }
            delay(90)
            assertFalse(drain.isCompleted)
            assertFalse(close.isCompleted)
            assertTrue(owner.coordinator.hasPendingCleanupOwners)
            assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
            // close has latched closed=true; idleDecision is no longer a legal observer.
            assertTrue(leases.open.get() > 0, "Pending SELECT holds the same native slot until its allowance really closes")
            closingNative.release()
            assertNull(withTimeout(10_000) { drain.await() }.checkpointFailure)
            withTimeout(10_000) { close.await() }
        }
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertEquals(0, leases.open.get())
        assertEquals(1, owner.gate.closes.get())
        assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN,
            owner.store.acquisitionView().acquisition.requests.getValue(batch.members.single().requestId).state)
    }

    @Test fun failedPendingNativeCloseRemainsStronglyOwnedAndOnlyExplicitCloseMayRetryIt() = acquisitionDownloadRoomCase {
        val owner = owner()
        batch(owner)
        owner.selector.answer = { throw IllegalStateException("Authored uncertain source result") }
        owner.gate.beforeClose = { throw IllegalStateException("Authored native close was not acknowledged") }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { owner.coordinator.hasPendingCleanupOwners && owner.gate.closes.get() == 1 &&
            owner.coordinator.lastFailure.value?.detail == "download_owner_drain_failed_restart_required" }
        delay(90)
        assertEquals(1, owner.gate.closes.get(), "No automatic retry after a completed pending job's failed close")
        assertTrue(leases.open.get() > 0)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { owner.coordinator.idleDecision() }.failure.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { owner.coordinator.drainForBackground() }.failure.code)
        assertEquals(1, owner.gate.closes.get(), "Independent background drain observes, not retries, retained cleanup")
        assertFailsWith<VeyraException> { owner.coordinator.close() }
        assertEquals(2, owner.gate.closes.get())
        assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertTrue(owner.coordinator.hasPendingCleanupOwners)
        owner.gate.beforeClose = { }
        assertFailsWith<VeyraException> { owner.coordinator.close() } // First failure stays diagnostic.
        assertEquals(3, owner.gate.closes.get())
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertEquals(0, leases.open.get())
        assertEquals(1, owner.selector.requests.size)
    }

    @Test fun inlinePendingAllowanceExpirationBeforeAcquireReturnsNeverStartsAQueryOrDropsThatLease() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = batch(owner)
        owner.gate.beforeAcquire = { expiration -> expiration() }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitCondition { owner.gate.closes.get() == 1 }
        idle(owner)
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        assertEquals(AcquisitionJobState.READY,
            owner.store.acquisitionView().acquisition.requests.getValue(batch.members.single().requestId).state)
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        assertNull(owner.coordinator.drainForBackground().checkpointFailure)
        assertEquals(0, leases.open.get())
    }

    @Test fun consumedPendingAllowanceCallbackCannotCancelAnotherBatchesRegisteredQuery() = acquisitionDownloadRoomCase {
        val owner = owner()
        val originalBatch = batch(owner)
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { state -> state.batches.getValue(originalBatch.id).members.single().claimId != null &&
            state.physicalDownloads.values.singleOrNull()?.status == DownloadStatus.COMPLETE }
        idle(owner)
        val originalExpiration: suspend () -> Unit = owner.gate.expirations[0]
        val oldCloses = owner.gate.closes.get()
        val querying = barrier()
        owner.selector.answer = { request -> querying.stop(); authoredSingle(request) }
        val successor = batch(owner)
        owner.coordinator.onAcquisitionBatchChanged()
        withTimeout(10_000) { querying.entered.await() }
        originalExpiration.invoke()
        delay(90)
        assertEquals(oldCloses, owner.gate.closes.get())
        assertEquals(AcquisitionJobState.CLAIMED,
            owner.store.acquisitionView().acquisition.requests.getValue(successor.members.single().requestId).state)
        assertIs<DownloadIdleDecision.OwnedWork>(owner.coordinator.idleDecision())
        querying.release()
        awaitState(owner.store) { it.batches.getValue(successor.id).members.single().claimId != null }
        idle(owner)
        assertEquals(2, owner.selector.requests.size)
        assertEquals(1, owner.gateway.transfers.size)
    }

    @Test fun offlineCancelsTheRegisteredSelectButStillAllowsANewLocalCommittedAttachment() = acquisitionDownloadRoomCase {
        val owner = owner()
        val original = batch(owner)
        val querying = barrier()
        owner.selector.answer = { request -> querying.stop(); authoredSingle(request) }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { querying.entered.await() }
        offline(owner)
        awaitState(owner.store) { it.requests.getValue(original.members.single().requestId).state == AcquisitionJobState.OUTCOME_UNKNOWN }
        idle(owner)
        assertEquals(1, owner.gate.closes.get())
        val local = batch(owner)
        acceptMember(owner, local, attach = false)
        owner.coordinator.onAcquisitionBatchChanged()
        awaitState(owner.store) { it.batches.getValue(local.id).members.single().claimId != null }
        idle(owner)
        assertEquals(1, owner.selector.requests.size, "Already committed local attachment must not research the captured title")
        assertTrue(owner.gateway.transfers.isEmpty())
        assertEquals(2, owner.gate.acquires.get())
        assertEquals(2, owner.gate.closes.get())
        assertEquals(0, leases.open.get())
    }
}
