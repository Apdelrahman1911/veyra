package dev.veyra.source.execution

import dev.veyra.data.LibraryStore
import dev.veyra.data.createPlatformLibraryStore
import dev.veyra.data.getOrThrow
import dev.veyra.downloads.DownloadFiles
import dev.veyra.model.*
import dev.veyra.playback.AssetValidation
import dev.veyra.playback.AssetValidator
import dev.veyra.playback.EngineCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * REAL Room + filesystem/SHA persistence tests, with authored NON-AUDIO bytes and an explicit
 * fake native validator. These establish resolver/offline policy, NOT audible or native playback.
 */
class AppSourceResolverRoomTest {
    private class Fixture(val directory: Path) {
        val item = executionTestItem()
        val files = DownloadFiles(directory.resolve("media").toString())
        var store: LibraryStore = createPlatformLibraryStore(directory.resolve("library").toString())
        val binding = PlaybackBinding(
            "authored-execution-receipt", item.id, item.title, FirstResultKind.VIDEO,
            FIRST_VIDEO_ID, "Authored selected-video metadata", "https://www.youtube.com/watch?v=$FIRST_VIDEO_ID",
            selectedVideoId = FIRST_VIDEO_ID, resolvedEpochMs = 10_000L,
        )
        val path get() = files.finalPath(item.id, 1L, "audio/mpeg")
        lateinit var asset: OfflineAsset
        lateinit var record: DownloadRecord
        val gateway = ExecutionTestGateway().apply {
            first = { error("No network/source search is permitted in an offline/corrupt-asset test") }
            execute = { _, _, _ -> error("No remote execution is permitted") }
        }
        var validations = 0
        var onValidation: suspend () -> Unit = {}
        var nativeResult: AssetValidation = AssetValidation.Valid(31_234L)
        fun resolver() = AppSourceResolver(store, files, AssetValidator {
            validations++
            onValidation()
            nativeResult
        }, gateway, ExecutionTestClock())

        suspend fun seed() {
            files.initialize()
            store.upsertCatalog(listOf(item)).getOrThrow()
            store.recordPlaybackBinding(binding).getOrThrow()
            Files.createDirectories(Path.of(path.parent!!.toString()))
            val bytes = "Authored byte-validation fixture. This is NOT AUDIO.".encodeToByteArray()
            Files.write(Path.of(path.toString()), bytes)
            asset = OfflineAsset(
                "authored-local-asset", item.id, path.toString(), bytes.size.toLong(),
                mimeType = "audio/mpeg", codecs = "mp3", sha256 = files.sha256(path),
                representationId = "authored-kmpyt-representation", validatorGeneration = 1,
                completedEpochMs = 11_000L, executionBindingId = binding.id, measuredDurationMs = 30_000L,
            )
            record = DownloadRecord(
                "authored-download", item.id, DownloadStatus.COMPLETE,
                bytesDownloaded = asset.sizeBytes, totalBytes = asset.sizeBytes, asset = asset,
                representationId = asset.representationId, validatorGeneration = 1,
                updatedEpochMs = 11_000L, executionBindingId = binding.id,
            )
            store.update { it.copy(downloads = it.downloads + (record.id to record)) }.getOrThrow()
        }

        suspend fun reopen() {
            store.close()
            store = createPlatformLibraryStore(directory.resolve("library").toString())
            store.initialize().getOrThrow()
        }

        fun assertNoGateway() {
            assertTrue(gateway.queries.isEmpty())
            assertTrue(gateway.resolutions.isEmpty())
            assertEquals(0, gateway.downloads)
        }
    }

    private fun workDirectory(): Path {
        val root = System.getenv("VEYRA_PROJECT_ROOT")?.let(Path::of)
            ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val parent = root.resolve(".work/execution-resolver-tests")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, "room-")
    }

    private fun checkCase(block: suspend Fixture.() -> Unit) = runBlocking {
        withTimeout(30_000L) {
            val fixture = Fixture(workDirectory())
            try { fixture.seed(); fixture.block() } finally { fixture.store.close() }
        }
    }

    @Test fun roomCloseAndReopenValidatesHashAndNativeContractWithoutAnyGateway() = checkCase {
        store.update { it.copy(settings = it.settings.copy(offlineOnly = true)) }.getOrThrow()
        reopen()
        val result = resolver().resolve(item, EngineCapabilities(), offlineOnly = true)
        assertTrue(result.isLocal)
        assertEquals(asset.uri, result.uri)
        assertEquals(item, result.item)
        assertEquals(binding, result.executionBinding)
        assertEquals(31_234L, result.durationMs)
        assertNotEquals(item.durationMs, result.durationMs)
        assertEquals(1, validations)
        assertNoGateway()
        Files.newInputStream(directory.resolve("library/veyra.db")).use {
            assertEquals("SQLite format 3\u0000", it.readNBytes(16).decodeToString())
        }
    }

    @Test fun publisherTitleCorrectionPreservesHistoricalOfflineReceiptAndSelectedVideo() = checkCase {
        val corrected = item.copy(title = "Corrected full publisher title — العنوان المصحح")
        store.upsertCatalog(listOf(corrected)).getOrThrow()
        reopen()
        val result = resolver().resolve(corrected, EngineCapabilities(), offlineOnly = true)
        assertTrue(result.isLocal)
        assertEquals(corrected, result.item)
        assertEquals(item.title, result.executionBinding?.queryTitle)
        assertEquals(FIRST_VIDEO_ID, result.executionBinding?.selectedVideoId)
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        assertNoGateway()
    }

    @Test fun sameSizeCorruptionMarksFailedDurablyAndNeverFallsBackEvenOnline() = checkCase {
        val data = Files.readAllBytes(Path.of(asset.uri))
        data[0] = (data[0].toInt() xor 1).toByte()
        Files.write(Path.of(asset.uri), data)
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertEquals(0, validations)
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        reopen()
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertNoGateway()
    }

    @Test fun missingFileLosesReadyStatusWithoutRemoteReplacement() = checkCase {
        Files.delete(Path.of(asset.uri))
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities())
        }.failure.code)
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertEquals(0, validations)
        assertNoGateway()
    }

    @Test fun nativeValidatorRejectionIsNotOverriddenByMatchingByteHash() = checkCase {
        nativeResult = AssetValidation.Invalid(AppFailure(FailureCode.CORRUPT_MEDIA, "authored_native_rejection"))
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities())
        }.failure.code)
        assertEquals(1, validations)
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertNoGateway()
    }

    @Test fun mismatchedAssetAndDownloadReceiptsFailBeforeFileOrNativeUse() = checkCase {
        val another = binding.copy(id = "another-execution", firstResultId = LATER_VIDEO_ID,
            selectedVideoId = LATER_VIDEO_ID, firstResultUrl = "https://www.youtube.com/watch?v=$LATER_VIDEO_ID")
        store.recordPlaybackBinding(another).getOrThrow()
        store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads +
            (record.id to record.copy(asset = asset.copy(executionBindingId = another.id)))) }.getOrThrow()
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = true)
        }.failure.code)
        assertEquals(0, validations)
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertNoGateway()
    }

    @Test fun legacyUnboundLocalBytesCannotBypassFirstResultPolicy() = checkCase {
        store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads +
            (record.id to record.copy(executionBindingId = null, asset = asset.copy(executionBindingId = null)))) }.getOrThrow()
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = true)
        }.failure.code)
        assertEquals(0, validations)
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertNoGateway()
    }

    private fun rejectRepresentationAfterRoomRestart(assetRepresentation: String?, recordRepresentation: String?) = checkCase {
        val invalidAsset = asset.copy(representationId = assetRepresentation)
        store.update { snapshot -> snapshot.copy(
            settings = snapshot.settings.copy(offlineOnly = true),
            downloads = snapshot.downloads + (record.id to record.copy(
                asset = invalidAsset, representationId = recordRepresentation)),
        ) }.getOrThrow()
        reopen()
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = true)
        }.failure.code)
        assertEquals(0, validations, "Malformed representation provenance must fail before native use")
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        assertEquals(invalidAsset, store.state.value.downloads.getValue(record.id).asset)
        assertTrue(Files.isRegularFile(Path.of(asset.uri)), "Keep owned bytes for explicit recovery/removal")
        reopen()
        assertEquals(DownloadStatus.FAILED, store.state.value.downloads.getValue(record.id).status)
        store.update { it.copy(settings = it.settings.copy(offlineOnly = false)) }.getOrThrow()
        assertEquals(FailureCode.CORRUPT_MEDIA, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertEquals(0, validations)
        assertEquals(binding, store.state.value.playbackBindings[binding.id])
        assertNoGateway()
    }

    @Test fun missingAssetRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() {
        rejectRepresentationAfterRoomRestart(null, null)
    }

    @Test fun blankAssetRepresentationIsRejectedEvenWhenRecordMatchesAfterRoomRestart() {
        for (blank in listOf("", " \t\n")) rejectRepresentationAfterRoomRestart(blank, blank)
    }

    @Test fun missingRecordRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() {
        rejectRepresentationAfterRoomRestart("authored-kmpyt-representation", null)
    }

    @Test fun mismatchedRecordRepresentationIsRejectedBeforeNativeValidationAfterRoomRestart() {
        rejectRepresentationAfterRoomRestart("authored-kmpyt-representation", "authored-other-representation")
    }

    @Test fun unsupportedEngineDoesNotRelabelAValidArtifactAsCorrupt() = checkCase {
        assertEquals(FailureCode.UNSUPPORTED_CODEC, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(mimeTypes = setOf("audio/mp4"), codecs = setOf("aac")), offlineOnly = true)
        }.failure.code)
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        nativeResult = AssetValidation.Valid()
        assertEquals(asset.measuredDurationMs, resolver().resolve(item, EngineCapabilities(), offlineOnly = true).durationMs)
        assertNoGateway()
    }

    @Test fun aDifferentRequestedSessionCannotSilentlyUseCompletedAudioFromAnotherBinding() = checkCase {
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = true, reuseBindingId = "different-session")
        }.failure.code)
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        assertEquals(0, validations)
        assertNoGateway()
    }

    @Test fun roomRejectsOriginalSourceReassignmentWithoutChangingHistoricalOfflineReceipt() = checkCase {
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            store.upsertCatalog(listOf(item.copy(source = item.source.copy(canonicalId = "another-original")))).getOrThrow()
        }.failure.code)
        reopen()
        assertEquals(item, store.state.value.media[item.id])
        assertEquals(binding, store.state.value.playbackBindings[binding.id])
        assertEquals(binding, resolver().resolve(item, EngineCapabilities(), offlineOnly = true).executionBinding)
        assertNoGateway()
    }

    @Test fun durableCleanupIntentBlocksNativeReopenAndRemoteFallbackWithoutCorruptingTheAsset() = checkCase {
        store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads +
            (record.id to record.copy(cleanupPending = true))) }.getOrThrow()
        reopen()
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        assertTrue(Files.isRegularFile(Path.of(asset.uri)))
        assertEquals(0, validations)
        assertNoGateway()
    }

    @Test fun cleanupBeginningDuringValidationCannotReturnTheArtifactForNativeHandoff() = checkCase {
        onValidation = {
            store.update { snapshot -> snapshot.copy(downloads = snapshot.downloads +
                (record.id to record.copy(cleanupPending = true))) }.getOrThrow()
        }
        assertEquals(FailureCode.INVALID_STATE, assertFailsWith<VeyraException> {
            resolver().resolve(item, EngineCapabilities(), offlineOnly = false)
        }.failure.code)
        assertEquals(DownloadStatus.COMPLETE, store.state.value.downloads.getValue(record.id).status)
        assertEquals(1, validations)
        assertNoGateway()
    }
}
