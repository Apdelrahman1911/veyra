package dev.veyra.downloads

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.veyra.data.getOrThrow
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** D09/D20: real Room and file integrity, with AUTHORED NON-AUDIO bytes and a fake decoder. */
class DownloadIntegrityRoomTest {
    @Test fun missingCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording() = damagedCompletedFile {
        Files.delete(it)
    }

    @Test fun truncatedCompletedFileIsNeverReadyAndExplicitRetryRetainsItsRecording() = damagedCompletedFile {
        Files.write(it, authoredBytes.copyOf(authoredBytes.size / 2))
    }

    @Test fun nonregularCompletedPathIsNeverReadyAndExplicitRetryRetainsItsRecording() = damagedCompletedFile {
        Files.delete(it)
        Files.createDirectory(it)
    }

    @Test fun invalidCompletedMaterializationLinksFailBeforeNativeValidationWithoutLosingTheAsset() {
        MaterializedDefect.entries.forEach { defect -> rejectedMaterialization(defect, complete = true) }
    }

    @Test fun invalidFinalizationLinksFailBeforeNativeValidationWithoutLosingPublishedOrStagedBytes() {
        MaterializedDefect.entries.forEach { defect ->
            rejectedMaterialization(defect, complete = false, published = false)
            rejectedMaterialization(defect, complete = false, published = true)
        }
    }

    @Test fun changedBindingValueUnderTheSameIdInvalidatesTheExactCompletedCheckReceipt() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        val rechecking = barrier()
        val validations = AtomicInteger()
        val native = AssetValidator {
            if (validations.incrementAndGet() == 2) rechecking.stop()
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native)
        val snapshots = CopyOnWriteArrayList<AppSnapshot>()
        val collecting = parent.launch(start = CoroutineStart.UNDISPATCHED) { a.presentation.collect { snapshots += it } }
        a.start()
        withTimeout(3_000) { a.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
        eventually(first) { it.downloads[seeded.record.id]?.leaseOwner == null }

        // Normal Room transitions make receipts immutable. Deliberately alter only a real SQLite
        // receipt row to model an external replacement/corruption; no production guard is disabled.
        val changed = seeded.binding.copy(firstResultTitle = "Authored externally replaced receipt title")
        replacePersistedBindingTitle(seeded.binding.id, changed.firstResultTitle)
        val mutation = first.flush().getOrThrow()
        assertEquals(changed, mutation.playbackBindings[changed.id])
        val projected = withTimeout(3_000) { a.presentation.first { it.revision >= mutation.revision } }
        assertEquals(DownloadStatus.VERIFYING, projected.downloads[seeded.record.id]?.status)
        rechecking.entered.await()
        withTimeout(3_000) { while (snapshots.none { it.revision >= mutation.revision }) delay(10) }
        assertEquals(seeded.record.asset, projected.downloads[seeded.record.id]?.asset)
        assertEquals(DownloadStatus.COMPLETE, first.flush().getOrThrow().downloads[seeded.record.id]?.status)
        snapshots.filter { it.revision >= mutation.revision }.forEach {
            assertEquals(changed, it.playbackBindings[changed.id])
            assertNotEquals(DownloadStatus.COMPLETE, it.downloads[seeded.record.id]?.status,
                "A matching receipt ID is not a check of its replaced value")
        }
        rechecking.release()
        withTimeout(3_000) { a.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
        assertEquals(2, validations.get())
        collecting.cancelAndJoin()
        gateway.assertUnused()
    }

    @Test fun removedAndRecreatedDownloadIdsCannotInheritAStaleReadyMapOrCheckedReceipt() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        val rechecking = barrier()
        val validations = AtomicInteger()
        val native = AssetValidator {
            if (validations.incrementAndGet() == 2) rechecking.stop()
            AssetValidation.Valid(31_234)
        }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native)
        val snapshots = CopyOnWriteArrayList<AppSnapshot>()
        val collecting = parent.launch(start = CoroutineStart.UNDISPATCHED) { a.presentation.collect { snapshots += it } }
        a.start()
        withTimeout(3_000) { a.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
        eventually(first) { it.downloads[seeded.record.id]?.leaseOwner == null }
        val removed = first.update { snapshot -> snapshot.copy(downloads = snapshot.downloads - seeded.record.id,
            downloadAttempts = snapshot.downloadAttempts.filterValues { it.downloadId != seeded.record.id },
            playbackBindings = snapshot.playbackBindings - seeded.binding.id) }.getOrThrow()
        val removalProjection = withTimeout(3_000) { a.presentation.first { it.revision >= removed.revision } }
        assertFalse(seeded.record.id in removalProjection.downloads)
        assertFalse(seeded.binding.id in removalProjection.playbackBindings)
        withTimeout(3_000) { while (snapshots.none { it.revision >= removed.revision }) delay(10) }

        // Reinsert only AFTER removal: this is a legal new receipt, not an in-place immutable edit.
        // Keep the download and binding IDs and even the entire asset value identical. Only the
        // receipt value changes, so checking either ID or the file alone would reuse stale Ready.
        val changed = seeded.binding.copy(firstResultTitle = "Authored replacement after removal",
            resolvedEpochMs = seeded.binding.resolvedEpochMs + 1)
        val recreated = first.update { snapshot -> snapshot.copy(
            playbackBindings = snapshot.playbackBindings + (changed.id to changed),
            downloads = snapshot.downloads + (seeded.record.id to seeded.record)) }.getOrThrow()
        val recreatedProjection = withTimeout(3_000) { a.presentation.first { it.revision >= recreated.revision } }
        assertEquals(changed, recreatedProjection.playbackBindings[changed.id])
        assertEquals(seeded.record.asset, recreatedProjection.downloads[seeded.record.id]?.asset)
        assertEquals(DownloadStatus.VERIFYING, recreatedProjection.downloads[seeded.record.id]?.status)
        rechecking.entered.await()
        withTimeout(3_000) { while (snapshots.none { it.revision >= recreated.revision }) delay(10) }
        snapshots.filter { it.revision >= removed.revision && it.revision < recreated.revision }.forEach {
            assertFalse(seeded.record.id in it.downloads, "A fresh removal revision must not contain a stale projected download map")
            assertFalse(seeded.binding.id in it.playbackBindings)
        }
        snapshots.filter { it.revision >= recreated.revision }.forEach {
            assertEquals(changed, it.playbackBindings[changed.id])
            assertNotEquals(DownloadStatus.COMPLETE, it.downloads[seeded.record.id]?.status)
        }
        rechecking.release()
        withTimeout(3_000) { a.presentation.first { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE } }
        assertEquals(2, validations.get())
        collecting.cancelAndJoin()
        gateway.assertUnused()
    }

    @Test fun bindingChangedDuringCompletedValidationCannotPublishACheckForTheReplacement() = downloadCase {
        val first = store()
        val seeded = seedComplete(first)
        val checking = barrier()
        val native = AssetValidator { checking.stop(); AssetValidation.Valid(31_234) }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native)
        a.start(); checking.entered.await()
        replacePersistedBindingTitle(seeded.binding.id, "Authored replacement during completed validation")
        first.flush().getOrThrow()
        checking.release()
        val failed = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.FAILED && d.leaseOwner == null
        } == true }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[seeded.record.id]?.failure?.code)
        assertEquals(seeded.record.asset, failed.downloads[seeded.record.id]?.asset)
        val projected = withTimeout(3_000) { a.presentation.first { it.revision >= failed.revision } }
        assertEquals(DownloadStatus.FAILED, projected.downloads[seeded.record.id]?.status)
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.record.asset!!.uri)))
        gateway.assertUnused()
    }

    @Test fun bindingChangedDuringFinalizationValidationCannotRenameOrCommitAgainstTheReplacement() = downloadCase {
        val first = store()
        val seeded = seedFinalization(first)
        val checking = barrier()
        val native = AssetValidator { checking.stop(); AssetValidation.Valid(31_234) }
        val gateway = DownloadTestGateway().apply { denyAcquisition() }
        val a = coordinator(first, gateway, native)
        a.start(); checking.entered.await()
        replacePersistedBindingTitle(seeded.binding.id, "Authored replacement during recovery validation")
        first.flush().getOrThrow()
        checking.release()
        val failed = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
            d.status == DownloadStatus.FAILED && d.leaseOwner == null
        } == true }
        assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[seeded.record.id]?.failure?.code)
        assertNull(failed.downloads[seeded.record.id]?.asset)
        assertEquals(seeded.intent, failed.downloadFinalizations[seeded.record.id])
        assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(seeded.intent!!.stagingPath)))
        assertFalse(Files.exists(Path.of(seeded.intent.finalPath)))
        gateway.assertUnused()
    }
}

private fun damagedCompletedFile(damage: (Path) -> Unit) = downloadCase {
    val first = store()
    val seeded = seedComplete(first)
    val old = seeded.record.asset!!
    damage(Path.of(old.uri))
    val cleaning = barrier()
    val gateway = DownloadTestGateway()
    val a = coordinator(first, gateway, beforeDelete = { cleaning.stop() })
    assertEquals(DownloadStatus.VERIFYING, a.presentation.value.downloads[seeded.record.id]?.status)
    a.start()
    val failed = eventually(first) { it.downloads[seeded.record.id]?.let { d ->
        d.status == DownloadStatus.FAILED && d.leaseOwner == null
    } == true }
    assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[seeded.record.id]?.failure?.code)
    assertEquals(old, failed.downloads[seeded.record.id]?.asset)
    assertEquals(0, validationCount.get(), "Missing, truncated and nonregular paths must fail before the decoder")
    gateway.assertUnused()
    a.resume(seeded.record.id)
    cleaning.entered.await()
    val cleanup = first.flush().getOrThrow().downloads.getValue(seeded.record.id)
    assertTrue(cleanup.cleanupPending)
    assertEquals(old, cleanup.asset)
    assertEquals(seeded.binding.id, cleanup.executionBindingId)
    gateway.assertUnused()
    cleaning.release()
    val ready = eventually(first) { it.downloads[seeded.record.id]?.status == DownloadStatus.COMPLETE }
    val replacement = ready.downloads.getValue(seeded.record.id).asset!!
    assertNotEquals(old.uri, replacement.uri)
    assertFalse(Files.exists(Path.of(old.uri)))
    assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(replacement.uri)))
    assertEquals(1, gateway.transfers.size)
    assertTrue(gateway.queries.isEmpty(), "Repair must reuse the durable original recording receipt")
    assertEquals(seeded.binding, gateway.transfers.single().binding)
    a.remove(seeded.record.id)
    eventually(first) { seeded.record.id !in it.downloads }
    assertFalse(Files.exists(Path.of(replacement.uri)))
}

private enum class MaterializedDefect {
    UNBOUND, UNSELECTED_VIDEO, NON_VIDEO, DIFFERENT_BINDING, DIFFERENT_REPRESENTATION, MISSING_REPRESENTATION, BLANK_REPRESENTATION,
}

private fun rejectedMaterialization(defect: MaterializedDefect, complete: Boolean, published: Boolean = false) = downloadCase {
    val first = store()
    val seeded = if (complete) seedComplete(first) else seedFinalization(first, published = published)
    val changedBinding = when (defect) {
        MaterializedDefect.UNSELECTED_VIDEO -> seeded.binding.copy(id = "unselected-receipt", selectedVideoId = null)
        MaterializedDefect.NON_VIDEO -> seeded.binding.copy(id = "playlist-receipt", firstResultKind = FirstResultKind.PLAYLIST,
            firstResultId = "authored-playlist", firstResultUrl = "https://www.youtube.com/playlist?list=authored-playlist", selectedVideoId = null)
        MaterializedDefect.DIFFERENT_BINDING -> seeded.binding.copy(id = "different-receipt")
        else -> seeded.binding
    }
    val recordBinding = when (defect) {
        MaterializedDefect.UNBOUND -> null
        MaterializedDefect.DIFFERENT_BINDING -> seeded.binding.id
        else -> changedBinding.id
    }
    val materializedBinding = if (defect == MaterializedDefect.UNBOUND) null else changedBinding.id
    val recordRepresentation = when (defect) {
        MaterializedDefect.MISSING_REPRESENTATION -> null
        MaterializedDefect.BLANK_REPRESENTATION -> "  "
        else -> authoredRepresentation
    }
    val materializedRepresentation = if (defect == MaterializedDefect.DIFFERENT_REPRESENTATION)
        "different-authored-representation" else recordRepresentation
    val invalid = first.update { snapshot ->
        val current = snapshot.downloads.getValue(seeded.record.id)
        val intent = snapshot.downloadFinalizations[current.id]?.copy(executionBindingId = materializedBinding,
            representationId = materializedRepresentation)
        snapshot.copy(playbackBindings = snapshot.playbackBindings + (changedBinding.id to changedBinding),
            downloads = snapshot.downloads + (current.id to current.copy(executionBindingId = recordBinding,
                representationId = recordRepresentation, asset = current.asset?.copy(executionBindingId = materializedBinding,
                    representationId = materializedRepresentation))),
            downloadAttempts = snapshot.downloadAttempts.mapValues { (_, attempt) ->
                if (attempt.downloadId == current.id) attempt.copy(executionBindingId = recordBinding,
                    representationId = recordRepresentation) else attempt
            }, downloadFinalizations = if (intent == null) snapshot.downloadFinalizations else snapshot.downloadFinalizations + (current.id to intent))
    }.getOrThrow()
    val gateway = DownloadTestGateway().apply { denyAcquisition() }
    val a = coordinator(first, gateway)
    a.start()
    val failed = eventually(first, "Materialization must fail: $defect, complete=$complete, published=$published") {
        it.downloads[seeded.record.id]?.let { d -> d.status == DownloadStatus.FAILED && d.leaseOwner == null } == true
    }
    assertEquals(FailureCode.CORRUPT_MEDIA, failed.downloads[seeded.record.id]?.failure?.code, "$defect")
    assertEquals(0, validationCount.get(), "Reject $defect before native validation")
    assertEquals(invalid.downloads[seeded.record.id]?.asset, failed.downloads[seeded.record.id]?.asset, "$defect")
    assertEquals(invalid.downloadFinalizations[seeded.record.id], failed.downloadFinalizations[seeded.record.id], "$defect")
    val retainedPath = seeded.record.asset?.uri ?: seeded.intent!!.let { if (published) it.finalPath else it.stagingPath }
    assertContentEquals(authoredBytes, Files.readAllBytes(Path.of(retainedPath)), "$defect")
    if (!complete && !published) assertFalse(Files.exists(Path.of(seeded.intent!!.finalPath)), "$defect")
    gateway.assertUnused()
}

/** Deliberate real-SQLite fault injection; normal application updates cannot mutate a receipt. */
private fun DownloadFixture.replacePersistedBindingTitle(bindingId: String, title: String) {
    BundledSQLiteDriver().open(databasePath.toString()).use { connection ->
        connection.execSQL("PRAGMA busy_timeout = 5000")
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            connection.prepare("UPDATE playback_bindings SET firstResultTitle = ? WHERE id = ?").use { statement ->
                statement.bindText(1, title)
                statement.bindText(2, bindingId)
                statement.step()
            }
            connection.execSQL("UPDATE store_meta SET revision = revision + 1 WHERE id = 0")
            connection.execSQL("COMMIT")
        } catch (error: Throwable) {
            connection.execSQL("ROLLBACK")
            throw error
        }
    }
}
