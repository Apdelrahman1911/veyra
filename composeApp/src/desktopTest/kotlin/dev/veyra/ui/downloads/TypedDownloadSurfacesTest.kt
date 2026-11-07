package dev.veyra.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadStatus
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.playback.PlaybackState
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import dev.veyra.ui.music.LibraryScreen
import dev.veyra.ui.music.LibrarySection
import dev.veyra.ui.music.MusicUiAction
import dev.veyra.ui.music.MusicUiOptions
import dev.veyra.ui.music.TrackDetailScreen
import dev.veyra.ui.podcasts.PodcastAction
import dev.veyra.ui.podcasts.PodcastEpisodeRow
import dev.veyra.ui.podcasts.podcastEpisodeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/** Authored typed rows consumed by the existing Compose screens; no I/O or real media claim. */
@OptIn(ExperimentalTestApi::class)
class TypedDownloadSurfacesTest {
    @get:Rule val ui = createComposeRule()

    private val english = AppStrings(LanguagePreference.ENGLISH)
    private val arabic = AppStrings(LanguagePreference.ARABIC)
    private val track = MediaItem("track", "Typed authored track", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "track"), durationMs = 600_000L)
    private val episode = MediaItem("episode", "Typed authored episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "episode"), showId = "show", durationMs = 900_000L)
    private val show = PodcastShow("show", "Typed authored show", "https://example.org/feed",
        episodeIds = listOf(episode.id))
    private val options = MusicUiOptions(downloadableProviders = setOf(SourceProvider.COMMONS, SourceProvider.RSS))

    private fun activeTyped(id: String = "typed-job", bytes: Long = 25,
        total: Long? = 100) = DownloadSurfaceEntry(
        id = id, origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = track.id,
        status = DownloadStatus.RUNNING, bytesDownloaded = bytes, totalBytes = total, updatedEpochMs = 20,
    )

    private fun checkingTyped(id: String = "typed-job") = DownloadSurfaceEntry(
        id = id, origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = track.id,
        status = DownloadStatus.NEEDS_FOREGROUND, bytesDownloaded = 100, totalBytes = 100,
        updatedEpochMs = 20, checking = true, hasOwnedBytes = true, assetId = "typed-asset", assetSizeBytes = 100,
    )

    private fun readyTyped(id: String = "typed-job") = DownloadSurfaceEntry(
        id = id, origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = track.id,
        status = DownloadStatus.COMPLETE, bytesDownloaded = 100, totalBytes = 100,
        updatedEpochMs = 20, ready = true, hasOwnedBytes = true, measuredDurationMs = 12_345,
        assetId = "typed-asset", assetSizeBytes = 100,
    )

    @Test
    fun typedCatalogueRowsUseTheirRealJobIdForPauseCancelAndConfirmedRemoval() {
        val actions = mutableListOf<MusicUiAction>()
        var surface by mutableStateOf(DownloadSurface(listOf(activeTyped())))
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                LibraryScreen(AppSnapshot(media = mapOf(track.id to track)), LibrarySection.DOWNLOADS,
                    { actions += it }, Modifier.size(900.dp, 700.dp), options.copy(downloads = surface))
            }
        }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-typed-job"))
        ui.onNodeWithText(english.downloadStatus(DownloadStatus.RUNNING)).assertIsDisplayed()
        ui.onNodeWithText(english.text(TextKey.PauseDownload)).performClick()
        ui.runOnIdle { surface = DownloadSurface(listOf(activeTyped().copy(status = DownloadStatus.PAUSED))) }
        ui.onNodeWithText(english.text(TextKey.ResumeDownload)).performClick()
        ui.onNodeWithText(english.text(TextKey.CancelDownload)).performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.PauseDownload("typed-job"),
            MusicUiAction.ResumeDownload("typed-job"), MusicUiAction.CancelDownload("typed-job")), actions)

        ui.runOnIdle { surface = DownloadSurface(listOf(checkingTyped())); actions.clear() }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-typed-job"))
        ui.onNodeWithText(english.downloadStatus(DownloadStatus.NEEDS_FOREGROUND)).assertIsDisplayed()
        listOf(TextKey.Play, TextKey.PauseDownload, TextKey.ResumeDownload, TextKey.CancelDownload, TextKey.ReadyOffline).forEach {
            ui.onNodeWithText(english.text(it)).assertDoesNotExist()
        }
        ui.onNodeWithText(english.text(TextKey.RemoveDownload)).performClick()
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.RemoveDownload("typed-job")), actions)
    }

    @Test
    fun trackDetailShowsTypedCheckingInsteadOfReadyAndBecomesHonestlyReadyOnlyFromReadySurface() {
        val actions = mutableListOf<MusicUiAction>()
        var surface by mutableStateOf(DownloadSurface(listOf(checkingTyped())))
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                TrackDetailScreen(AppSnapshot(media = mapOf(track.id to track),
                    settings = dev.veyra.model.AppSettings(offlineOnly = true)), track.id, PlaybackState(),
                    { actions += it }, Modifier.size(900.dp, 720.dp), options.copy(downloads = surface))
            }
        }
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag("track-play"))
        ui.onNodeWithTag("track-play").assertIsNotEnabled()
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag("track-download"))
        ui.onNodeWithText(english.downloadStatus(DownloadStatus.NEEDS_FOREGROUND)).assertIsDisplayed()
        ui.onNodeWithTag("track-downloaded-audio").assertDoesNotExist()
        ui.onNodeWithText(english.text(TextKey.ReadyOffline)).assertDoesNotExist()
        ui.onNodeWithTag("track-download-remove").assertIsDisplayed()

        ui.runOnIdle { surface = DownloadSurface(listOf(readyTyped())) }
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag("track-downloaded-audio"))
        ui.onNodeWithText(english.format(TextKey.DownloadedAudioDuration, english.mediaTime(12_345))).assertIsDisplayed()
        ui.onNode(hasText(english.text(TextKey.Downloaded)) and hasAnyAncestor(hasTestTag("track-downloaded-audio")))
            .assertIsDisplayed()
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag("track-play"))
        ui.onNodeWithTag("track-play").assertIsEnabled()
        ui.onNodeWithTag("track-detail-content").performScrollToNode(hasTestTag("track-download-remove"))
        ui.onNodeWithTag("track-download-remove").performClick()
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.RemoveDownload("typed-job")), actions)
    }

    @Test
    fun typedPodcastRowsUsePairedReadinessAndPhysicalActionsInArabicRtl() {
        val actions = mutableListOf<PodcastAction>()
        val ready = DownloadSurface(listOf(readyTyped().copy(mediaId = episode.id)))
        val running = DownloadSurface(listOf(activeTyped().copy(mediaId = episode.id)))
        val checking = DownloadSurface(listOf(checkingTyped().copy(mediaId = episode.id)))
        var surface by mutableStateOf(running)
        ui.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                VeyraTheme(true, LanguagePreference.ARABIC) {
                    Box(Modifier.size(420.dp, 720.dp).testTag("rtl-typed-podcast")) {
                        PodcastEpisodeRow(AppSnapshot(media = mapOf(episode.id to episode),
                            shows = mapOf(show.id to show), settings = dev.veyra.model.AppSettings(offlineOnly = true))
                            .podcastEpisodeUiState(episode, downloadSurface = surface), { actions += it })
                    }
                }
            }
        }
        ui.onNodeWithText(arabic.downloadStatus(DownloadStatus.RUNNING)).assertIsDisplayed()
        ui.onNodeWithTag("podcast-more-episode").performClick()
        ui.onNodeWithTag("podcast-menu-download").assertTextEquals(arabic.text(TextKey.PauseDownload)).performClick()
        ui.onNodeWithTag("podcast-more-episode").performClick()
        ui.onNodeWithText(arabic.text(TextKey.CancelDownload)).performClick()
        assertEquals(listOf<PodcastAction>(PodcastAction.PauseDownload("typed-job"),
            PodcastAction.CancelDownload("typed-job")), actions)

        ui.runOnIdle { surface = checking; actions.clear() }
        ui.onNodeWithText(arabic.downloadStatus(DownloadStatus.NEEDS_FOREGROUND)).assertIsDisplayed()
        ui.onNodeWithTag("podcast-play-episode").assertIsNotEnabled()
        ui.onNodeWithText(arabic.text(TextKey.Downloaded)).assertDoesNotExist()
        ui.onNodeWithTag("podcast-more-episode").performClick()
        ui.onNodeWithTag("podcast-menu-download").assertIsNotEnabled()
            .assertTextEquals(arabic.downloadStatus(DownloadStatus.NEEDS_FOREGROUND))
        ui.onNodeWithText(arabic.text(TextKey.CancelDownload)).assertDoesNotExist()
        ui.onNodeWithTag("podcast-menu-remove-download").performClick()
        ui.onNodeWithTag("podcast-action-confirm").performClick()
        assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload("typed-job")), actions)

        ui.runOnIdle { surface = ready; actions.clear() }
        ui.onNodeWithTag("podcast-play-episode").assertIsEnabled()
        ui.onNodeWithText(arabic.text(TextKey.Downloaded)).assertIsDisplayed()
        ui.onNodeWithTag("podcast-more-episode").performClick()
        ui.onNodeWithTag("podcast-menu-download").assertTextEquals(arabic.text(TextKey.RemoveDownload)).performClick()
        ui.onNodeWithTag("podcast-action-confirm").performClick()
        assertEquals(listOf<PodcastAction>(PodcastAction.RemoveDownload("typed-job")), actions)
    }

    @Test
    fun neutralTypedRowKeepsCapturedTitleAndNeverOffersPlayOrCatalogueAcquisition() {
        val actions = mutableListOf<MusicUiAction>()
        val neutral = DownloadSurfaceEntry("neutral-job", DownloadSurfaceOrigin.TYPED_SOURCE,
            displayTitle = "Captured neutral request — تسجيل", status = DownloadStatus.RUNNING,
            bytesDownloaded = 12, totalBytes = 100, updatedEpochMs = 30)
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                LibraryScreen(AppSnapshot(), LibrarySection.DOWNLOADS, { actions += it },
                    Modifier.size(900.dp, 700.dp), options.copy(downloads = DownloadSurface(listOf(neutral))))
            }
        }
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-neutral-job"))
        ui.onNodeWithText(neutral.displayTitle!!).assertIsDisplayed()
        ui.onNodeWithText(english.text(TextKey.SourceRecording)).assertIsDisplayed()
        listOf(TextKey.Play, TextKey.Download, TextKey.ReadyOffline).forEach {
            ui.onNodeWithText(english.text(it)).assertDoesNotExist()
        }
        ui.onNodeWithText(english.text(TextKey.PauseDownload)).performClick()
        ui.onNodeWithText(english.text(TextKey.CancelDownload)).performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.PauseDownload("neutral-job"),
            MusicUiAction.CancelDownload("neutral-job")), actions)
    }

    @Test
    fun typedDownloadsFiltersAndStorageUseActualPairedStateWithoutDoubleCounting() {
        val other = track.copy(id = "other", title = "Second typed track")
        val ready = readyTyped()
        val active = activeTyped("active-job").copy(mediaId = other.id)
        val surface = DownloadSurface(listOf(ready, active))
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                LibraryScreen(AppSnapshot(media = mapOf(track.id to track, other.id to other)),
                    LibrarySection.DOWNLOADS, {}, Modifier.size(900.dp, 700.dp), options.copy(downloads = surface))
            }
        }
        ui.onNodeWithText(english.format(TextKey.StorageUsed, english.byteSize(100))).assertIsDisplayed()
        ui.onNodeWithText(english.text(TextKey.Completed)).performClick()
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-typed-job"))
        ui.onNodeWithText(track.title).assertIsDisplayed()
        ui.onNodeWithTag("download-active-job").assertDoesNotExist()
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasText(english.text(TextKey.InProgress)))
        ui.onNodeWithText(english.text(TextKey.InProgress)).performClick()
        ui.onNodeWithTag("downloads-list").performScrollToNode(hasTestTag("download-active-job"))
        ui.onNodeWithTag("download-typed-job").assertDoesNotExist()
    }
}
