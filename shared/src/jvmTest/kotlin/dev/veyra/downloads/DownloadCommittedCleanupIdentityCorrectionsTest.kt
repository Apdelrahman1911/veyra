package dev.veyra.downloads

import dev.veyra.data.*
import dev.veyra.model.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/**
 * Coherent runtime fake repository oracle for the defensive committed-identity rollback. The
 * canonical Room claim is separately constrained by the real-Room invariant test; this fake
 * deliberately returns a different but internally coherent committed fence/claim set so a
 * rollback that reused the pre-read snapshot would be detected. It is not Room/canonical proof.
 */
class DownloadCommittedCleanupIdentityCorrectionsTest {
    @Test fun returnedCommittedCleanupIdentityDrivesRollbackWhenItDiffersFromPreRead() = physicalRuntimeCase {
        val asset = seedComplete()
        val base = assertNotNull(repository.physicalDownload(PhysicalRuntimeFixture.JOB))
        val originalClaim = base.claims.single()
        val cancelledClaim = originalClaim.copy(intent = DownloadBatchIntent.CANCELLED,
            retainCompletedAsset = false, generation = originalClaim.generation + 1)
        val cleanupJob = base.job.copy(status = DownloadStatus.CANCELLED, cleanupPending = true,
            removeWhenClean = true, userPaused = true, failure = null, nextRetryEpochMs = null)
        repository.state.value = repository.state.value.copy(
            physicalDownloads = mapOf(PhysicalRuntimeFixture.JOB to cleanupJob),
            claims = mapOf(cancelledClaim.id to cancelledClaim))
        val expected = assertNotNull(repository.physicalDownload(PhysicalRuntimeFixture.JOB))
        val normal = AtomicReference<ClaimedPhysicalDownload>()
        val changed = AtomicReference<ClaimedPhysicalDownload>()
        repository.maintenanceClaimOutcome = { _, claimed ->
            normal.set(claimed)
            val changedClaims = claimed.physical.claims.map { it.copy(generation = it.generation + 1) }
            val changedOwner = "authored-returned-cleanup-owner"
            val changedJob = claimed.physical.job.copy(
                ownershipGeneration = claimed.fence.ownershipGeneration + 1,
                leaseOwner = changedOwner)
            val changedPhysical = claimed.physical.copy(job = changedJob, claims = changedClaims)
            val changedFence = claimed.fence.copy(ownershipGeneration = changedJob.ownershipGeneration,
                leaseOwner = changedOwner)
            ClaimedPhysicalDownload(changedFence, changedPhysical).also { changed.set(it) }
        }

        assertNull(runtime.cleanupStoppedStandalone(expected),
            "The post-read identity mismatch is a retryable cleanup claim race, not a host success")

        val normalClaim = assertNotNull(normal.get())
        val returned = assertNotNull(changed.get())
        assertNotEquals(normalClaim.fence, returned.fence)
        assertNotEquals(normalClaim.physical.claims, returned.physical.claims)
        val finish = repository.maintenanceFinishes.single { it.kind == PhysicalDownloadMaintenanceKind.CLEANUP }
        assertEquals(returned.fence, finish.fence,
            "Rollback must settle the exact returned committed fence, not the pre-read proposed fence")
        assertEquals(returned.physical.claims, finish.expectedClaims,
            "Rollback must settle the exact returned committed claim set, not the pre-read claim set")
        val after = assertNotNull(repository.physicalDownload(PhysicalRuntimeFixture.JOB))
        assertNull(after.job.leaseOwner)
        assertNull(after.job.maintenanceKind)
        assertTrue(after.job.cleanupPending, "The retryable cleanup intent remains reserved")
        assertEquals(asset, after.asset, "The retained asset and bytes remain owned")
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
    }
}
