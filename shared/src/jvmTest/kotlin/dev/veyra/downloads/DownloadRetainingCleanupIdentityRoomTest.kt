package dev.veyra.downloads

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.EngineCapabilities
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/**
 * Real Room/SQLite/files/OS-lock retaining-to-nonretaining cleanup discriminator. A controlled
 * runtime host over the same canonical repository invokes the production
 * cleanupStoppedStandalone(expectedOwner) path without a scheduler race. The download itself is
 * an in-progress staged typed transfer; bytes are authored NON-AUDIO. No native/live/device proof.
 */
class DownloadRetainingCleanupIdentityRoomTest {
    @Test fun retainingCleanupPreReadDiffersFromTheCanonicalCommittedClaimSet() = acquisitionDownloadRoomCase {
        val owner = owner()
        val accepted = acceptNeutral(owner)
        val id = enqueue(owner, accepted)
        val transferStarted = CompletableDeferred<Unit>()
        owner.gateway.transfer = { prepared, path, _ ->
            Files.write(Path.of(path), authoredBytes)
            transferStarted.complete(Unit)
            awaitCancellation()
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { transferStarted.await() }
        // Stop the scheduler/transfer without deleting the staged generation. The canonical
        // command below then creates the legitimate retaining cleanupPending state.
        runCatching { owner.coordinator.drainForBackground() }
        val afterDrain = assertNotNull(owner.repository.physicalDownload(id))
        val staging = afterDrain.job.stagingPath ?: afterDrain.attempts.lastOrNull()?.stagingPath
        val stagingPath = assertNotNull(staging, "The interrupted transfer must retain its staged path")
        assertTrue(Files.exists(Path.of(stagingPath)))
        val claim = afterDrain.claims.single { it.owner == DownloadClaimOwner.Standalone(id) }
        val retaining = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
            owner.repository.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(
                id, afterDrain.job.producer, afterDrain.job.ownershipGeneration, claim.id, claim.generation,
                claim.ownerIntentRevision, DownloadBatchIntent.CANCELLED, retainCompletedAsset = true,
                nowEpochMs = clock.epochMs())))
        assertTrue(retaining.value.job.cleanupPending)
        assertTrue(retaining.value.claims.single().retainCompletedAsset)
        assertEquals(DownloadStatus.CANCELLED, retaining.value.job.status)
        assertTrue(Files.exists(Path.of(stagingPath)))

        val runtime = controlledRuntime(owner)
        val expected = assertNotNull(owner.repository.physicalDownload(id))
        val preRead = AtomicReference<List<DownloadClaim>>()
        val changed = AtomicReference<List<DownloadClaim>>()
        val committedClaims = AtomicReference<List<DownloadClaim>>()
        val committedFence = AtomicReference<PhysicalDownloadFence>()
        val finishResult = AtomicReference<PhysicalDownloadCommit<PhysicalDownloadJoin>>()
        val first = AtomicBoolean(true)
        owner.repository.beforeMaintenanceClaim = { command ->
            if (command.kind == PhysicalDownloadMaintenanceKind.CLEANUP && first.compareAndSet(true, false)) {
                val before = assertNotNull(owner.repository.physicalDownload(id))
                preRead.set(before.claims)
                val beforeClaim = before.claims.single()
                assertTrue(before.job.cleanupPending)
                assertTrue(beforeClaim.retainCompletedAsset)
                val updated = assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(
                    owner.repository.updateStandaloneDownloadIntent(UpdateStandaloneDownloadIntent(
                        id, before.job.producer, before.job.ownershipGeneration, beforeClaim.id,
                        beforeClaim.generation, beforeClaim.ownerIntentRevision, DownloadBatchIntent.CANCELLED,
                        retainCompletedAsset = false, nowEpochMs = clock.epochMs())))
                assertFalse(updated.value.claims.single().retainCompletedAsset)
                assertNotEquals(beforeClaim.generation, updated.value.claims.single().generation)
                changed.set(updated.value.claims)
            }
        }
        owner.repository.transformMaintenanceClaim = { command, result ->
            if (command.kind == PhysicalDownloadMaintenanceKind.CLEANUP &&
                result is PhysicalDownloadCommit.Committed) {
                committedFence.set(result.value.fence)
                committedClaims.set(result.value.physical.claims)
            }
            result
        }
        owner.repository.afterMaintenanceFinishHook = { command, result ->
            if (command.kind == PhysicalDownloadMaintenanceKind.CLEANUP && !command.cleanupCompleted)
                finishResult.set(result)
        }

        val firstEpoch = runtime.cleanupStoppedStandalone(expected)
        assertNull(firstEpoch, "The post-read claim mismatch is a retryable rollback, not cleanup success")
        val before = assertNotNull(preRead.get())
        val changedClaims = assertNotNull(changed.get())
        val claimed = assertNotNull(committedClaims.get())
        val fence = assertNotNull(committedFence.get())
        assertNotEquals(before.toSet(), claimed.toSet(), "The retained pre-read and committed claim sets must differ")
        assertEquals(changedClaims, claimed)
        val finish = owner.repository.maintenanceFinishCalls.single {
            it.kind == PhysicalDownloadMaintenanceKind.CLEANUP && !it.cleanupCompleted
        }
        assertEquals(fence, finish.fence)
        assertEquals(claimed, finish.expectedClaims,
            "Rollback must settle the exact committed retaining-to-nonretaining claim set")
        assertIs<PhysicalDownloadCommit.Committed<PhysicalDownloadJoin>>(assertNotNull(finishResult.get()))
        val held = assertNotNull(owner.repository.physicalDownload(id))
        assertNull(held.job.leaseOwner)
        assertTrue(held.job.cleanupPending)
        assertEquals(claimed, held.claims)
        assertTrue(Files.exists(Path.of(stagingPath)))

        val later = assertNotNull(owner.repository.physicalDownload(id))
        assertNotNull(runtime.cleanupStoppedStandalone(later),
            "The subsequent exact nonretaining cleanup must actually delete the staged generation")
        val removed = assertNotNull(owner.repository.physicalDownload(id))
        assertFalse(removed.job.cleanupPending)
        assertNull(removed.job.stagingPath)
        assertFalse(Files.exists(Path.of(stagingPath)))
        assertTrue(runtime.epochs.allLocalOwnersConsumed())
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    private fun DownloadAcquisitionRoomFixture.controlledRuntime(owner: DownloadAcquisitionRoomOwner): DownloadPhysicalRuntime {
        val state = MutableStateFlow(DownloadPhysicalHostState(true, false, false, true, 1, 0, null, false,
            2, DownloadNetworkState.CONNECTED))
        val registry = DownloadCleanupRegistry()
        val host = DownloadPhysicalHost(Mutex(), Mutex(), mutableMapOf(), registry, { state.value },
            { owner.store.acquisitionView() }, {}, {}, { _ -> }, {}, {}, { _ -> })
        return DownloadPhysicalRuntime(owner.store, owner.dependencies, owner.files, owner.legacy,
            EngineCapabilities(), owner.gate, clock, leaseDurationMs = 60_000, heartbeatMs = 10_000, host = host)
    }
}
