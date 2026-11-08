package dev.veyra.downloads

import dev.veyra.acquisition.AcquisitionDownloadDurableSettlement
import dev.veyra.model.*
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * Deterministic removal-observation cases. The fixture has real owned files/OS leases and fake
 * canonical commands/native answers; no device, native-audio or live-source proof is implied.
 */
class DownloadRemovalObservationCorrectionsTest {
    @Test fun interruptedRemovalDoesNotTreatConsumedCancelledOwnerAsCompletion() = physicalRuntimeCase(removalOwnerGraceMs = 10_000) {
        val pending = currentJob().copy(status = DownloadStatus.CANCELLED, cleanupPending = true,
            ownershipGeneration = 1, leaseOwner = null, maintenanceKind = null)
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to pending))
        val epoch = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1), 1, null, null, null)
        val cleanup = DownloadPhysicalCleanup(runtime, epoch, emptyList(), acceptedDurableOwner = true) {
            AcquisitionDownloadDurableSettlement.Confirmed(1)
        }
        host.cleanupRegistry.register(cleanup)
        runtime.epochs.retain(epoch, cleanup.token, cleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(epoch)
        coroutineScope {
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.awaitRemovalOutcome(PhysicalRuntimeFixture.JOB, failedIsTerminal = false)
            }
            assertFalse(observed.isCompleted, "The exact pending cleanup keeps the caller waiting")
            // Consuming the cancelled child owner leaves the accepted cleanup intent pending. It is
            // not evidence that the foreground retry can never delete the retained bytes.
            runtime.attemptCleanup(cleanup, report = false)
            assertTrue(host.cleanupRegistry.owners.value.values.none { it === cleanup })
            assertTrue(removalJob().job.cleanupPending)
            assertNull(withTimeoutOrNull(250) { observed.await() },
                "Owner consumption alone must not be reported as a terminal removal failure")
            repository.state.value = repository.state.value.copy(
                physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to pending.copy(cleanupPending = false)))
            assertNull(observed.await(), "Only the durable cleanup completion settles the interrupted caller")
        }
    }

    @Test fun normalRemovalDoesNotTreatConsumedOwnerAsTerminalWhileCleanupRemainsPending() = physicalRuntimeCase(removalOwnerGraceMs = 10_000) {
        val pending = currentJob().copy(status = DownloadStatus.CANCELLED, cleanupPending = true,
            ownershipGeneration = 1, leaseOwner = null, maintenanceKind = null)
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to pending))
        val epoch = runtime.epochs.begin(PhysicalRuntimeFixture.JOB, producer(contextR1), 1, null, null, null)
        val cleanup = DownloadPhysicalCleanup(runtime, epoch, emptyList(), acceptedDurableOwner = true) {
            AcquisitionDownloadDurableSettlement.Confirmed(1)
        }
        host.cleanupRegistry.register(cleanup)
        runtime.epochs.retain(epoch, cleanup.token, cleanup, completesAcquisition = true)
        runtime.epochs.finishAdmission(epoch)
        coroutineScope {
            val observed = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.awaitRemovalOutcome(PhysicalRuntimeFixture.JOB)
            }
            assertFalse(observed.isCompleted)
            runtime.attemptCleanup(cleanup, report = false)
            assertTrue(host.cleanupRegistry.owners.value.values.none { it === cleanup })
            assertTrue(removalJob().job.cleanupPending)
            assertNull(withTimeoutOrNull(250) { observed.await() },
                "A consumed owner with durable cleanup pending is not a terminal removal failure")
            repository.state.value = repository.state.value.copy(
                physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to pending.copy(cleanupPending = false)))
            assertNull(observed.await(), "The exact durable cleanup completion settles the normal caller")
        }
    }

    @Test fun interruptedRemovalWithoutAnOwnerStillFailsBoundedAndPreservesIntent() = physicalRuntimeCase(removalOwnerGraceMs = 40) {
        val pending = currentJob().copy(status = DownloadStatus.CANCELLED, cleanupPending = true,
            ownershipGeneration = 1, leaseOwner = null, maintenanceKind = null)
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to pending))
        val started = System.nanoTime()
        val failure = assertNotNull(runtime.awaitRemovalOutcome(PhysicalRuntimeFixture.JOB, failedIsTerminal = false),
            "A missing owner must still fail closed after the bounded handoff")
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, failure.code)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000,
            "The interrupted path remains bounded instead of waiting indefinitely")
        assertTrue(removalJob().job.cleanupPending, "The bounded failure preserves the exact removal intent")
        assertEquals(pending.ownershipGeneration, removalJob().job.ownershipGeneration)
    }

    private suspend fun PhysicalRuntimeFixture.removalJob() =
        assertNotNull(repository.physicalDownload(PhysicalRuntimeFixture.JOB))
}
