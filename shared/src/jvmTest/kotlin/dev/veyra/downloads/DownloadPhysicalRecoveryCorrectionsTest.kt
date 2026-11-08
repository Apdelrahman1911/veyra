package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadDurableSettlement
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * Causal physical ownership tests for the PR17 review corrections. Real owned files/OS leases,
 * fake canonical commands/native answers, authored non-audio bytes; no device/native proof.
 */
class DownloadPhysicalRecoveryCorrectionsTest {
    @Test fun cancellationInsideGateAdmissionClosesTheAcquiredAllowance() = physicalRuntimeCase {
        seedComplete()
        val baseAcquires = gate.acquires.get()
        val baseCloses = gate.closes.get()
        val holder = arrayOfNulls<Job>(1)
        var requested = 0
        gate.beforeAcquire = { requested++; holder[0]?.cancel(CancellationException("authored cancellation at allowance boundary")) }
        supervisorScope {
            val pending = async(start = CoroutineStart.LAZY) {
                runCatching { runtime.validateStandaloneAssetUse(PhysicalRuntimeFixture.JOB, contextR2.binding.id) }
            }
            holder[0] = pending
            pending.start()
            assertFailsWith<CancellationException> { pending.await() }
        }
        assertEquals(baseAcquires + 1, gate.acquires.get())
        assertEquals(baseCloses + 1, gate.closes.get(), "The acquired allowance is closed by the maintenance finally")
        assertEquals(1, requested, "Exactly the fake host-side-effect request was made and released")
        assertEquals(0, leases.open.get())
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        assertNull(runtime.drainCheckpointFailure())
    }

    @Test fun completedCancellationSupersedesLateStaleFinalizerButUnchangedActiveStaleStaysFatal() = physicalRuntimeCase {
        val original = currentJob()
        val cancelled = claimR2.copy(intent = DownloadBatchIntent.CANCELLED,
            generation = claimR2.generation + 1, updatedEpochMs = 11_000)
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to original.copy(
                status = DownloadStatus.CANCELLED, cleanupPending = false, leaseOwner = null,
                leaseExpiresEpochMs = null, maintenanceKind = null, ownershipGeneration = 1)),
            claims = mapOf(cancelled.id to cancelled))
        val late = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1),
            1, null, null, null)
        val lateCleanup = DownloadPhysicalCleanup(runtime, late, emptyList(), acceptedDurableOwner = true,
            expectedClaims = listOf(claimR2)) { AcquisitionDownloadDurableSettlement.Stale }
        host.cleanupRegistry.register(lateCleanup)
        runtime.epochs.retain(late, lateCleanup.token, lateCleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(late)
        runtime.attemptCleanup(lateCleanup, report = false)
        assertNull(runtime.drainCheckpointFailure(),
            "The foreign cancellation durably completed this exact generation; its late stale write is not an unknown checkpoint")
        assertIs<DownloadCleanupObservation.Completed>(runtime.cleanupObservation(PhysicalRuntimeFixture.JOB))

        // The cancellation maintenance may advance the ownership generation before its cleaning
        // owner completes; the older execution epoch is still exactly superseded.
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to original.copy(
                status = DownloadStatus.CANCELLED, cleanupPending = false, leaseOwner = null,
                leaseExpiresEpochMs = null, maintenanceKind = null, ownershipGeneration = 2)),
            claims = mapOf(cancelled.id to cancelled.copy(generation = cancelled.generation + 1)))
        val supersededLate = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1),
            1, null, null, null)
        val supersededCleanup = DownloadPhysicalCleanup(runtime, supersededLate, emptyList(),
            acceptedDurableOwner = true, expectedClaims = listOf(claimR2)) {
            AcquisitionDownloadDurableSettlement.Stale
        }
        host.cleanupRegistry.register(supersededCleanup)
        runtime.epochs.retain(supersededLate, supersededCleanup.token, supersededCleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(supersededLate)
        runtime.attemptCleanup(supersededCleanup, report = false)
        assertNull(runtime.drainCheckpointFailure(),
            "A newer completed cancellation generation still proves this older finalizer was superseded")

        // The SAME stale settlement remains fatal while the exact generation is still active.
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to original.copy(
                cleanupPending = true, ownershipGeneration = 1)),
            claims = mapOf(claimR2.id to claimR2))
        val active = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1),
            1, null, null, null)
        val activeCleanup = DownloadPhysicalCleanup(runtime, active, emptyList(), acceptedDurableOwner = true,
            expectedClaims = listOf(claimR2)) { AcquisitionDownloadDurableSettlement.Stale }
        host.cleanupRegistry.register(activeCleanup)
        runtime.epochs.retain(active, activeCleanup.token, activeCleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(active)
        runtime.attemptCleanup(activeCleanup, report = false)
        assertEquals(FailureCode.INVALID_STATE, assertNotNull(runtime.drainCheckpointFailure()).code,
            "An unchanged active generation must not launder a stale finalizer")
    }

    @Test fun revokedOwnerStaleFinalizerIsBenignButActiveOwnerStaleRemainsFatal() = physicalRuntimeCase {
        val job = currentJob().copy(ownershipGeneration = 1)
        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to job))
        val revoked = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1), 1, null, null, null)
        revoked.revoked = true
        val cleanup = DownloadPhysicalCleanup(runtime, revoked, emptyList(), acceptedDurableOwner = true,
            maintenanceKind = PhysicalDownloadMaintenanceKind.CLEANUP) {
            AcquisitionDownloadDurableSettlement.Stale
        }
        host.cleanupRegistry.register(cleanup)
        runtime.epochs.retain(revoked, cleanup.token, cleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(revoked)
        runtime.attemptCleanup(cleanup, report = false)
        assertNull(runtime.drainCheckpointFailure(), "A revoked owner's stale finalizer is not an unknown checkpoint")

        repository.state.value = repository.state.value.copy(physicalDownloads = mapOf(
            PhysicalRuntimeFixture.JOB to currentJob().copy(cleanupPending = true)))
        val active = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1), 1, null, null, null)
        val activeCleanup = DownloadPhysicalCleanup(runtime, active, emptyList(), acceptedDurableOwner = true,
            maintenanceKind = PhysicalDownloadMaintenanceKind.CLEANUP) {
            AcquisitionDownloadDurableSettlement.Stale
        }
        host.cleanupRegistry.register(activeCleanup)
        runtime.epochs.retain(active, activeCleanup.token, activeCleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(active)
        runtime.attemptCleanup(activeCleanup, report = false)
        assertEquals(FailureCode.INVALID_STATE, assertNotNull(runtime.drainCheckpointFailure()).code)
        assertIs<DownloadCleanupObservation.Failed>(runtime.cleanupObservation(PhysicalRuntimeFixture.JOB))
        assertEquals(FailureCode.INVALID_STATE, assertNotNull(runtime.awaitRemovalOutcome(PhysicalRuntimeFixture.JOB)).code,
            "A consumed stale owner returns a bounded removal failure instead of waiting for a missing owner")
    }
}
