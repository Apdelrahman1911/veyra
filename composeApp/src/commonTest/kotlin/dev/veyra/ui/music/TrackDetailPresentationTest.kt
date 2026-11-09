package dev.veyra.ui.music

import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackDetailPresentationTest {
    private val track = MediaItem("track", "Complete requested title — العنوان الكامل", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "track"), durationMs = 600_000L)
    private fun receipt(id: String, at: Long) = PlaybackBinding(id, track.id, track.title,
        FirstResultKind.VIDEO, "video-$id", "Resolved $id", selectedVideoId = "video-$id", resolvedEpochMs = at)
    private fun asset(bindingId: String?) = OfflineAsset("asset", track.id, "file:///owned/audio.m4a", 1_234,
        executionBindingId = bindingId, measuredDurationMs = 182_000L)

    @Test fun currentAndDownloadedRecordingsUseTheirExactOwnersNotTheNewestSearch() {
        val current = receipt("current", 2L)
        val downloaded = receipt("downloaded", 1L)
        val later = receipt("later", 3L)
        val record = DownloadRecord("download", track.id, DownloadStatus.COMPLETE, asset = asset(downloaded.id), executionBindingId = later.id)
        val snapshot = AppSnapshot(downloads = mapOf(record.id to record), playbackBindings = listOf(current, downloaded, later).associateBy { it.id })
        val presentation = trackDetailPresentation(track, snapshot, PlaybackState(currentItem = track, currentEntryId = "occurrence", executionBinding = current, durationMs = 197_000L))
        assertTrue(presentation.isCurrent)
        assertEquals(current, presentation.currentBinding)
        assertEquals(downloaded, presentation.downloadedBinding)
        assertEquals(later, presentation.previousBinding)
        assertEquals(197_000L, presentation.currentDurationMs)
        assertEquals(182_000L, presentation.downloadedDurationMs)
    }

    @Test fun unownedReceiptsArePreviousSearchesNeverEvidenceOfCurrentOrDownloadedAudio() {
        val previous = receipt("previous", 5)
        val presentation = trackDetailPresentation(track,
            AppSnapshot(playbackBindings = mapOf(previous.id to previous)),
            PlaybackState(currentItem = track.copy(id = "another"), currentEntryId = "other", executionBinding = previous, durationMs = 99_000L))
        assertFalse(presentation.isCurrent)
        assertNull(presentation.currentBinding)
        assertNull(presentation.downloadedBinding)
        assertNull(presentation.currentDurationMs)
        assertNull(presentation.downloadedDurationMs)
        assertEquals(previous, presentation.previousBinding)
    }

    @Test fun unknownNativeAndMeasuredDurationsNeverFallBackToTheListing() {
        val record = DownloadRecord("download", track.id, DownloadStatus.COMPLETE,
            asset = asset(null).copy(measuredDurationMs = -1L))
        val presentation = trackDetailPresentation(track, AppSnapshot(downloads = mapOf(record.id to record)),
            PlaybackState(currentItem = track, currentEntryId = "occurrence"))
        assertNull(presentation.currentDurationMs)
        assertNull(presentation.downloadedDurationMs)
        assertEquals(600_000L, track.durationMs)
    }

    @Test fun readyRequiresFinalStatusAndAPlausibleAssetForTheSameItem() {
        val ready = DownloadRecord("download", track.id, DownloadStatus.COMPLETE, asset = asset(null))
        assertTrue(ready.isReady())
        listOf(
            ready.copy(status = DownloadStatus.VERIFYING),
            ready.copy(status = DownloadStatus.NEEDS_FOREGROUND),
            ready.copy(asset = null),
            ready.copy(asset = ready.asset!!.copy(mediaId = "another")),
            ready.copy(asset = ready.asset!!.copy(sizeBytes = 0)),
            ready.copy(asset = ready.asset!!.copy(uri = "  ")),
        ).forEach { assertFalse(it.isReady()) }
    }

    @Test fun recheckingMaterializedAssetsNeverUsesActiveTransferControlsOrClaimsReadiness() {
        val record = DownloadRecord("download", track.id, DownloadStatus.VERIFYING, asset = asset(null))
        listOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND).forEach { status ->
            val projected = record.copy(status = status)
            assertTrue(projected.isRecheckingAsset())
            assertFalse(projected.isReady())
            assertFalse(projected.copy(asset = null).isRecheckingAsset(), "Asset-null finalization remains a transfer")
            assertFalse(projected.copy(cleanupPending = true).isRecheckingAsset())
        }
        assertFalse(record.copy(status = DownloadStatus.FAILED).isRecheckingAsset(), "Corrupt files still support retry")
        assertFalse(record.copy(status = DownloadStatus.COMPLETE).isRecheckingAsset())
        assertFalse((null as DownloadRecord?).isRecheckingAsset())
    }

    @Test fun receiptsForOtherMediaCannotBeBorrowedEvenThroughADanglingAssetReference() {
        val foreign = receipt("foreign", 1L).copy(mediaId = "another")
        val record = DownloadRecord("download", track.id, DownloadStatus.COMPLETE, asset = asset(foreign.id))
        val presentation = trackDetailPresentation(track,
            AppSnapshot(downloads = mapOf(record.id to record), playbackBindings = mapOf(foreign.id to foreign)),
            PlaybackState(currentItem = track, currentEntryId = "occurrence", executionBinding = foreign))
        assertNull(presentation.currentBinding)
        assertNull(presentation.downloadedBinding)
        assertNull(presentation.previousBinding)
    }

    @Test fun verifyingAssetsCannotSupplyReadyIdentityOrMeasuredDuration() {
        val binding = receipt("pending", 1L)
        val record = DownloadRecord("download", track.id, DownloadStatus.VERIFYING, asset = asset(binding.id))
        val presentation = trackDetailPresentation(track,
            AppSnapshot(downloads = mapOf(record.id to record), playbackBindings = mapOf(binding.id to binding)), PlaybackState())
        assertNull(presentation.downloadedBinding)
        assertNull(presentation.downloadedDurationMs)
        assertEquals(binding, presentation.previousBinding)
    }

    @Test fun sourceDisplayOmitsPrivateLocatorsAndExternalIntentsNeverUseFileSchemes() {
        assertEquals("example.org", musicSourceHost("https://example.org/private/path?token=private#fragment"))
        assertNull(musicSourceHost("https://user:secret@example.org/private"))
        assertNull(musicSourceHost("https://example.org\\private"))
        assertNull(musicSourceHost("file:///private/audio.m4a"))
        assertNull(musicSourcePage("http://example.org/page", "file:///private/audio.m4a"))
        assertEquals("https://example.org/page?q=full-title", musicSourcePage("", "https://example.org/page?q=full-title"))
    }
}
