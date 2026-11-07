package dev.veyra.downloads

import dev.veyra.data.*
import dev.veyra.model.*
import dev.veyra.playback.*
import dev.veyra.source.execution.ExecutionTestPersistence
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * REAL existing Coordinator/files/OS path+slot leases; EXPLICIT FAKE snapshot persistence.
 * This exercises the compatibility AssetValidator call path, NOT schema3 Room (which correctly
 * rejects arbitrary legacy download/binding snapshot writes). Decoder, native gate and central
 * source results are fake and all bytes authored NON-AUDIO. Real schema2 migration tests live in
 * DownloadSchema2AdoptionRoomTest; none of these cases establishes native/playable media proof.
 */
class DownloadLegacyValidationReaderTest {
    @Test fun legacyExecutionRetainsOriginalReaderBeforePlanResourceAllowancePathSlotAndAttemptFinish() = legacyValidationCase {
        val seeded = seed.seedPending(store, bound = true)
        val resourceCloses = AtomicInteger()
        val plan = gateway.plan
        gateway.plan = { binding -> plan(binding).copy(resource = object : PlaybackResource {
            override suspend fun close() { resourceCloses.incrementAndGet() }
        }) }
        gateway.search = { error("An already bound legacy transfer must not search again") }
        coordinator.start(DownloadActivation.DURABLE_WORK)
        val input = withTimeout(10_000) { observed.await() }
        assertBlocked(listOf(input.uri), transferSlot = true)
        val blocked = store.flush().getOrThrow()
        val job = blocked.downloads.getValue(seeded.record.id)
        val attempt = blocked.downloadAttempts.getValue(assertNotNull(job.activeAttemptId))
        assertNull(attempt.finishedEpochMs)
        assertNull(job.asset)
        assertTrue(blocked.downloadFinalizations.isEmpty())
        assertEquals(0, resourceCloses.get(), "The plan resource is downstream of the same retained native reader")
        assertTrue(gateway.queries.isEmpty())
        assertEquals(1, gateway.resolutions.size)
        assertEquals(1, gateway.transfers.size)
        releaseReaderAndClose(listOf(input.uri))
        assertEquals(1, resourceCloses.get())
        assertNotNull(store.flush().getOrThrow().downloadAttempts.getValue(attempt.id).finishedEpochMs)
        assertEquals(1, validations.get())
    }

    @Test fun legacyCompletedCheckCannotLaunderAMismatchedReportedInputIntoAnOrdinaryAssetFailure() = legacyValidationCase {
        val seeded = seed.seedComplete(store)
        val asset = assertNotNull(seeded.record.asset)
        gateway.denyAcquisition()
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        reportedInput = { it.copy(uri = "/authored/mismatched-reported-input") }
        coordinator.start(DownloadActivation.USER_SESSION)
        val observedAsset = withTimeout(10_000) { observed.await() }
        assertEquals(asset, observedAsset)
        assertBlocked(listOf(asset.uri), transferSlot = false)
        val record = store.flush().getOrThrow().downloads.getValue(seeded.record.id)
        assertEquals(DownloadStatus.COMPLETE, record.status, "An unacknowledged reader is not a normal corrupt-file result")
        assertEquals(asset, record.asset)
        assertNotNull(record.leaseOwner, "The failed reader must still own the actual original path")
        assertTrue(Files.exists(Path.of(asset.uri)))
        gateway.assertUnused()
        releaseReaderAndClose(listOf(asset.uri))
        assertEquals(asset, store.flush().getOrThrow().downloads.getValue(seeded.record.id).asset)
        assertEquals(1, validations.get())
    }

    @Test fun cancellationOfLegacyFinalizationRetainsTheReaderBothPathsAndOriginalIntentUntilPositiveRelease() = legacyValidationCase {
        val seeded = seed.seedFinalization(store, published = false)
        val intent = assertNotNull(seeded.intent)
        val attempt = assertNotNull(seeded.attempt)
        val validating = seed.barrier()
        gateway.denyAcquisition()
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        validate = { asset ->
            try { validating.stop(); AssetValidation.Valid(31_234) }
            finally {
                if (!released.get()) throw LocalMediaValidationReaderUnacknowledged(asset.localInput(),
                    LocalMediaValidationReaderRelease { released.get() })
            }
        }
        coordinator.start(DownloadActivation.DURABLE_WORK)
        withTimeout(10_000) { validating.entered.await() }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { coordinator.drainForBackground() }.failure.code)
        assertBlocked(listOf(intent.stagingPath, intent.finalPath), transferSlot = false)
        val blocked = store.flush().getOrThrow()
        assertEquals(intent, blocked.downloadFinalizations.getValue(intent.downloadId))
        assertEquals(attempt, blocked.downloadAttempts.getValue(attempt.id))
        assertNull(blocked.downloads.getValue(intent.downloadId).asset)
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertTrue(Files.exists(Path.of(intent.stagingPath)))
        gateway.assertUnused()
        releaseReaderAndClose(listOf(intent.stagingPath, intent.finalPath))
        val after = store.flush().getOrThrow()
        assertEquals(intent, after.downloadFinalizations.getValue(intent.downloadId))
        assertEquals(attempt, after.downloadAttempts.getValue(attempt.id))
        assertFalse(Files.exists(Path.of(intent.finalPath)))
        assertEquals(1, validations.get())
    }
}

private class LegacyValidationFallbackFixture {
    val seed = DownloadFixture()
    // Intentionally NOT DownloadFixture.persistence()/Room. The production typed backend's
    // canonical-command requirement must never be relaxed to seed these compatibility cases.
    val store = seed.store(ExecutionTestPersistence())
    val gateway = DownloadTestGateway()
    val gate = PhysicalRuntimeGate(CopyOnWriteArrayList())
    val released = AtomicBoolean()
    val validations = AtomicInteger()
    val observed = CompletableDeferred<OfflineAsset>()
    var reportedInput: (LocalMediaValidationInput) -> LocalMediaValidationInput = { it }
    var validate: suspend (OfflineAsset) -> AssetValidation = { asset ->
        throw LocalMediaValidationReaderUnacknowledged(reportedInput(asset.localInput()),
            LocalMediaValidationReaderRelease { released.get() })
    }
    val coordinator = seed.coordinator(store, gateway, AssetValidator { asset ->
        validations.incrementAndGet()
        observed.complete(asset)
        validate(asset)
    }, gate)

    init { assertFalse(store.requiresTypedPlayback, "This is the explicit legacy fake-persistence branch") }

    suspend fun assertBlocked(paths: List<String>, transferSlot: Boolean) {
        withTimeout(10_000) {
            while (!coordinator.hasPendingCleanupOwners ||
                coordinator.lastFailure.value?.detail != "download_owner_drain_failed_restart_required") delay(10)
        }
        assertEquals(1, gate.acquires.get())
        assertEquals(0, gate.closes.get())
        paths.forEach { path ->
            val competing = seed.files.tryLeasePaths(listOf(path))
            try { assertNull(competing, "Original native reader still owns this exact real path lease") }
            finally { competing?.close() }
        }
        val competingSlot = seed.files.tryTransferSlot(1)
        try {
            if (transferSlot) assertNull(competingSlot, "A retained transfer validator still owns physical capacity")
            else assertNotNull(competingSlot, "Local check/finalization does not acquire a transfer slot")
        } finally { competingSlot?.close() }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { coordinator.idleDecision() }.failure.code)
        assertEquals(FailureCode.BACKGROUND_RESTRICTED,
            assertFailsWith<VeyraException> { coordinator.drainForBackground() }.failure.code)
        assertFailsWith<VeyraException> { coordinator.close() }
        assertFalse(coordinator.dependencyReleaseSafeAfterClose)
        assertTrue(coordinator.hasPendingCleanupOwners)
        assertEquals(0, gate.closes.get(), "Explicit close only observes the reader; it cannot retry its native destroy")
    }

    suspend fun releaseReaderAndClose(paths: List<String>) {
        released.set(true) // Authored observation of the ORIGINAL reader, not a replacement decoder.
        assertFailsWith<VeyraException> { coordinator.close() } // Prior failure remains diagnostic after release.
        assertTrue(coordinator.dependencyReleaseSafeAfterClose)
        assertFalse(coordinator.hasPendingCleanupOwners)
        assertEquals(1, gate.closes.get())
        assertNotNull(seed.files.tryLeasePaths(paths)).close()
        assertNotNull(seed.files.tryTransferSlot(1)).close()
    }

    suspend fun close() {
        released.set(true) // Ensure an assertion failure cannot strand the authored retained reader.
        seed.barriers.forEach(DownloadBarrier::release)
        gate.beforeClose = { }
        for (owner in seed.coordinators.asReversed()) {
            try { withTimeout(10_000) { owner.close() } }
            catch (_: VeyraException) { assertTrue(owner.dependencyReleaseSafeAfterClose) }
            assertTrue(owner.dependencyReleaseSafeAfterClose)
        }
        seed.parent.coroutineContext.job.cancelAndJoin()
        seed.stores.asReversed().forEach { it.close() }
        assertFalse(Files.exists(seed.databasePath), "No SQLite/Room execution evidence is claimed by this fallback fixture")
    }
}

private fun OfflineAsset.localInput() = LocalMediaValidationInput(uri, kind, sizeBytes, mimeType, codecs,
    sha256, representationId, validatorGeneration)

private fun legacyValidationCase(block: suspend LegacyValidationFallbackFixture.() -> Unit) = runBlocking {
    val fixture = LegacyValidationFallbackFixture()
    try { withTimeout(45_000) { fixture.block() } }
    finally { withContext(NonCancellable) { fixture.close() } }
}
