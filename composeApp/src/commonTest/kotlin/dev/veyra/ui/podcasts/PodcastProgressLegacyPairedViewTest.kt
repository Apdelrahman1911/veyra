package dev.veyra.ui.podcasts

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.PlaybackAcquisitionView
import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Production-shaped paired view with only a schema2 progress row. The nonnull projection must
 * preserve the genuine legacy receipt comparison; it must not route a legacy row through the
 * canonical-current-occurrence comparison.
 */
class PodcastProgressLegacyPairedViewTest {
    private val episode = MediaItem("episode", "The complete original episode title", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "guid", feedUrl = "https://example.org/feed.xml"),
        showId = "show", durationMs = 120_000L)
    private val show = PodcastShow("show", "Authored show", "https://example.org/feed.xml",
        episodeIds = listOf(episode.id), lastRefreshEpochMs = 100L)
    private val historical = receipt("history-A", "abcdefghijk", 1L)
    private val selected = receipt("download-B", "lmnopqrstuv", 2L)
    private val progress = MediaProgress(positionMs = 45_000L, durationMs = 180_000L,
        executionBindingId = historical.id, updatedEpochMs = 40L)

    private fun receipt(id: String, video: String, time: Long) = PlaybackBinding(
        id, episode.id, episode.title, FirstResultKind.VIDEO, video, "Authored recording $id",
        selectedVideoId = video, resolvedEpochMs = time,
    )

    private fun download(binding: PlaybackBinding) = DownloadRecord("download", episode.id,
        DownloadStatus.COMPLETE, executionBindingId = binding.id, representationId = "representation",
        asset = OfflineAsset("asset", episode.id, "/owned/authored-not-a-media-file", 1_024L,
            executionBindingId = binding.id, representationId = "representation",
            measuredDurationMs = 240_000L))

    private fun snapshot(target: PlaybackBinding = selected) = AppSnapshot(
        media = mapOf(episode.id to episode), shows = mapOf(show.id to show),
        progress = mapOf(episode.id to progress), downloads = mapOf("download" to download(target)),
        playbackBindings = listOf(historical, target).associateBy { it.id },
        savedEpisodeIds = setOf(episode.id), followedShowIds = setOf(show.id),
        settings = AppSettings(offlineOnly = true),
    )

    private fun paired(snapshot: AppSnapshot, canonical: Map<String, MediaProgress> = emptyMap()):
        PodcastProgressView {
        val state = AcquisitionRepositoryState(libraryRevision = snapshot.revision)
        return podcastProgressView(PlaybackAcquisitionView(
            LibraryAcquisitionView(snapshot, state), emptyMap(), emptyMap(), emptyList(), canonical))!!
    }

    @Test
    fun nonnullLegacyOnlyProjectionKeepsMatchingSchema2ResumeTimeline() {
        val sameVideo = selected.copy(firstResultId = historical.firstResultId,
            selectedVideoId = historical.selectedVideoId)
        val base = snapshot(sameVideo)
        val view = paired(base)
        val entry = view.forEpisode(episode.id)!!
        assertEquals(PodcastProgressSource.LEGACY, entry.source)
        assertEquals(historical.selectedVideoId, entry.recordingId)

        val state = base.podcastEpisodeUiState(episode, progressView = view)
        assertNull(state.canonicalProgress, "A legacy row is not a canonical progress row")
        assertEquals(PodcastProgressRecording.MATCHED, state.progressRecording)
        assertEquals(45_000L, state.historicalPositionMs)
        assertEquals(45_000L, state.positionMs)
        assertEquals(180_000L, state.progressDurationMs)
        assertTrue(state.canResume)
        assertFalse(state.played)
    }

    @Test
    fun nonnullLegacyOnlyProjectionKeepsChangedReceiptHistoryButNoResumeClaim() {
        val view = paired(snapshot())
        val state = snapshot().podcastEpisodeUiState(episode, progressView = view)
        assertNull(state.canonicalProgress)
        assertEquals(PodcastProgressRecording.CHANGED, state.progressRecording)
        assertEquals(45_000L, state.historicalPositionMs)
        assertEquals(0L, state.positionMs)
        assertNull(state.progressDurationMs)
        assertFalse(state.canResume)
        assertEquals(TextKeyPodcastChanged, podcastHistoricalProgressNoticeKey(state))
    }

    @Test
    fun canonicalMissingBindingRowStaysUnverifiedWithoutLegacyFallback() {
        val base = snapshot()
        val missing = MediaProgress(45_000L, 180_000L, played = false, updatedEpochMs = 100,
            executionBindingId = "missing-binding")
        val view = paired(base, canonical = mapOf(episode.id to missing))
        val entry = view.forEpisode(episode.id)!!
        assertEquals(PodcastProgressSource.CANONICAL, entry.source)
        val state = base.podcastEpisodeUiState(episode, progressView = view)
        assertEquals(entry, state.canonicalProgress)
        assertEquals(PodcastProgressRecording.UNVERIFIED, state.progressRecording)
        assertEquals(45_000L, state.historicalPositionMs)
        assertEquals(0L, state.positionMs)
        assertNull(state.progressDurationMs)
        assertFalse(state.canResume)
    }

    private companion object {
        // Keep the assertion typed without importing the presentation enum into production.
        val TextKeyPodcastChanged = dev.veyra.ui.design.TextKey.PodcastProgressRecordingChanged
    }
}
