package dev.veyra.ui.podcasts

import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The existing podcast downloaded filter and row controls consume the paired typed surface. */
class TypedPodcastDownloadStateTest {
    private val show = PodcastShow("show", "Authored show", "https://example.org/feed",
        episodeIds = listOf("episode"))
    private val episode = MediaItem("episode", "Authored episode", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "episode"), showId = show.id)
    private val snapshot = AppSnapshot(media = mapOf(episode.id to episode), shows = mapOf(show.id to show))

    private fun typed(status: DownloadStatus, ready: Boolean = false) = DownloadSurfaceEntry(
        id = "typed-job", origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = episode.id,
        status = status, bytesDownloaded = 100, totalBytes = 100, ready = ready,
        checking = status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND),
        hasOwnedBytes = ready || status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND),
        assetId = if (ready) "typed-asset" else null, assetSizeBytes = if (ready) 100 else null,
    )

    @Test
    fun downloadedFilterShowsOnlyExactReadyTypedAssetsNotActiveOrNeutralRows() {
        val ready = PodcastShowUiState(show.id, snapshot,
            options = PodcastListOptions(filter = PodcastEpisodeFilter.DOWNLOADED),
            downloads = DownloadSurface(listOf(typed(DownloadStatus.COMPLETE, ready = true))))
        assertEquals(listOf(episode), ready.visibleEpisodes())

        val active = ready.copy(downloads = DownloadSurface(listOf(typed(DownloadStatus.RUNNING))))
        assertTrue(active.visibleEpisodes().isEmpty())

        val neutral = DownloadSurfaceEntry("neutral", DownloadSurfaceOrigin.TYPED_SOURCE,
            displayTitle = "Neutral source request", status = DownloadStatus.COMPLETE,
            bytesDownloaded = 100, totalBytes = 100, ready = true, hasOwnedBytes = true,
            assetId = "neutral-asset", assetSizeBytes = 100)
        val withNeutral = ready.copy(downloads = DownloadSurface(listOf(neutral)))
        assertTrue(withNeutral.visibleEpisodes().isEmpty(),
            "A neutral recording is not an invented episode or catalogue membership")
    }

    @Test
    fun typedReadyAndCheckingRowsExposeOnlyTheirRealJobIdAndNeverInventPlaybackBinding() {
        val readySession = DownloadSurface(listOf(typed(DownloadStatus.COMPLETE, ready = true)))
        val ready = snapshot.podcastEpisodeUiState(episode, downloadSurface = readySession)
        assertTrue(ready.downloaded)
        assertTrue(ready.canPlay)
        assertEquals("typed-job", ready.activeDownload?.id)
        assertEquals(null, ready.nextPlayBinding)
        assertEquals(PodcastAction.RemoveDownload("typed-job"), podcastDownloadAction(ready).action)

        val checkingSession = DownloadSurface(listOf(typed(DownloadStatus.NEEDS_FOREGROUND)))
        val checking = snapshot.copy(settings = dev.veyra.model.AppSettings(offlineOnly = true))
            .podcastEpisodeUiState(episode, downloadSurface = checkingSession)
        assertFalse(checking.downloaded)
        assertFalse(checking.canPlay)
        assertEquals("typed-job", checking.activeDownload?.id)
        assertEquals(null, podcastDownloadAction(checking).action)
        assertEquals(PodcastAction.RemoveDownload("typed-job"),
            podcastRetainedAssetRemovalAction(checking)?.action)
    }
}
