package dev.veyra.ui.podcasts

import dev.veyra.model.AppSettings
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FirstResultKind
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAsset
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Authored receipts and paths test projection only; no source or file is opened. */
class PodcastProgressRecordingTest {
    private val episode = MediaItem("episode", "The complete original episode title", MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, "guid", feedUrl = "https://example.org/feed.xml"),
        showId = "show", durationMs = 120_000L)
    private val show = PodcastShow("show", "Authored show", "https://example.org/feed.xml",
        episodeIds = listOf(episode.id), lastRefreshEpochMs = 100L)
    private val historical = receipt("history-A", "abcdefghijk", 1L)
    private val selected = receipt("download-B", "lmnopqrstuv", 2L)
    private val newest = receipt("latest-C", "0123456789_", 3L)
    private val progress = MediaProgress(positionMs = 45_000L, durationMs = 180_000L,
        executionBindingId = historical.id, updatedEpochMs = 40L, sessionId = "authored-session", sequence = 4L)
    private val queue = QueueSnapshot(listOf(QueueEntry("queued-episode", episode.id)), "queued-episode")

    private fun receipt(id: String, video: String, time: Long) = PlaybackBinding(
        id, episode.id, episode.title, FirstResultKind.VIDEO, video, "Authored recording $id",
        selectedVideoId = video, resolvedEpochMs = time,
    )

    private fun download(binding: PlaybackBinding) = DownloadRecord("download", episode.id, DownloadStatus.COMPLETE,
        executionBindingId = binding.id, representationId = "representation",
        asset = OfflineAsset("asset", episode.id, "/owned/authored-not-a-media-file", 1_024L,
            executionBindingId = binding.id, representationId = "representation", measuredDurationMs = 240_000L))

    private fun snapshot(target: PlaybackBinding = selected) = AppSnapshot(
        media = mapOf(episode.id to episode), shows = mapOf(show.id to show),
        progress = mapOf(episode.id to progress), downloads = mapOf("download" to download(target)),
        playbackBindings = listOf(historical, target, newest).associateBy { it.id },
        savedEpisodeIds = setOf(episode.id), followedShowIds = setOf(show.id),
        queues = mapOf(QueueContext.PODCAST to queue), settings = AppSettings(offlineOnly = true),
    )

    @Test
    fun podcastSavedChoiceIsUnambiguousAndNeverOverridesCanonicalResume() {
        fun entry(id: String, binding: String, time: Long) = DownloadSurfaceEntry(
            id = id, origin = DownloadSurfaceOrigin.TYPED_CATALOGUE, mediaId = episode.id,
            status = DownloadStatus.COMPLETE, bytesDownloaded = 10L, hasOwnedBytes = true,
            ready = true, bindingId = binding, updatedEpochMs = time,
        )
        val twoReady = DownloadSurface(listOf(entry("ready-A", "binding-A", 1L), entry("ready-B", "binding-B", 2L)))
        assertNull(snapshot().copy(progress = emptyMap()).podcastEpisodeUiState(episode,
            downloadSurface = twoReady).savedPlayBindingId,
            "Two ready recordings must not collapse to the newest saved binding")

        val canonical = PodcastEpisodeProgress(
            mediaId = episode.id, positionMs = 45_000L, durationMs = 180_000L, played = false,
            updatedEpochMs = 40L, bindingId = "canonical-binding-B", recordingId = "lmnopqrstuv",
            occurrenceId = "occ-B", source = PodcastProgressSource.CANONICAL, bindingValid = true,
        )
        val view = PodcastProgressView(mapOf(episode.id to canonical), currentEpisodeId = episode.id,
            currentRecordingId = "lmnopqrstuv", currentOccurrenceId = "occ-B",
            currentBindingId = "canonical-binding-B", queueRevision = 9L,
            queuedTargets = mapOf(episode.id to listOf(PodcastResumeTarget(
                "occ-B", "canonical-binding-B", "lmnopqrstuv", queueRevision = 9L))))
        val canonicalState = snapshot().copy(progress = emptyMap()).podcastEpisodeUiState(episode,
            downloadSurface = DownloadSurface(listOf(entry("ready-A", "binding-A", 1L))), progressView = view)
        assertEquals("binding-A", canonicalState.savedPlayBindingId)
        assertTrue(canonicalState.resumeActionable)
        assertEquals("canonical-binding-B", canonicalState.resumeBindingId)
        assertEquals("occ-B", canonicalState.resumeOccurrenceId)
        assertEquals(9L, canonicalState.resumeQueueRevision)

        val legacyView = PodcastProgressView(mapOf(episode.id to PodcastEpisodeProgress(
            mediaId = episode.id, positionMs = 45_000L, durationMs = 180_000L, played = false,
            updatedEpochMs = 40L, bindingId = selected.id, recordingId = "lmnopqrstuv",
            occurrenceId = "queued-legacy", source = PodcastProgressSource.LEGACY, bindingValid = true,
        )), queuedTargets = mapOf(episode.id to listOf(PodcastResumeTarget(
            occurrenceId = "queued-legacy", bindingId = selected.id, recordingId = "schema2:lmnopqrstuv",
            legacyRecordingId = "lmnopqrstuv", queueRevision = 5L,
        ))))
        val legacyState = snapshot().copy(progress = mapOf(episode.id to progress.copy(executionBindingId = selected.id)))
            .podcastEpisodeUiState(episode, progressView = legacyView)
        assertTrue(legacyState.resumeActionable)
        assertEquals(selected.id, legacyState.resumeBindingId)
        assertEquals("queued-legacy", legacyState.resumeOccurrenceId)
        assertEquals(5L, legacyState.resumeQueueRevision)
    }

    @Test
    fun changedDownloadedRecordingRetainsHistoryButNeverBorrowsItsResumeTimeline() {
        listOf(false, true).forEach { offline ->
            val original = snapshot().copy(settings = AppSettings(offlineOnly = offline))
            val state = original.podcastEpisodeUiState(episode)
            assertEquals(selected, state.binding)
            assertEquals(PodcastBindingContext.DOWNLOADED, state.bindingContext)
            assertEquals(selected, state.nextPlayBinding)
            assertEquals(PodcastProgressRecording.CHANGED, state.progressRecording)
            assertTrue(state.downloaded)
            assertTrue(state.canPlay)
            assertFalse(state.canResume)
            assertEquals(0L, state.positionMs)
            assertNull(state.progressDurationMs)
            assertNull(podcastProgressFraction(state.positionMs, state.progressDurationMs))
            assertNull(podcastRemainingMs(state.positionMs, state.progressDurationMs))
            assertEquals(TextKey.PodcastProgressRecordingChanged, podcastHistoricalProgressNoticeKey(state))
            listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
                val strings = AppStrings(language)
                assertEquals(strings.text(TextKey.Play), podcastPlayLabel(state, strings))
            }
            assertEquals(progress, state.progress)
            assertEquals(45_000L, state.historicalPositionMs)
            assertTrue(state.inProgress)
            assertFalse(state.played)
            assertTrue(state.saved)
            assertTrue(state.queued)
            assertEquals(listOf(episode), PodcastsUiState(original).continueEpisodes())
            assertEquals(emptyList(), PodcastsUiState(original).newEpisodes())
            assertEquals(listOf(episode), PodcastShowUiState(show.id, original,
                PodcastListOptions(filter = PodcastEpisodeFilter.IN_PROGRESS)).visibleEpisodes())
            assertEquals(setOf(show.id), original.followedShowIds)
            assertEquals(queue, original.queue(QueueContext.PODCAST))
            assertEquals(progress, original.progress[episode.id])
            assertEquals(download(selected), original.downloads["download"])
        }
    }

    @Test
    fun sameVideoAcrossDifferentReceiptsAndTitleCorrectionKeepsNativeMeasuredResumeTimeline() {
        val sameVideo = selected.copy(firstResultId = historical.firstResultId, selectedVideoId = historical.selectedVideoId)
        val corrected = episode.copy(title = "A corrected complete publisher title", durationMs = 30_000L)
        val original = snapshot(sameVideo).copy(media = mapOf(corrected.id to corrected))
        val state = original.podcastEpisodeUiState(corrected)
        assertNotEquals(historical.id, sameVideo.id)
        assertNotEquals(corrected.title, sameVideo.queryTitle)
        assertEquals(PodcastProgressRecording.MATCHED, state.progressRecording)
        assertEquals(sameVideo, state.nextPlayBinding)
        assertTrue(state.canResume)
        assertEquals(45_000L, state.positionMs)
        assertEquals(180_000L, state.progressDurationMs, "Use matching native progress, not listing or unrelated asset duration")
        assertEquals(30_000L, state.listingDurationMs)
        assertEquals(0.25f, podcastProgressFraction(state.positionMs, state.progressDurationMs))
        assertEquals(135_000L, podcastRemainingMs(state.positionMs, state.progressDurationMs))
        assertNull(podcastHistoricalProgressNoticeKey(state))
        listOf(LanguagePreference.ENGLISH, LanguagePreference.ARABIC).forEach { language ->
            val strings = AppStrings(language)
            assertEquals(strings.format(TextKey.ResumeAt, strings.mediaTime(45_000L)), podcastPlayLabel(state, strings))
        }
        assertEquals(progress, original.progress[episode.id])
    }

    @Test
    fun nextLocalPlayTargetWinsOverCurrentDisclosureInBothDirections() {
        val localA = historical.copy(id = "new-receipt-A", resolvedEpochMs = 2L)
        val currentB = snapshot(localA).podcastEpisodeUiState(episode, currentBinding = selected)
        assertEquals(selected, currentB.binding)
        assertEquals(PodcastBindingContext.CURRENT, currentB.bindingContext)
        assertEquals(localA, currentB.nextPlayBinding)
        assertEquals(PodcastProgressRecording.MATCHED, currentB.progressRecording)
        assertTrue(currentB.canResume)
        assertEquals(45_000L, currentB.positionMs)

        val currentA = snapshot().podcastEpisodeUiState(episode, currentBinding = historical)
        assertEquals(historical, currentA.binding)
        assertEquals(PodcastBindingContext.CURRENT, currentA.bindingContext)
        assertEquals(selected, currentA.nextPlayBinding)
        assertEquals(PodcastProgressRecording.CHANGED, currentA.progressRecording)
        assertFalse(currentA.canResume)
        assertEquals(0L, currentA.positionMs)
        assertNull(currentA.progressDurationMs)
        assertEquals(progress, currentA.progress)
    }

    @Test
    fun onlineFreshSearchDoesNotTreatCurrentOrLatestHistoricalDisclosureAsAPinnedResume() {
        val original = snapshot().copy(downloads = emptyMap(), settings = AppSettings(offlineOnly = false))
        listOf(null, historical, selected).forEach { current ->
            val state = original.podcastEpisodeUiState(episode, currentBinding = current)
            assertEquals(current ?: newest, state.binding)
            assertEquals(if (current == null) PodcastBindingContext.PREVIOUS else PodcastBindingContext.CURRENT, state.bindingContext)
            assertNull(state.nextPlayBinding)
            assertEquals(PodcastProgressRecording.UNVERIFIED, state.progressRecording)
            assertTrue(state.canPlay)
            assertFalse(state.canResume)
            assertEquals(0L, state.positionMs)
            assertNull(state.progressDurationMs)
            assertEquals(TextKey.PodcastProgressRecordingUnverified, podcastHistoricalProgressNoticeKey(state))
            assertEquals(AppStrings(LanguagePreference.ENGLISH).text(TextKey.Play),
                podcastPlayLabel(state, AppStrings(LanguagePreference.ENGLISH)))
            assertEquals(progress, state.progress)
        }
    }

    @Test
    fun missingMalformedOrOtherMediaProgressReceiptCannotPromiseRecordingCompatibility() {
        val localA = historical.copy(id = "download-A", resolvedEpochMs = 2L)
        val malformed = listOf(null, historical.copy(mediaId = "other"), historical.copy(id = "wrong-map-key"),
            historical.copy(firstResultKind = FirstResultKind.PLAYLIST), historical.copy(selectedVideoId = null),
            historical.copy(firstResultId = ""), historical.copy(selectedVideoId = " "),
            historical.copy(firstResultId = "short", selectedVideoId = "short"), historical.copy(queryTitle = " "),
            historical.copy(selectionOrdinal = 1), historical.copy(firstResultId = selected.firstResultId))
        malformed.forEach { receipt ->
            val original = snapshot(localA).let {
                it.copy(playbackBindings = (it.playbackBindings - historical.id) +
                    if (receipt == null) emptyMap() else mapOf(historical.id to receipt))
            }
            val state = original.podcastEpisodeUiState(episode)
            assertEquals(localA, state.nextPlayBinding)
            assertEquals(PodcastProgressRecording.UNVERIFIED, state.progressRecording, "Malformed receipt: $receipt")
            assertFalse(state.canResume)
            assertEquals(0L, state.positionMs)
            assertNull(state.progressDurationMs)
            assertEquals(45_000L, state.historicalPositionMs)
            assertEquals(progress, state.progress)
        }
        val unbound = snapshot(localA).copy(progress = mapOf(episode.id to progress.copy(executionBindingId = null)))
            .podcastEpisodeUiState(episode)
        assertEquals(PodcastProgressRecording.UNVERIFIED, unbound.progressRecording)
        assertFalse(unbound.canResume)
        assertEquals(45_000L, unbound.historicalPositionMs)
    }

    @Test
    fun missingInconsistentOrNotReadyLocalArtifactCannotPinTheNextPlayReceipt() {
        val localA = historical.copy(id = "download-A", resolvedEpochMs = 2L)
        val original = snapshot(localA)
        val record = original.downloads.getValue("download")
        val asset = record.asset!!
        listOf(record.copy(executionBindingId = null), record.copy(executionBindingId = historical.id),
            record.copy(asset = asset.copy(executionBindingId = "missing")),
            record.copy(asset = asset.copy(mediaId = "other")), record.copy(asset = null),
            record.copy(asset = asset.copy(uri = "")), record.copy(asset = asset.copy(sizeBytes = 0L)),
            record.copy(asset = asset.copy(representationId = null)), record.copy(representationId = "other"),
            record.copy(asset = asset.copy(kind = OfflineAssetKind.MEDIA3_CACHE)),
            record.copy(cleanupPending = true), record.copy(status = DownloadStatus.VERIFYING),
            record.copy(status = DownloadStatus.NEEDS_FOREGROUND), record.copy(status = DownloadStatus.FAILED)).forEach { changed ->
            val state = original.copy(downloads = mapOf(record.id to changed))
                .podcastEpisodeUiState(episode, currentBinding = historical)
            assertNull(state.nextPlayBinding, "Invalid/unfinished artifact: $changed")
            assertEquals(PodcastProgressRecording.UNVERIFIED, state.progressRecording)
            assertFalse(state.canResume)
            assertNull(state.progressDurationMs)
            assertEquals(progress, state.progress)
        }
        val noReceipt = original.copy(playbackBindings = original.playbackBindings - localA.id)
            .podcastEpisodeUiState(episode, currentBinding = historical)
        assertNull(noReceipt.nextPlayBinding)
        assertFalse(noReceipt.canResume)
        assertEquals(progress, noReceipt.progress)
    }

    @Test
    fun playedFlagsAndHistoricalPositionRemainIndependentOfTheNextRecording() {
        val marked = progress.copy(played = true)
        val changed = snapshot().copy(progress = mapOf(episode.id to marked)).podcastEpisodeUiState(episode)
        assertTrue(changed.played)
        assertFalse(changed.inProgress)
        assertFalse(changed.canResume)
        assertEquals(marked, changed.progress)
        assertEquals(45_000L, changed.historicalPositionMs)
        assertEquals(0L, changed.positionMs)
        assertEquals(AppStrings(LanguagePreference.ENGLISH).text(TextKey.Play),
            podcastPlayLabel(changed, AppStrings(LanguagePreference.ENGLISH)))

        val matched = snapshot(historical).copy(progress = mapOf(episode.id to marked)).podcastEpisodeUiState(episode)
        assertTrue(matched.played)
        assertFalse(matched.inProgress)
        assertFalse(matched.canResume)
        assertEquals(45_000L, matched.positionMs)
        assertEquals(AppStrings(LanguagePreference.ENGLISH).text(TextKey.Replay),
            podcastPlayLabel(matched, AppStrings(LanguagePreference.ENGLISH)))
        val unknownTarget = matched.copy(download = null, downloadedBinding = null, offlineOnly = false)
        assertTrue(unknownTarget.played)
        assertFalse(unknownTarget.canResume)
        assertEquals(PodcastProgressRecording.UNVERIFIED, unknownTarget.progressRecording)
        assertEquals(marked, unknownTarget.progress)
        assertEquals(45_000L, unknownTarget.historicalPositionMs)
        assertEquals(0L, unknownTarget.positionMs)
        assertNull(unknownTarget.progressDurationMs)
        assertEquals(TextKey.PodcastProgressRecordingUnverified, podcastHistoricalProgressNoticeKey(unknownTarget))
        assertEquals(AppStrings(LanguagePreference.ENGLISH).text(TextKey.Play),
            podcastPlayLabel(unknownTarget, AppStrings(LanguagePreference.ENGLISH)))
        val noPosition = matched.copy(progress = marked.copy(positionMs = -1L, played = false))
        assertEquals(0L, noPosition.historicalPositionMs)
        assertFalse(noPosition.inProgress)
        assertFalse(noPosition.canResume)
        assertNull(podcastHistoricalProgressNoticeKey(noPosition))
    }
}
