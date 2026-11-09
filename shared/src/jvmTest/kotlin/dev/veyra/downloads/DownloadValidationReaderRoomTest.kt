package dev.veyra.downloads

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import kotlinx.coroutines.*
import okio.ByteString.Companion.toByteString
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** REAL Coordinator/Room/path leases, FAKE native release observations and authored NON-AUDIO. */
class DownloadValidationReaderRoomTest {
    @Test fun failedTransferValidatorReleasePinsAllowanceFileSlotAndCanonicalAttemptUntilActualAcknowledgment() = acquisitionDownloadRoomCase {
        val owner = owner()
        val id = enqueue(owner, acceptNeutral(owner))
        val released = AtomicBoolean()
        val observed = CompletableDeferred<LocalMediaValidationInput>()
        val finishes = AtomicInteger()
        owner.repository.beforeAttemptFinish = { finishes.incrementAndGet() }
        owner.validation = { input ->
            observed.complete(input)
            throw LocalMediaValidationReaderUnacknowledged(input, LocalMediaValidationReaderRelease { released.get() })
        }
        try {
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            val input = withTimeout(10_000) { observed.await() }
            assertReaderBlocked(owner, input.uri)
            val held = owner.store.acquisitionView().acquisition
            assertEquals(DownloadStatus.VERIFYING, held.physicalDownloads.getValue(id).status)
            assertNotNull(held.physicalDownloads.getValue(id).activeAttemptId)
            assertNull(held.physicalAttempts.values.single().finishedEpochMs)
            assertEquals(0, finishes.get())
            assertTrue(held.physicalFinalizations.isEmpty())
            assertTrue(held.materializations.isEmpty())
            val unexpectedSlot = files.tryTransferSlot(1)
            try { assertNull(unexpectedSlot, "Retained validator keeps the original physical transfer capacity") }
            finally { unexpectedSlot?.close() }
            released.set(true) // Observes the ORIGINAL decoder, not permission to retry destroy.
            assertFailsWith<VeyraException> { owner.coordinator.close() }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(1, owner.gate.closes.get())
            assertEquals(1, finishes.get())
            assertEquals(0, leases.open.get())
            assertNotNull(owner.store.acquisitionView().acquisition.physicalAttempts.values.single().finishedEpochMs)
            assertEquals(1, owner.validations.size)
        } finally { released.set(true) }
    }

    @Test fun cancellationDoesNotLaunderAValidatorWhoseNativeCloseStillHasNoReceipt() = acquisitionDownloadRoomCase {
        val owner = owner()
        enqueue(owner, acceptNeutral(owner))
        val released = AtomicBoolean()
        val entered = barrier()
        val observed = CompletableDeferred<LocalMediaValidationInput>()
        owner.validation = { input ->
            observed.complete(input)
            try { entered.stop(); AssetValidation.Valid(733) }
            finally {
                if (!released.get()) throw LocalMediaValidationReaderUnacknowledged(input,
                    LocalMediaValidationReaderRelease { released.get() })
            }
        }
        try {
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            withTimeout(10_000) { entered.entered.await() }
            assertFailsWith<VeyraException> { owner.coordinator.drainForBackground() }
            assertReaderBlocked(owner, withTimeout(10_000) { observed.await() }.uri)
            assertEquals(1, owner.validations.size)
            assertEquals(0, owner.gate.closes.get())
            released.set(true)
            assertFailsWith<VeyraException> { owner.coordinator.close() }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(0, leases.open.get())
        } finally { released.set(true); entered.release() }
    }

    @Test fun historicalAssetValidatorInputMismatchStillRetainsItsActualOriginalFileLease() = acquisitionDownloadRoomCase {
        val old = owner()
        val (id, _) = complete(old)
        val asset = old.store.acquisitionView().acquisition.materializations.values.single()
        old.coordinator.close()
        old.store.close()
        val owner = owner()
        val released = AtomicBoolean()
        owner.validation = { input ->
            throw LocalMediaValidationReaderUnacknowledged(input.copy(uri = "/authored/mismatched-reported-input"),
                LocalMediaValidationReaderRelease { released.get() })
        }
        try {
            owner.coordinator.start(DownloadActivation.USER_SESSION)
            assertReaderBlocked(owner, asset.uri)
            val held = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(id)
            assertEquals(PhysicalDownloadMaintenanceKind.VALIDATE, held.maintenanceKind)
            assertNotNull(held.leaseOwner)
            assertEquals(asset, owner.store.acquisitionView().acquisition.materializations[asset.id])
            assertTrue(Files.exists(Path.of(asset.uri)))
            assertTrue(owner.gateway.preparations.isEmpty())
            // Local maintenance never reserved transfer capacity; exact asset path is the guard.
            assertNotNull(files.tryTransferSlot(1)).close()
            released.set(true)
            assertFailsWith<VeyraException> { owner.coordinator.close() }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            assertEquals(1, owner.gate.closes.get())
            assertEquals(1, owner.validations.size)
            assertNotNull(files.tryLeasePaths(listOf(asset.uri))).close()
        } finally { released.set(true) }
    }

    @Test fun recoveryValidatorReleaseFailureCannotPublishAFileOrDiscardOriginalFinalizationHistory() = acquisitionDownloadRoomCase {
        val old = owner()
        val accepted = acceptNeutral(old)
        val id = enqueue(old, accepted)
        val saved = seedFinalization(old, id)
        old.coordinator.close()
        old.store.close()
        val owner = owner()
        val released = AtomicBoolean()
        owner.validation = { input -> throw LocalMediaValidationReaderUnacknowledged(input,
            LocalMediaValidationReaderRelease { released.get() }) }
        try {
            owner.coordinator.start(DownloadActivation.DURABLE_WORK)
            assertReaderBlocked(owner, saved.stagingPath)
            val unexpectedFinalPath = files.tryLeasePaths(listOf(saved.finalPath))
            try { assertNull(unexpectedFinalPath, "Recovery holds BOTH staging and publication path leases") }
            finally { unexpectedFinalPath?.close() }
            val state = owner.store.acquisitionView().acquisition
            assertEquals(saved, state.physicalFinalizations.getValue(id))
            assertEquals(PhysicalDownloadMaintenanceKind.RECOVER_FINALIZATION, state.physicalDownloads.getValue(id).maintenanceKind)
            assertTrue(state.materializations.isEmpty())
            assertFalse(Files.exists(Path.of(saved.finalPath)))
            val original = state.physicalAttempts.getValue(assertNotNull(saved.attemptId))
            assertEquals(saved.ownershipGeneration, original.ownershipGeneration)
            assertTrue(owner.gateway.preparations.isEmpty())
            assertTrue(owner.selector.requests.isEmpty())
            released.set(true)
            assertFailsWith<VeyraException> { owner.coordinator.close() }
            assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
            val after = owner.store.acquisitionView().acquisition
            assertEquals(saved, after.physicalFinalizations.getValue(id))
            assertEquals(original, after.physicalAttempts.getValue(original.id))
            assertTrue(after.materializations.isEmpty())
        } finally { released.set(true) }
    }

    private suspend fun DownloadAcquisitionRoomFixture.assertReaderBlocked(owner: DownloadAcquisitionRoomOwner, path: String) {
        awaitCondition { owner.coordinator.hasPendingCleanupOwners &&
            owner.coordinator.lastFailure.value?.detail == "download_owner_drain_failed_restart_required" }
        assertEquals(0, owner.gate.closes.get(), "Reader release MUST be checked before allowance.close")
        val unexpectedPathBeforeClose = files.tryLeasePaths(listOf(path))
        try { assertNull(unexpectedPathBeforeClose) }
        finally { unexpectedPathBeforeClose?.close() }
        assertTrue(Files.exists(Path.of(path)))
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { owner.coordinator.idleDecision() }.failure.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { owner.coordinator.drainForBackground() }.failure.code)
        assertFailsWith<VeyraException> { owner.coordinator.close() }
        assertTrue(owner.coordinator.hasPendingCleanupOwners)
        assertFalse(owner.coordinator.dependencyReleaseSafeAfterClose)
        assertEquals(0, owner.gate.closes.get(), "Explicit close may observe release but must not bypass or retry the native decoder")
        val unexpectedPathAfterClose = files.tryLeasePaths(listOf(path))
        try { assertNull(unexpectedPathAfterClose) }
        finally { unexpectedPathAfterClose?.close() }
    }

    /** Authored interrupted-process state, created only through canonical REAL Room commands. */
    private suspend fun DownloadAcquisitionRoomFixture.seedFinalization(owner: DownloadAcquisitionRoomOwner, id: String): PhysicalDownloadFinalization {
        val before = assertNotNull(owner.repository.physicalDownload(id))
        val part = files.prepareStaging(before.job.producer.subjectId.value, "authored-old-materializer")
        Files.write(Path.of(part.toString()), PHYSICAL_BYTES)
        val claim = assertIs<PhysicalDownloadCommit.Committed<ClaimedPhysicalDownload>>(
            owner.repository.claimPhysicalDownloadAttempt(ClaimPhysicalDownloadAttempt(id, before.job.producer,
                before.job.ownershipGeneration, "authored-old-materializer", "authored-retired-process", clock.epochMs(),
                5_000, part.toString())))
        val fence = claim.value.fence
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.selectPhysicalDownloadRepresentation(
            SelectPhysicalDownloadRepresentation(fence, PHYSICAL_REPRESENTATION.representationId, 0, null, null, clock.epochMs())))
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.heartbeatPhysicalDownload(HeartbeatPhysicalDownload(
            fence, clock.epochMs(), 5_000, PHYSICAL_BYTES.size.toLong(), PHYSICAL_BYTES.size.toLong())))
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.beginPhysicalDownloadValidation(
            BeginPhysicalDownloadValidation(fence, clock.epochMs())))
        val saved = PhysicalDownloadFinalization(id, before.job.producer, part.toString(),
            files.finalPath(before.job.producer.subjectId.value, 0, "audio/mpeg", fence.attemptId!!).toString(),
            PHYSICAL_BYTES.toByteString().sha256().hex(), PHYSICAL_BYTES.size.toLong(), fence.attemptId, 733,
            "audio/mpeg", "mp3", PHYSICAL_REPRESENTATION.representationId, 0, fence.ownershipGeneration,
            MaterializationAssetId("authored-old-materialization"))
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.preparePhysicalDownloadFinalization(
            PreparePhysicalDownloadFinalization(fence, saved, clock.epochMs())))
        assertIs<PhysicalDownloadCommit.Committed<*>>(owner.repository.finishPhysicalDownloadAttempt(FinishPhysicalDownloadAttempt(
            fence, DownloadStatus.VERIFYING, DownloadAttemptStatus.PAUSED, null, null, 0, clock.epochMs())))
        return saved
    }
}
