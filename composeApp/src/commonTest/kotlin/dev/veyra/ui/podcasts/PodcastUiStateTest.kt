package dev.veyra.ui.podcasts

import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PodcastUiStateTest {
    private val show = PodcastShow(
        id = "show", title = "A publisher-owned show", feedUrl = "https://example.org/feed.xml",
        publisher = "Fixture publisher", episodeIds = listOf("new", "started", "played"),
    )

    private fun episode(id: String, date: Long? = null, showId: String = show.id) = MediaItem(
        id = id,
        title = "Episode $id",
        kind = MediaKind.EPISODE,
        source = SourceRef(SourceProvider.RSS, id, feedUrl = show.feedUrl, episodeGuid = id,
            enclosureUrl = "https://example.org/$id.mp3"),
        showId = showId,
        durationMs = 7_200_000L,
        publishedEpochMs = date,
    )

    @Test
    fun continueAndNewDoNotConflatePartialPlayedUnfollowedOrMusicItems() {
        val media = listOf(episode("new", 30L), episode("started", 20L), episode("played", 10L),
            episode("elsewhere", 40L, "other"), episode("song").copy(kind = MediaKind.MUSIC))
        val snapshot = AppSnapshot(
            media = media.associateBy { it.id }, shows = mapOf(show.id to show), followedShowIds = setOf(show.id),
            progress = mapOf("started" to MediaProgress(positionMs = 45_000L, updatedEpochMs = 5L),
                "played" to MediaProgress(positionMs = 20_000L, played = true)),
        )
        assertEquals(listOf("started"), PodcastsUiState(snapshot).continueEpisodes().map { it.id })
        assertEquals(listOf("new"), PodcastsUiState(snapshot).newEpisodes().map { it.id })
        assertEquals(listOf(show), PodcastsUiState(snapshot).followedShows())
        assertEquals(emptyList(), PodcastsUiState(snapshot.copy(followedShowIds = emptySet())).newEpisodes())
    }

    @Test
    fun markPlayedAndDownloadFlagsDoNotEraseOrInventProgress() {
        val item = episode("started")
        val row = PodcastEpisodeUiState(item, progress = MediaProgress(positionMs = 90_000L, played = true))
        assertTrue(row.played)
        assertFalse(row.inProgress)
        assertFalse(row.canResume)
        assertEquals(90_000L, row.positionMs)
        assertTrue(row.copy(progress = row.progress!!.copy(played = false)).canResume)
        assertFalse(row.downloaded)
    }

    @Test
    fun durationEstimatesAndEndPositionDoNotConfirmCompletion() {
        val item = episode("duration-mismatch").copy(durationMs = 30_000L)
        val progress = MediaProgress(positionMs = 45_000L, durationMs = 60_000L)
        val row = PodcastEpisodeUiState(item, progress = progress)
        assertEquals(60_000L, row.progressDurationMs)
        assertEquals(30_000L, row.listingDurationMs)
        assertTrue(row.inProgress)
        assertTrue(row.canResume)
        assertFalse(row.played)

        val atEnd = row.copy(progress = progress.copy(positionMs = 60_000L))
        assertTrue(atEnd.inProgress)
        assertTrue(atEnd.canResume)
        val beyondFeedEstimate = row.copy(progress = progress.copy(durationMs = null))
        assertTrue(beyondFeedEstimate.canResume)
        assertNull(beyondFeedEstimate.progressDurationMs)
        assertFalse(atEnd.copy(progress = atEnd.progress!!.copy(played = true)).canResume)

        val snapshot = AppSnapshot(media = mapOf(item.id to item), progress = mapOf(item.id to atEnd.progress!!))
        assertEquals(listOf(item.id), PodcastsUiState(snapshot).continueEpisodes().map { it.id })
    }

    @Test
    fun completeStatusWithoutCommittedAssetDoesNotEnableOfflinePlayback() {
        val item = episode("new")
        val record = DownloadRecord("download", item.id, status = DownloadStatus.COMPLETE)
        val row = PodcastEpisodeUiState(item, download = record, offlineOnly = true)
        assertFalse(row.downloaded)
        assertFalse(row.canPlay)
        val asset = OfflineAsset("asset", item.id, "/owned/file.mp3", 42L)
        val readyRecord = record.copy(asset = asset)
        val ready = row.copy(download = readyRecord)
        assertTrue(ready.downloaded)
        assertTrue(ready.canPlay)
        assertFalse(ready.copy(download = readyRecord.copy(status = DownloadStatus.VERIFYING)).downloaded)
        assertFalse(ready.copy(download = readyRecord.copy(asset = asset.copy(mediaId = "wrong"))).downloaded)
        assertFalse(ready.copy(download = readyRecord.copy(asset = asset.copy(sizeBytes = 0L))).downloaded)
        assertFalse(ready.copy(download = readyRecord.copy(asset = asset.copy(uri = ""))).downloaded)
        // Publisher RSS audio is not played directly. A full title enables central first-result resolution.
        assertTrue(row.copy(episode = item.copy(source = item.source.copy(enclosureUrl = null)), offlineOnly = false).canPlay)
        assertFalse(row.copy(episode = item.copy(title = " "), offlineOnly = false).canPlay)
    }

    @Test
    fun viewSortingFilteringNeverMutatesQueueOrTreatsUnknownDatesAsNewest() {
        val media = listOf(episode("later", 20L), episode("earlier", 10L), episode("undated"))
        val queue = QueueSnapshot(listOf(QueueEntry("occurrence", "later")), currentEntryId = "occurrence")
        val snapshot = AppSnapshot(media = media.associateBy { it.id }, shows = mapOf(show.id to show),
            progress = mapOf("earlier" to MediaProgress(positionMs = 1_000L)),
            queues = mapOf(QueueContext.PODCAST to queue))
        val state = PodcastShowUiState(show.id, snapshot)
        assertEquals(listOf("later", "earlier", "undated"), state.visibleEpisodes().map { it.id })
        assertEquals(listOf("earlier", "later", "undated"), state.copy(options =
            PodcastListOptions(sort = PodcastEpisodeSort.OLDEST_FIRST)).visibleEpisodes().map { it.id })
        assertEquals(listOf("earlier"), state.copy(options =
            PodcastListOptions(filter = PodcastEpisodeFilter.IN_PROGRESS)).visibleEpisodes().map { it.id })
        assertEquals(queue, state.snapshot.queue(QueueContext.PODCAST))
    }

    @Test
    fun previouslyConsumedOccurrenceDoesNotPreventAddingEpisodeAgain() {
        val first = episode("earlier")
        val second = episode("later")
        val snapshot = AppSnapshot(media = mapOf(first.id to first, second.id to second), queues = mapOf(
            QueueContext.PODCAST to QueueSnapshot(listOf(QueueEntry("old", first.id), QueueEntry("now", second.id)), "now"),
        ))
        assertFalse(snapshot.podcastEpisodeUiState(first).queued)
        assertTrue(snapshot.podcastEpisodeUiState(second).queued)
    }

    @Test
    fun notesAndRetainedEpisodesRemainVisibleWhenLatestFeedDropsAnItem() {
        val retained = episode("retained")
        val snapshot = AppSnapshot(media = mapOf(retained.id to retained), shows = mapOf(show.id to
            show.copy(episodeIds = emptyList(), lastRefreshEpochMs = 42L)))
        assertEquals(listOf(retained), PodcastShowUiState(show.id, snapshot).visibleEpisodes())
        assertTrue(snapshot.podcastEpisodeUiState(retained).noLongerInFeed)
        assertEquals(emptyList(), PodcastsUiState(snapshot.copy(followedShowIds = setOf(show.id))).newEpisodes())
    }

    @Test
    fun downloadedFilterExcludesStagingAndMissingAssetsButRetainsIndependentSavedState() {
        val ready = episode("ready")
        val staged = episode("staged")
        val noAsset = episode("no-asset")
        val records = listOf(
            DownloadRecord("ready-file", ready.id, DownloadStatus.COMPLETE,
                asset = OfflineAsset("ready-asset", ready.id, "/owned/ready.mp3", 100L)),
            DownloadRecord("staged-file", staged.id, DownloadStatus.VERIFYING,
                asset = OfflineAsset("staged-asset", staged.id, "/owned/staging.mp3", 100L)),
            DownloadRecord("missing-file", noAsset.id, DownloadStatus.COMPLETE),
        )
        val snapshot = AppSnapshot(media = listOf(ready, staged, noAsset).associateBy { it.id },
            savedEpisodeIds = setOf(staged.id), downloads = records.associateBy { it.id })
        val state = PodcastShowUiState(show.id, snapshot, PodcastListOptions(filter = PodcastEpisodeFilter.DOWNLOADED))
        assertEquals(listOf(ready), state.visibleEpisodes())
        assertTrue(snapshot.podcastEpisodeUiState(staged).saved)
        assertFalse(snapshot.podcastEpisodeUiState(ready).saved)
    }
}
