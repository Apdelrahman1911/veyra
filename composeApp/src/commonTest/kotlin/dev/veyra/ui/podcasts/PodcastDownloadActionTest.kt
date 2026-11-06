package dev.veyra.ui.podcasts

import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodcastDownloadActionTest {
    private val item = MediaItem("episode", "Authored test episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "episode", enclosureUrl = "https://example.org/audio.mp3"))

    private fun state(status: DownloadStatus, offline: Boolean = false) = PodcastEpisodeUiState(
        item, download = DownloadRecord("download", item.id, status), offlineOnly = offline,
    )

    @Test
    fun runningDownloadsPauseWhilePendingAndVerifyingDownloadsCanCancel() {
        assertEquals(PodcastAction.PauseDownload("download"), podcastDownloadAction(state(DownloadStatus.RUNNING)).action)
        listOf(DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.WAITING_NETWORK,
            DownloadStatus.VERIFYING).forEach { status ->
            assertEquals(PodcastAction.CancelDownload("download"), podcastDownloadAction(state(status)).action)
            assertTrue(podcastDownloadAction(state(status, offline = true)).enabled)
        }
    }

    @Test
    fun retriesAndResumesStayDistinctFromCreatingOrDeletingADownload() {
        listOf(DownloadStatus.PAUSED, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED).forEach { status ->
            assertEquals(PodcastAction.ResumeDownload("download"), podcastDownloadAction(state(status)).action)
            assertFalse(podcastDownloadAction(state(status, offline = true)).enabled)
        }
        assertEquals(TextKey.Retry, podcastDownloadAction(state(DownloadStatus.FAILED)).label)
        assertEquals(PodcastAction.Download(item.id), podcastDownloadAction(state(DownloadStatus.CANCELLED)).action)
        assertEquals(PodcastAction.Download(item.id), podcastDownloadAction(state(DownloadStatus.COMPLETE)).action)
    }

    @Test
    fun onlyACommittedReadyAssetOffersRemovalAndItAlwaysRequiresConfirmation() {
        val incomplete = state(DownloadStatus.COMPLETE)
        assertFalse(podcastDownloadAction(incomplete).requiresRemovalConfirmation)
        val ready = incomplete.copy(download = incomplete.download!!.copy(
            asset = OfflineAsset("asset", item.id, "/owned/audio.mp3", 1_024L)))
        val action = podcastDownloadAction(ready.copy(offlineOnly = true))
        assertEquals(PodcastAction.RemoveDownload("download"), action.action)
        assertTrue(action.enabled)
        assertTrue(action.requiresRemovalConfirmation)
    }

    @Test
    fun newDownloadsRequireAnOnlineResolutionTitleNotAnRssEnclosure() {
        val row = PodcastEpisodeUiState(item)
        assertTrue(podcastDownloadAction(row).enabled)
        assertFalse(podcastDownloadAction(row.copy(offlineOnly = true)).enabled)
        assertTrue(podcastDownloadAction(row.copy(episode = item.copy(source = item.source.copy(enclosureUrl = null)))).enabled)
        assertFalse(podcastDownloadAction(row.copy(episode = item.copy(title = " "))).enabled)
    }

    @Test
    fun playResumeReplayCopyFollowsExplicitProgressStateInBothLanguages() {
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
            val strings = AppStrings(language)
            val row = PodcastEpisodeUiState(item)
            assertEquals(strings.text(TextKey.Play), podcastPlayLabel(row, strings))
            val started = row.copy(progress = MediaProgress(positionMs = 45_000L, durationMs = 45_000L))
            assertEquals(strings.format(TextKey.ResumeAt, strings.mediaTime(45_000L)), podcastPlayLabel(started, strings))
            assertEquals(strings.text(TextKey.Replay), podcastPlayLabel(started.copy(
                progress = started.progress!!.copy(played = true)), strings))
        }
    }
}
