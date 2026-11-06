package dev.veyra.ui.music

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.Attribution
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FirstResultKind
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.Playlist
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI fixtures test explicit intents and disclosure, not real recording/source equivalence. */
class TrackDetailFlowsTest {
    @get:Rule val ui = createComposeRule()
    private val strings = AppStrings(LanguagePreference.ENGLISH)
    private val track = MediaItem("track", "Complete requested title — العنوان الكامل", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "track", "https://example.org/source-page"), creator = "Authored creator",
        durationMs = 600_000L, album = "Authored album", description = "Notes about the original listing only.",
        attribution = Attribution("Original attribution", "https://example.org/source-page", "CC0 — listing only", "https://example.org/license"))
    private val options = MusicUiOptions(downloadableProviders = setOf(SourceProvider.COMMONS))
    private fun snapshot() = AppSnapshot(media = mapOf(track.id to track))
    private fun receipt(id: String, epoch: Long) = PlaybackBinding(id, track.id, track.title,
        FirstResultKind.VIDEO, "video-$id", "Actual recording $id", selectedVideoId = "video-$id", resolvedEpochMs = epoch)

    @Test fun openingDetailsDoesNotPlayAndExplicitPlayRetainsTheCompleteRequestedItem() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot(), track.id, PlaybackState(), { actions += it }, Modifier.size(900.dp, 720.dp), options)
        } }
        ui.onNodeWithTag("track-title").assertTextEquals(track.title)
        assertTrue(actions.isEmpty())
        scrollTo("track-play")
        ui.onNodeWithTag("track-play").performClick()
        assertEquals(listOf(MusicUiAction.Play(track)), actions)
    }

    @Test fun currentTrackControlsPreserveTheCurrentOccurrenceAndLibraryActionsAreIndependent() {
        val actions = mutableListOf<MusicUiAction>()
        val state = PlaybackState(currentItem = track, currentEntryId = "exact-occurrence", phase = PlaybackPhase.BUFFERING, desiredPlaying = true)
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot(), track.id, state, { actions += it }, Modifier.size(900.dp, 720.dp), options)
        } }
        scrollTo("track-play")
        ui.onNodeWithTag("track-play").performClick()
        ui.onNodeWithTag("track-favorite").performClick()
        ui.onNodeWithTag("track-play-next").performClick()
        ui.onNodeWithTag("track-enqueue").performClick()
        assertEquals(listOf(MusicUiAction.TogglePlayPause, MusicUiAction.ToggleFavorite(track),
            MusicUiAction.Enqueue(track, true), MusicUiAction.Enqueue(track, false)), actions)
    }

    @Test fun currentDownloadedAndPreviousRecordingReceiptsAreDistinctAndDurationsNeverBorrowTheListing() {
        val current = receipt("current", 2L)
        val downloaded = receipt("downloaded", 1L)
        val later = receipt("previous-search", 3L)
        val record = DownloadRecord("download", track.id, DownloadStatus.COMPLETE, asset = OfflineAsset("asset", track.id,
            "file:///owned/audio.m4a", 1234, executionBindingId = downloaded.id, measuredDurationMs = 182_000L), executionBindingId = later.id)
        val snapshot = snapshot().copy(downloads = mapOf(record.id to record), playbackBindings = listOf(current, downloaded, later).associateBy { it.id })
        val state = PlaybackState(currentItem = track, currentEntryId = "occurrence", executionBinding = current, durationMs = 197_000L)
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot, track.id, state, {}, Modifier.size(900.dp, 720.dp), options)
        } }
        ui.onNodeWithText(strings.format(TextKey.OriginalDuration, strings.mediaTime(600_000L))).assertIsDisplayed()
        scrollTo("track-current-audio")
        ui.onNodeWithText(strings.format(TextKey.CurrentAudioDuration, strings.mediaTime(197_000L))).assertIsDisplayed()
        ui.onNodeWithText(current.firstResultTitle).assertIsDisplayed()
        scrollTo("track-downloaded-audio")
        ui.onNodeWithText(strings.format(TextKey.DownloadedAudioDuration, strings.mediaTime(182_000L))).assertIsDisplayed()
        ui.onNodeWithText(downloaded.firstResultTitle).assertIsDisplayed()
        scrollTo("track-previous-audio")
        ui.onNodeWithText(strings.text(TextKey.PreviousAudioResolution)).assertIsDisplayed()
        ui.onNodeWithText(later.firstResultTitle).assertIsDisplayed()
        scrollTo("track-attribution")
        ui.onNodeWithText(strings.text(TextKey.ResolvedAudioRightsNotice)).assertIsDisplayed()
    }

    @Test fun offlineDetailsKeepReferenceActionsButDisableUnavailableAudioAndExternalPages() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot().copy(settings = AppSettings(offlineOnly = true)), track.id, PlaybackState(),
                { actions += it }, Modifier.size(900.dp, 720.dp), options)
        } }
        scrollTo("track-play")
        ui.onNodeWithTag("track-play").assertIsNotEnabled()
        ui.onNodeWithTag("track-favorite").performClick()
        ui.onNodeWithTag("track-enqueue").performClick()
        scrollTo("track-download-start")
        ui.onNodeWithTag("track-download-start").assertIsNotEnabled()
        scrollTo("track-source-link")
        ui.onNodeWithTag("track-source-link").assertIsNotEnabled()
        scrollTo("track-license-link")
        ui.onNodeWithTag("track-license-link").assertIsNotEnabled()
        assertEquals(listOf(MusicUiAction.ToggleFavorite(track), MusicUiAction.Enqueue(track, false)), actions)
    }

    @Test fun verifyingIsNotReadyAndCancellationUsesTheDownloadId() {
        val actions = mutableListOf<MusicUiAction>()
        val record = DownloadRecord("job-id", track.id, DownloadStatus.VERIFYING, bytesDownloaded = 100, totalBytes = 100,
            asset = OfflineAsset("not-final", track.id, "file:///not-final/audio.m4a", 100))
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot().copy(downloads = mapOf(record.id to record)), track.id, PlaybackState(),
                { actions += it }, Modifier.size(900.dp, 720.dp), options)
        } }
        scrollTo("track-download")
        ui.onNodeWithText(strings.text(TextKey.DownloadVerifying)).assertIsDisplayed()
        ui.onNodeWithTag("track-downloaded-audio").assertDoesNotExist()
        ui.onNodeWithTag("track-download-cancel").performClick()
        assertEquals(listOf(MusicUiAction.CancelDownload(record.id)), actions)
    }

    @Test fun playlistAndPublicPageIntentsDoNotStartPlayback() {
        val actions = mutableListOf<MusicUiAction>()
        val playlist = Playlist("playlist", "Authored playlist")
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(snapshot().copy(playlists = listOf(playlist)), track.id, PlaybackState(),
                { actions += it }, Modifier.size(900.dp, 720.dp), options)
        } }
        scrollTo("track-playlist")
        ui.onNodeWithTag("track-playlist").performClick()
        ui.onNodeWithText(playlist.name).performClick()
        scrollTo("track-source-link")
        ui.onNodeWithTag("track-source-link").performClick()
        scrollTo("track-license-link")
        ui.onNodeWithTag("track-license-link").performClick()
        assertEquals(listOf(MusicUiAction.AddToPlaylist(playlist.id, track),
            MusicUiAction.OpenSourcePage("https://example.org/source-page"), MusicUiAction.OpenSourcePage("https://example.org/license")), actions)
    }

    @Test fun missingDetailsHaveAReachableBackActionAndNoPlaybackIntent() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) {
            TrackDetailScreen(AppSnapshot(), "missing", PlaybackState(), { actions += it }, Modifier.size(320.dp, 500.dp))
        } }
        ui.onNodeWithTag("track-missing").assertIsDisplayed()
        ui.onNodeWithTag("track-play").assertDoesNotExist()
        ui.onNodeWithTag("track-back").performClick()
        assertEquals(listOf(MusicUiAction.NavigateBack), actions)
    }

    @Test fun narrowLargeArabicDetailsKeepWrappedActionsAndFixedBackReachable() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.75f)) {
            VeyraTheme(true, LanguagePreference.ARABIC) {
                TrackDetailScreen(snapshot(), track.id, PlaybackState(), { actions += it }, Modifier.size(320.dp, 600.dp), options)
            }
        } }
        ui.onNodeWithTag("track-back").assertIsDisplayed()
        capture("track-ar-dark-large-top.png")
        scrollTo("track-enqueue")
        ui.onNodeWithTag("track-enqueue").assertIsDisplayed().performClick()
        capture("track-ar-dark-large-actions.png")
        ui.onNodeWithTag("track-back").assertIsDisplayed().performClick()
        assertEquals(listOf(MusicUiAction.Enqueue(track, false), MusicUiAction.NavigateBack), actions)
    }

    private fun scrollTo(tag: String) { ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag(tag)) }

    private fun capture(name: String) {
        val bitmap = ui.onNodeWithTag("track-detail").captureToImage()
        val pixels = bitmap.toPixelMap()
        val output = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val directory = File("build/test-evidence/music").apply { mkdirs() }
        ImageIO.write(output, "png", File(directory, name))
    }
}
