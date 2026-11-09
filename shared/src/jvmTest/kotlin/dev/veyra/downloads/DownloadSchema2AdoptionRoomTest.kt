package dev.veyra.downloads

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.data.room.*
import dev.veyra.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * REAL exported schema2 -> production Room migration/reopen -> existing Coordinator/OS locks.
 * Historical journal/bytes and fresh source responses are authored NON-AUDIO fixtures. Native
 * playback-reader drain is deliberately fake; these are not native/live/offline-playback proof.
 */
class DownloadSchema2AdoptionRoomTest {
    @Test fun anUnboundRetiredAttemptPathAloneSchedulesExactlyOneCleanupAcrossRoomReopen() =
        retiredPathCleanup(withBinding = false)

    @Test fun aNonExecutableSchema2ReceiptAndRetiredAttemptRemainImmutableAfterHistoryOnlyCleanup() =
        retiredPathCleanup(withBinding = true)

    @Test fun noBindingAdoptionStillAwaitsTheRequiredReaderAndUsesOnlyTheExplicitFreshCompleteTitle() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = false)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val history = owner.store.acquisitionView()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        val before = assertNotNull(owner.repository.physicalDownload(seed.jobId))
        val entered = barrier()
        val callbacks = AtomicInteger()
        val commits = AtomicInteger()
        owner.beforeAdoption = { producer ->
            callbacks.incrementAndGet()
            assertEquals(before.job.producer, producer)
            assertNull(producer.bindingId)
            assertNull(producer.recordingId)
            assertFalse(Files.exists(seed.directory), "The entire retired generation, not only transfer.part, was cleaned")
            entered.stop()
        }
        faults(owner.store).beforeCommit = { old, next ->
            val a = old.state().physicalDownloads.getValue(seed.jobId)
            val b = next.state().physicalDownloads.getValue(seed.jobId)
            if (a.producer != b.producer) {
                commits.incrementAndGet()
                assertEquals(before.job.producer, a.producer)
                assertIs<PhysicalDownloadProducer.Typed>(b.producer)
                assertNull(files.tryTransferSlot(4), "All existing transfer slots/admission are held across the canonical adoption commit")
                assertNull(files.tryLeasePaths(listOf(seed.part.toString())), "Retired generation path is held across the same commit")
                val receipt = next.rows.physicalDownloadAdoptionRows.single()
                assertEquals(seed.jobId, receipt.jobId)
                assertNull(receipt.oldBindingId)
                assertNull(receipt.oldRecordingId)
                assertEquals(selected.request.id.value, receipt.newRequestId)
            }
        }
        coroutineScope {
            val adoption = async { owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { } }
            withTimeout(10_000) { entered.entered.await() }
            delay(90)
            assertFalse(adoption.isCompleted)
            assertEquals(0, commits.get())
            assertEquals(before.job.producer, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId).producer)
            assertNoAdoptions()
            assertTrue(owner.gateway.preparations.isEmpty())
            assertEquals(listOf(selected.request), owner.selector.requests.toList())
            entered.release()
            assertEquals(seed.jobId, withTimeout(10_000) { adoption.await() })
        }
        idle(owner)
        val adopted = owner.store.acquisitionView()
        val newJob = adopted.acquisition.physicalDownloads.getValue(seed.jobId)
        val newClaim = adopted.acquisition.claims.values.single()
        assertEquals(before.job.ownershipGeneration + 1, newJob.ownershipGeneration)
        assertEquals(selected.singleBinding(), newJob.producer.bindingId)
        assertEquals(before.claims.single().id, newClaim.id)
        assertEquals(before.claims.single().owner, newClaim.owner)
        assertNull(newClaim.requestedBindingId, "The historical owner is not retroactively given a captured request")
        assertEquals(DownloadBatchIntent.ACTIVE, newClaim.intent)
        assertNull(newJob.stagingPath)
        assertNull(newJob.representationId)
        assertEquals(0L, newJob.bytesDownloaded)
        assertEquals(1, callbacks.get())
        assertEquals(1, commits.get())
        assertHistorical(seed, history, adopted)
        assertTrue(owner.gateway.transfers.isEmpty(), "Offline adoption is not permission to execute a remote transfer")

        val transfer = owner.gateway.transfer
        owner.gateway.transfer = { prepared, path, progress ->
            assertEquals(selected.request, prepared.context.request)
            assertNotEquals(seed.part.toString(), path, "Unknown old bytes never become a resumable fresh capture")
            assertFalse(Files.exists(seed.directory))
            transfer(prepared, path, progress)
        }
        offline(owner, enabled = false)
        awaitState(owner.store) { it.physicalDownloads.getValue(seed.jobId).status == DownloadStatus.COMPLETE }
        idle(owner)
        assertEquals(1, owner.gateway.transfers.size)
        assertEquals(1, owner.selector.requests.size, "Do not research the old title/receipt or the selected recording title")
        owner.legacy.assertUnused()
        assertHistorical(seed, history, owner.store.acquisitionView())
        owner.coordinator.close()
        owner.store.close()
        val reopened = owner()
        reopened.coordinator.initializeScheduling()
        assertHistorical(seed, history, reopened.store.acquisitionView())
        assertEquals(selected.singleBinding(), reopened.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId).producer.bindingId)
        withSql(databasePath) { db -> assertEquals(1L, db.longValue("SELECT count(*) FROM download_producer_adoptions")) }
        assertTrue(reopened.selector.requests.isEmpty())
        assertTrue(reopened.gateway.preparations.isEmpty())
        assertEquals(0, reopened.gate.acquires.get())
    }

    @Test fun failedRequiredReaderCallbackCannotReplaceAnUnknownProducerOrRewriteItsOldReceipt() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = true)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val history = owner.store.acquisitionView()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        val callbacks = AtomicInteger()
        val refusal = AppFailure(FailureCode.BACKGROUND_RESTRICTED, "authored_original_native_reader_retained")
        owner.beforeAdoption = { callbacks.incrementAndGet(); throw VeyraException(refusal) }
        assertEquals(refusal, assertFailsWith<VeyraException> {
            owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { }
        }.failure)
        idle(owner)
        delay(90)
        assertEquals(1, callbacks.get(), "The scheduler cannot retry an explicit reader-drain/adoption action")
        val state = owner.store.acquisitionView()
        assertIs<PhysicalDownloadProducer.Schema2>(state.acquisition.physicalDownloads.getValue(seed.jobId).producer)
        assertEquals(DownloadBatchIntent.CANCELLED, state.acquisition.claims.values.single().intent)
        assertFalse(state.acquisition.claims.values.single().retainCompletedAsset)
        assertHistorical(seed, history, state)
        assertNoAdoptions()
        assertTrue(owner.gateway.preparations.isEmpty())
        assertEquals(1, owner.selector.requests.size)
        owner.legacy.assertUnused()
    }

    @Test fun aRetiredHighSlotStillRejectsAdoptionAtParallelismOneWithoutConsumingThatSlot() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = false)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        parallelism(owner, 1)
        val high = assertNotNull(leases.acquire((files.root / ".locks" / "slot-3").toString()))
        val callbacks = AtomicInteger()
        owner.beforeAdoption = { callbacks.incrementAndGet() }
        try {
            assertEquals("download_adoption_busy", assertFailsWith<VeyraException> {
                owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { }
            }.failure.detail)
            assertEquals(1, callbacks.get())
            assertNoAdoptions()
            assertEquals(1, leases.open.get(), "Only the externally held high slot remains owned")
            assertNull(files.tryTransferSlot(1), "Lowering the limit does not waive a live high slot")
            assertTrue(owner.gateway.preparations.isEmpty())
        } finally { high.close() }
        assertEquals(0, leases.open.get())
    }

    @Test fun theRetiredGenerationLockRemainsRequiredAfterTheReaderCallbackAndPriorCleanup() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = true)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        var stillReading: DownloadFileLease? = null
        owner.beforeAdoption = { stillReading = assertNotNull(files.tryLeasePaths(listOf(seed.part.toString()))) }
        try {
            assertEquals("download_adoption_busy", assertFailsWith<VeyraException> {
                owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { }
            }.failure.detail)
            assertNoAdoptions()
            assertNull(files.tryLeasePaths(listOf(seed.part.toString())))
            assertEquals(1, leases.open.get())
        } finally { stillReading?.close() }
        assertEquals(0, leases.open.get())
    }

    @Test fun aRemainingJournalWithoutTransferPartCannotBeMistakenForAnAbsentOldGeneration() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = true)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        owner.beforeAdoption = {
            Files.createDirectories(seed.directory)
            Files.writeString(seed.directory.resolve("authored-late-journal-only"), "Authored marker; no source credentials")
        }
        assertEquals("download_legacy_staging_remains", assertFailsWith<VeyraException> {
            owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { }
        }.failure.detail)
        assertFalse(Files.exists(seed.part))
        assertTrue(Files.exists(seed.directory))
        assertNoAdoptions()
        assertEquals(0, leases.open.get())
        assertTrue(owner.gateway.preparations.isEmpty())
    }

    @Test fun staleExplicitIntentAfterTheRequiredReaderCallbackCannotReviveTheCancelledSchema2Owner() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = false)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        cleanUnknown(owner, seed)
        var current = true
        owner.beforeAdoption = { current = false }
        assertFailsWith<CancellationException> {
            owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) {
                if (!current) throw CancellationException("Authored explicit action was superseded")
            }
        }
        val state = owner.store.acquisitionView().acquisition
        assertIs<PhysicalDownloadProducer.Schema2>(state.physicalDownloads.getValue(seed.jobId).producer)
        assertEquals(DownloadBatchIntent.CANCELLED, state.claims.values.single().intent)
        assertFalse(state.claims.values.single().retainCompletedAsset)
        assertNoAdoptions()
        assertTrue(owner.gateway.preparations.isEmpty())
        assertEquals(1, owner.selector.requests.size)
    }

    @Test fun adoptionOnColdHostPreservesPendingCleanupInsteadOfAdoptingDestructively() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = false)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        offline(owner)
        // Cold acceptance records REMOVE_CANDIDATE but cannot prove physical cleanup yet.
        val refused = assertFailsWith<VeyraException> { owner.coordinator.remove(seed.jobId) }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, refused.failure.code)
        val pending = owner.store.acquisitionView().acquisition
        assertTrue(pending.physicalDownloads.getValue(seed.jobId).cleanupPending)
        assertTrue(Files.exists(seed.part))
        val adoptionFailure = assertFailsWith<VeyraException> {
            owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { }
        }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, adoptionFailure.failure.code)
        assertEquals("download_owner_drain_unconfirmed", adoptionFailure.failure.detail)
        val preserved = owner.store.acquisitionView().acquisition
        assertIs<PhysicalDownloadProducer.Schema2>(preserved.physicalDownloads.getValue(seed.jobId).producer)
        assertEquals(DownloadBatchIntent.CANCELLED, preserved.claims.values.single().intent)
        assertTrue(preserved.physicalDownloads.getValue(seed.jobId).cleanupPending)
        assertTrue(Files.exists(seed.part))
        assertTrue(Files.exists(seed.journal))
        assertNoAdoptions()
        assertTrue(owner.gateway.preparations.isEmpty())
        // The activated scheduler completes the real cleanup before the explicit adoption retry.
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { state ->
            state.physicalDownloads.getValue(seed.jobId).let { !it.cleanupPending }
        }
        idle(owner)
        assertEquals(seed.jobId, owner.coordinator.adoptSchema2(seed.jobId, selected.request.id,
            selected.singleBinding()) { })
        val adopted = owner.store.acquisitionView().acquisition
        assertEquals(selected.singleBinding(), adopted.physicalDownloads.getValue(seed.jobId).producer.bindingId)
        assertFalse(Files.exists(seed.part))
        withSql(databasePath) { db ->
            assertEquals(1L, db.longValue("SELECT count(*) FROM download_producer_adoptions"))
        }
        assertEquals(0, leases.open.get())
        idle(owner)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    @Test fun adoptionWaitsForSchedulerOwnedCleanupBarrier() = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding = false)
        val owner = owner()
        owner.coordinator.initializeScheduling()
        val selected = freshSelection(owner, seed)
        offline(owner)
        assertFailsWith<VeyraException> { owner.coordinator.remove(seed.jobId) }
        assertTrue(owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId).cleanupPending)
        val gateHeld = barrier()
        var heldOnce = false
        owner.gate.beforeAcquire = {
            if (!heldOnce) {
                heldOnce = true
                gateHeld.stop()
            }
        }
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        gateHeld.entered.await()
        val claimed = owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId)
        assertTrue(claimed.cleanupPending)
        assertTrue(claimed.removeWhenClean)
        assertNotNull(claimed.leaseOwner, "The scheduler cleanup must own the maintenance lease at the barrier")
        assertEquals(PhysicalDownloadMaintenanceKind.CLEANUP, claimed.maintenanceKind)
        coroutineScope {
            val adoption = async {
                runCatching { owner.coordinator.adoptSchema2(seed.jobId, selected.request.id, selected.singleBinding()) { } }
            }
            delay(90)
            assertFalse(adoption.isCompleted, "Adoption must wait for the scheduler-owned cleanup")
            val waiting = owner.store.acquisitionView().acquisition
            val waitingJob = waiting.physicalDownloads.getValue(seed.jobId)
            assertIs<PhysicalDownloadProducer.Schema2>(waitingJob.producer)
            assertTrue(waitingJob.cleanupPending)
            assertTrue(waitingJob.removeWhenClean)
            assertTrue(Files.exists(seed.part))
            assertTrue(Files.exists(seed.journal))
            assertNull(files.tryLeasePaths(listOf(seed.part.toString())),
                "The scheduler cleanup holds the exact retired path lease at the barrier")
            val waitingClaims = waiting.claims.values.filter { it.physicalJobId == seed.jobId }
            assertEquals(1, waitingClaims.size)
            assertEquals(DownloadBatchIntent.CANCELLED, waitingClaims.single().intent)
            assertFalse(waitingClaims.single().retainCompletedAsset)
            assertNoAdoptions()
            gateHeld.release()
            assertEquals(seed.jobId, withTimeout(10_000) { adoption.await().getOrThrow() })
        }
        val adopted = owner.store.acquisitionView().acquisition
        assertIs<PhysicalDownloadProducer.Typed>(adopted.physicalDownloads.getValue(seed.jobId).producer)
        assertEquals(selected.singleBinding(), adopted.physicalDownloads.getValue(seed.jobId).producer.bindingId)
        assertEquals(DownloadBatchIntent.ACTIVE, adopted.claims.values.single { it.physicalJobId == seed.jobId }.intent)
        assertFalse(adopted.physicalDownloads.getValue(seed.jobId).cleanupPending)
        assertFalse(Files.exists(seed.part))
        assertTrue(owner.gateway.preparations.isEmpty())
        assertTrue(owner.gateway.transfers.isEmpty())
        withSql(databasePath) { db ->
            assertEquals(1L, db.longValue("SELECT count(*) FROM download_producer_adoptions"))
        }
        assertEquals(0, leases.open.get())
        idle(owner)
        owner.coordinator.close()
        assertTrue(owner.coordinator.dependencyReleaseSafeAfterClose)
    }

    private fun retiredPathCleanup(withBinding: Boolean) = acquisitionDownloadRoomCase {
        val seed = seedSchema2(withBinding)
        val owner = owner()
        owner.coordinator.initializeScheduling() // This is the actual production 2 -> 3 migration.
        val original = owner.store.acquisitionView()
        val job = original.acquisition.physicalDownloads.getValue(seed.jobId)
        assertIs<PhysicalDownloadProducer.Schema2>(job.producer)
        assertEquals(seed.bindingId?.let(::ExecutionBindingId), job.producer.bindingId)
        assertNull(job.producer.recordingId)
        assertNull(job.stagingPath)
        assertNull(job.assetId)
        assertNull(job.activeAttemptId)
        assertTrue(original.acquisition.physicalFinalizations.isEmpty())
        assertFalse(job.cleanupPending)
        assertFalse(job.removeWhenClean)
        assertEquals(seed.part.toString(), original.acquisition.physicalAttempts.getValue(seed.attemptId).stagingPath)
        assertNotNull(original.acquisition.physicalAttempts.getValue(seed.attemptId).finishedEpochMs)
        assertTrue(Files.exists(seed.journal))
        assertEquals(0, owner.gate.acquires.get())
        withSql(databasePath) { db ->
            assertEquals(3L, db.longValue("PRAGMA user_version"))
            assertEquals("ok", db.textValue("PRAGMA integrity_check"))
            assertFalse(db.prepare("PRAGMA foreign_key_check").use { it.step() })
        }
        cleanUnknown(owner, seed)
        val cleaned = owner.store.acquisitionView()
        assertHistorical(seed, original, cleaned)
        assertEquals(1, owner.gate.acquires.get())
        assertEquals(1, owner.gate.closes.get())
        assertTrue(owner.selector.requests.isEmpty())
        assertTrue(owner.gateway.preparations.isEmpty())
        owner.legacy.assertUnused()
        val generation = cleaned.acquisition.physicalDownloads.getValue(seed.jobId).ownershipGeneration
        assertTrue(generation > job.ownershipGeneration)
        delay(90)
        assertEquals(generation, owner.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId).ownershipGeneration)
        assertEquals(1, owner.gate.acquires.get(), "Retained immutable attempt paths must not cause an endless cleanup loop")
        owner.coordinator.close()
        owner.store.close()
        val restarted = owner()
        restarted.coordinator.start(DownloadActivation.DURABLE_WORK)
        idle(restarted)
        assertHistorical(seed, original, restarted.store.acquisitionView())
        assertEquals(generation, restarted.store.acquisitionView().acquisition.physicalDownloads.getValue(seed.jobId).ownershipGeneration)
        assertEquals(0, restarted.gate.acquires.get())
        assertTrue(restarted.selector.requests.isEmpty())
        assertTrue(restarted.gateway.preparations.isEmpty())
        assertNoAdoptions()
        assertEquals(0, leases.open.get())
    }

    private suspend fun DownloadAcquisitionRoomFixture.cleanUnknown(owner: DownloadAcquisitionRoomOwner, seed: Schema2Seed) {
        offline(owner)
        // The cold host cannot prove physical cleanup; removal must report a truthful restriction
        // while retaining the accepted REMOVE_CANDIDATE intent for the activated cleanup below.
        val refused = assertFailsWith<VeyraException> { owner.coordinator.remove(seed.jobId) }
        assertEquals(FailureCode.BACKGROUND_RESTRICTED, refused.failure.code)
        val queued = owner.store.acquisitionView().acquisition
        val job = queued.physicalDownloads.getValue(seed.jobId)
        assertTrue(job.cleanupPending, "Canonical REMOVE_CANDIDATE must account for a finished attempt's path even without a job stagingPath")
        assertTrue(job.removeWhenClean)
        assertEquals(0, owner.gate.acquires.get())
        assertTrue(Files.exists(seed.journal))
        owner.coordinator.start(DownloadActivation.DURABLE_WORK)
        awaitState(owner.store) { state -> state.physicalDownloads.getValue(seed.jobId).let {
            !it.cleanupPending && it.removeWhenClean && it.leaseOwner == null && it.maintenanceKind == null
        } }
        idle(owner)
        assertFalse(Files.exists(seed.directory))
        assertEquals(0, leases.open.get())
    }

    private suspend fun DownloadAcquisitionRoomFixture.freshSelection(owner: DownloadAcquisitionRoomOwner, seed: Schema2Seed): AcquisitionRecord {
        val current = owner.store.acquisitionView().snapshot.media.getValue(seed.mediaId)
        val requested = current.copy(title = "Explicit fresh COMPLETE requested title — العنوان الجديد الكامل")
        val captured = owner.acquisition.capture(MediaCapture(requested))
        val selected = assertIs<AcquisitionResolution.Accepted>(owner.acquisition.resolve(captured.request.id)).record
        assertEquals(requested.title, selected.request.completeTitle)
        assertEquals(listOf(selected.request), owner.selector.requests.toList())
        owner.selector.answer = { error("No implicit old-title/receipt or selected-recording requery") }
        return selected
    }

    private fun AcquisitionRecord.singleBinding(): ExecutionBindingId = assertIs<AcquisitionOutcome.Single>(outcome).bindingId

    private fun DownloadAcquisitionRoomFixture.assertNoAdoptions() = withSql(databasePath) { db ->
        assertEquals(0L, db.longValue("SELECT count(*) FROM download_producer_adoptions"))
    }

    private fun assertHistorical(seed: Schema2Seed, original: LibraryAcquisitionView, current: LibraryAcquisitionView) {
        assertEquals(original.acquisition.physicalAttempts.getValue(seed.attemptId), current.acquisition.physicalAttempts.getValue(seed.attemptId))
        assertEquals(original.snapshot.history, current.snapshot.history)
        assertEquals(original.snapshot.progress, current.snapshot.progress)
        assertEquals(original.snapshot.playbackBindings, current.snapshot.playbackBindings)
        original.acquisition.receipts.forEach { (id, receipt) -> assertEquals(receipt, current.acquisition.receipts[id]) }
        val historical = current.acquisition.physicalAttempts.getValue(seed.attemptId)
        assertIs<PhysicalDownloadProducer.Schema2>(historical.producer)
        assertNull(historical.producer.recordingId)
        assertEquals(seed.part.toString(), historical.stagingPath)
    }

    private data class Schema2Seed(val mediaId: String, val jobId: String, val attemptId: String,
        val bindingId: String?, val part: Path, val journal: Path) { val directory: Path get() = part.parent }

    /** Only the real exported version2 schema is created; no hand-authored schema3 expectation. */
    private fun DownloadAcquisitionRoomFixture.seedSchema2(withBinding: Boolean): Schema2Seed {
        check(!Files.exists(databasePath))
        val media = "authored-schema2-media"
        val job = "authored-schema2-job"
        val attempt = "authored-finished-schema2-attempt"
        val binding = "authored-nonexecutable-schema2-receipt".takeIf { withBinding }
        val part = Path.of(files.prepareStaging(media, attempt).toString())
        Files.write(part, PHYSICAL_BYTES)
        val journal = part.parent.resolve("authored-private-transfer-journal")
        Files.writeString(journal, "Authored inert marker, not a kmpYT receipt or source credentials")
        withSchema2Helper(databasePath) { helper -> helper.createDatabase(2).use { db ->
            db.execSQL("BEGIN IMMEDIATE")
            try {
                listOf("MUSIC", "PODCAST").forEach { context -> db.insertSeed("queue_contexts", mapOf(
                    "id" to context, "positionMs" to 0L, "shuffle" to false, "repeat" to "OFF", "speed" to 1.0, "hasOriginalOrder" to false)) }
                db.insertSeed("store_meta", mapOf("id" to 0, "revision" to 7L, "activeContext" to "MUSIC"))
                db.insertSeed("settings", mapOf("id" to 0, "language" to "ARABIC", "theme" to "DARK", "textScale" to 1.0,
                    "offlineOnly" to false, "speed" to 1.0, "skipBackSeconds" to 15, "skipForwardSeconds" to 30,
                    "crossfadeEnabled" to false, "crossfadeDurationMs" to 3000L, "downloadParallelism" to 1))
                db.insertSeed("media", mapOf("id" to media, "title" to "Current catalogue title, not the old query — عنوان",
                    "kind" to "MUSIC", "creator" to "Authored creator", "description" to "Authored metadata only", "explicit" to false,
                    "source_provider" to "COMMONS", "source_canonicalId" to "authored-schema2-catalog-id"))
                db.insertSeed("tracks", mapOf("mediaId" to media))
                if (binding != null) db.insertSeed("playback_bindings", mapOf("id" to binding, "mediaId" to media,
                    "queryTitle" to "Historical captured full title — العنوان التاريخي", "firstResultKind" to "PLAYLIST",
                    "firstResultId" to "PLauthored-not-executable", "firstResultTitle" to "Historical FIRST collection",
                    "firstResultUrl" to "https://www.youtube.com/playlist?list=PLauthored-not-executable", "selectedVideoId" to null,
                    "resolvedEpochMs" to 900L, "selectionOrdinal" to 0))
                db.insertSeed("media_progress", mapOf("mediaId" to media, "positionMs" to 1234L, "durationMs" to 9000L,
                    "played" to false, "updatedEpochMs" to 950L, "sessionId" to "authored-historical-session", "generation" to 2L,
                    "sequence" to 3L, "executionBindingId" to binding))
                db.insertSeed("playback_history", mapOf("id" to "authored-old-listening-history", "mediaId" to media,
                    "startedEpochMs" to 940L, "lastPositionMs" to 1234L, "endedNaturally" to false, "executionBindingId" to binding, "position" to 0))
                db.insertSeed("downloads", mapOf("id" to job, "mediaId" to media, "status" to "NEEDS_FOREGROUND",
                    "bytesDownloaded" to 0L, "validatorGeneration" to 0L, "updatedEpochMs" to 2000L, "executionBindingId" to binding,
                    "attemptCount" to 1, "priority" to 0, "createdEpochMs" to 1000L, "userPaused" to false, "automaticRetryCount" to 0))
                db.insertSeed("download_attempts", mapOf("id" to attempt, "downloadId" to job, "number" to 1, "status" to "FAILED",
                    "startedEpochMs" to 1000L, "finishedEpochMs" to 1100L, "bytesDownloaded" to PHYSICAL_BYTES.size.toLong(),
                    "totalBytes" to PHYSICAL_BYTES.size.toLong() + 37, "stagingPath" to part.toString(),
                    "representationId" to "authored-unknown-old-representation", "executionBindingId" to binding,
                    "etag" to "\"authored-old-validator\"", "validatorGeneration" to 3L))
                db.execSQL("COMMIT")
            } catch (failure: Throwable) {
                try { db.execSQL("ROLLBACK") } catch (rollback: Throwable) { if (rollback !== failure) failure.addSuppressed(rollback) }
                throw failure
            }
        } }
        return Schema2Seed(media, job, attempt, binding, part, journal)
    }

    private fun withSchema2Helper(path: Path, block: (MigrationTestHelper) -> Unit) {
        val opened = mutableListOf<SQLiteConnection>()
        val real = BundledSQLiteDriver()
        val driver = VeyraSQLiteDriver(object : SQLiteDriver by real {
            override fun open(fileName: String) = real.open(fileName).also(opened::add)
        })
        val owner = object : AutoCloseable {
            override fun close() {
                var primary: Throwable? = null
                opened.asReversed().forEach { connection ->
                    try { connection.close() } catch (failure: Throwable) {
                        val previous = primary
                        if (previous == null) primary = failure else if (previous !== failure) previous.addSuppressed(failure)
                    }
                }
                primary?.let { throw it }
            }
        }
        // Failed MigrationTestHelper opens may still own a raw SQLite handle: retain all of them.
        owner.use { block(MigrationTestHelper(schemaDirectoryPath = projectRoot().resolve("shared/schemas"), databasePath = path,
            driver = driver, databaseClass = VeyraDatabase::class, databaseFactory = VeyraDatabaseConstructor::initialize)) }
    }

    private fun SQLiteConnection.insertSeed(table: String, values: Map<String, Any?>) {
        val fields = values.entries.toList()
        prepare("INSERT INTO `$table` (${fields.joinToString(",") { "`${it.key}`" }}) VALUES (${fields.joinToString(",") { "?" }})").use { statement ->
            fields.forEachIndexed { index, entry -> when (val value = entry.value) {
                null -> statement.bindNull(index + 1)
                is String -> statement.bindText(index + 1, value)
                is Boolean -> statement.bindLong(index + 1, if (value) 1L else 0L)
                is Int -> statement.bindLong(index + 1, value.toLong())
                is Long -> statement.bindLong(index + 1, value)
                is Double -> statement.bindDouble(index + 1, value)
                else -> error("Unsupported authored schema2 scalar")
            } }
            statement.step()
        }
    }
}
