package dev.veyra.ui.podcasts

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodcastBindingTest {
    private val episode = MediaItem("episode", "The complete original episode title", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "guid", feedUrl = "https://example.org/feed.xml"))
    private val old = receipt("old", "An older first result", 1L)
    private val current = receipt("current", "The currently loaded first result", 2L)
    private val newest = receipt("newest", "A later search result", 3L)
    private val download = DownloadRecord("download", episode.id, DownloadStatus.COMPLETE,
        asset = OfflineAsset("asset", episode.id, "/owned/episode.mp3", 128L, executionBindingId = old.id))

    private fun receipt(id: String, title: String, time: Long) = PlaybackBinding(
        id, episode.id, episode.title, FirstResultKind.VIDEO, "video-$id", title,
        selectedVideoId = "video-$id", resolvedEpochMs = time,
    )

    private fun snapshot() = AppSnapshot(media = mapOf(episode.id to episode),
        downloads = mapOf(download.id to download),
        playbackBindings = listOf(old, current, newest).associateBy { it.id })

    @Test
    fun downloadedProvenanceUsesExactAssetReceiptNotTheNewestTitleSearch() {
        val row = snapshot().podcastEpisodeUiState(episode)
        assertEquals(old, row.binding)
        assertEquals(PodcastBindingContext.DOWNLOADED, row.bindingContext)
        assertEquals(episode, row.episode)
    }

    @Test
    fun rootProvidedCurrentReceiptOverridesDownloadedAndNewerHistoricalReceipts() {
        val row = snapshot().podcastEpisodeUiState(episode, currentBinding = current)
        assertEquals(current, row.binding)
        assertEquals(PodcastBindingContext.CURRENT, row.bindingContext)
        assertEquals(episode.title, row.episode.title)
    }

    @Test
    fun anotherItemsCurrentReceiptCannotBeShownForThisEpisode() {
        val row = snapshot().podcastEpisodeUiState(episode, current.copy(mediaId = "other"))
        assertEquals(old, row.binding)
        assertEquals(PodcastBindingContext.DOWNLOADED, row.bindingContext)
    }

    @Test
    fun newestReceiptWithoutExactAssetOwnershipIsAlwaysLabeledPrevious() {
        val row = snapshot().copy(downloads = emptyMap()).podcastEpisodeUiState(episode)
        assertEquals(newest, row.binding)
        assertEquals(PodcastBindingContext.PREVIOUS, row.bindingContext)
    }

    @Test
    fun stagingAndMissingAssetReceiptCannotClaimDownloadedProvenance() {
        val staged = snapshot().copy(downloads = mapOf(download.id to download.copy(status = DownloadStatus.VERIFYING)))
            .podcastEpisodeUiState(episode)
        assertEquals(newest, staged.binding)
        assertEquals(PodcastBindingContext.PREVIOUS, staged.bindingContext)
        val noExactReceipt = snapshot().copy(playbackBindings = mapOf(newest.id to newest)).podcastEpisodeUiState(episode)
        assertEquals(newest, noExactReceipt.binding)
        assertEquals(PodcastBindingContext.PREVIOUS, noExactReceipt.bindingContext)
    }

    @Test
    fun absentOrOtherEpisodeHistoryDoesNotInventAResolution() {
        val snapshot = AppSnapshot(media = mapOf(episode.id to episode),
            playbackBindings = mapOf(newest.id to newest.copy(mediaId = "other")))
        assertNull(snapshot.podcastEpisodeUiState(episode).binding)
    }
}
