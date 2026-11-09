package dev.veyra.downloads

import dev.veyra.data.*
import dev.veyra.model.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Real Room/files/OS-lock shared-job semantics. Removing one standalone demand must settle that
 * exact claim and its required cleanup without deleting bytes still demanded by surviving batch
 * claims. Authored non-audio bytes; no native/live/device proof.
 */
class DownloadSharedSiblingRemovalRoomTest {
    @Test fun removingOneStandaloneDemandPreservesTheSiblingAssetAndReturnsWithoutCleanup() = acquisitionDownloadRoomCase {
        val owner = owner()
        val batch = enrollOriginUseBatch(owner, count = 2)
        val initial = owner.store.acquisitionView().acquisition
        val job = initial.physicalDownloads.values.single()
        val origin = batch.members.single { it.requiredBindingId == job.producer.bindingId }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitOriginUsePublication(owner, job.id)
        val before = assertNotNull(owner.repository.physicalDownload(job.id))
        val asset = assertNotNull(before.asset)
        assertEquals(2, before.claims.count { it.owner is DownloadClaimOwner.BatchMember })

        val standalone = assertIs<PhysicalDownloadCommit.Committed<StandalonePhysicalDownloadAdmission>>(
            owner.repository.admitStandalonePhysicalDownload(AdmitStandalonePhysicalDownload(
                origin.requestId, assertNotNull(origin.requiredBindingId), newEntityId("download-shared-standalone"),
                DownloadClaimId(newEntityId("claim-shared-standalone")), clock.epochMs()))).value.claim
        assertEquals(DownloadClaimOwner.Standalone(job.id), standalone.owner)

        // The public action removes the standalone claim; the surviving batch claims retain the
        // exact shared asset, so there is no cleanupPending/delete and normal return is truthful.
        owner.coordinator.remove(job.id)
        val after = assertNotNull(owner.repository.physicalDownload(job.id))
        assertFalse(after.job.cleanupPending, "No cleanup is required while a surviving claim retains the asset")
        assertEquals(before.asset, after.asset)
        assertTrue(Files.exists(Path.of(asset.uri)))
        assertEquals(DownloadBatchIntent.CANCELLED, after.claims.single { it.id == standalone.id }.intent)
        assertFalse(after.claims.single { it.id == standalone.id }.retainCompletedAsset)
        val siblings = after.claims.filter { it.owner is DownloadClaimOwner.BatchMember }
        assertEquals(2, siblings.size)
        assertTrue(siblings.all { it.intent == DownloadBatchIntent.ACTIVE && it.retainCompletedAsset })
        assertFalse(owner.coordinator.hasPendingCleanupOwners)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }
}
