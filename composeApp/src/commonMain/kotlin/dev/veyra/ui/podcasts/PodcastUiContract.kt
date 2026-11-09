package dev.veyra.ui.podcasts

import dev.veyra.model.AppFailure
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OfflineAssetKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.SourceProvider
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import dev.veyra.ui.downloads.toDownloadSurfaceEntry

/** Root owns these states and handles every effect; feature composables own no clients or players. */
data class PodcastsUiState(
    val snapshot: AppSnapshot = AppSnapshot(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val failure: AppFailure? = null,
    val addFeed: AddPodcastFeedUiState? = null,
    /** Paired typed/schema2 projection from the app composition; null keeps legacy-only callers. */
    val downloads: DownloadSurface? = null,
    /** Paired canonical playback view projection; null keeps the legacy snapshot fallback. */
    val progressView: PodcastProgressView? = null,
)

data class AddPodcastFeedUiState(
    val url: String = "",
    val isSubmitting: Boolean = false,
    val failure: AppFailure? = null,
)

data class PodcastShowUiState(
    val showId: String,
    val snapshot: AppSnapshot,
    val options: PodcastListOptions = PodcastListOptions(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val failure: AppFailure? = null,
    val downloads: DownloadSurface? = null,
    val progressView: PodcastProgressView? = null,
) {
    val show: PodcastShow? get() = snapshot.shows[showId]
}

data class EpisodeDetailUiState(
    val mediaId: String,
    val snapshot: AppSnapshot,
    val isLoading: Boolean = false,
    val failure: AppFailure? = null,
    /** Only the root knows the receipt actually loaded by the active audio coordinator. */
    val currentBinding: PlaybackBinding? = null,
    val downloads: DownloadSurface? = null,
    val progressView: PodcastProgressView? = null,
) {
    val episode: MediaItem? get() = snapshot.media[mediaId]?.takeIf { it.kind == MediaKind.EPISODE }
    val show: PodcastShow? get() = episode?.showId?.let(snapshot.shows::get)
}

enum class PodcastBindingContext { CURRENT, DOWNLOADED, PREVIOUS }

enum class PodcastProgressRecording { MATCHED, CHANGED, UNVERIFIED }

/** Domain IDs stay intact. Selecting/navigating is intentionally not a play command. */
sealed interface PodcastAction {
    data object FindPodcasts : PodcastAction
    data object OpenAddFeed : PodcastAction
    data object DismissAddFeed : PodcastAction
    data class FeedUrlChanged(val url: String) : PodcastAction
    data class SubmitFeed(val url: String) : PodcastAction
    data object RefreshShows : PodcastAction
    data class RefreshShow(val showId: String) : PodcastAction
    data class OpenShow(val showId: String) : PodcastAction
    data class OpenEpisode(val mediaId: String) : PodcastAction
    data object NavigateBack : PodcastAction
    data class SetFollowed(val showId: String, val followed: Boolean) : PodcastAction
    data class SetShowListOptions(val showId: String, val options: PodcastListOptions) : PodcastAction
    data class PlayEpisode(val mediaId: String) : PodcastAction
    /** Exact current/queued occurrence resume; never a fresh catalogue Play. */
    data class ResumeEpisode(
        val mediaId: String,
        val bindingId: String? = null,
        val occurrenceId: String? = null,
        val expectedQueueRevision: Long? = null,
    ) : PodcastAction
    /** Explicit exact saved-R1 episode action; owner revalidates all identity/readiness fences. */
    data class PlaySavedEpisode(val mediaId: String, val bindingId: String) : PodcastAction
    data class StartOver(val mediaId: String) : PodcastAction
    data class SetPlayed(val mediaId: String, val played: Boolean) : PodcastAction
    data class SetSaved(val mediaId: String, val saved: Boolean) : PodcastAction
    data class PlayNext(val mediaId: String) : PodcastAction
    data class AddToEnd(val mediaId: String) : PodcastAction
    data object OpenQueue : PodcastAction
    data class Download(val mediaId: String) : PodcastAction
    data class PauseDownload(val downloadId: String) : PodcastAction
    /** Explicit local validation of retained FAILED bytes under the exact persisted binding/member. */
    data class ValidateRetainedDownload(
        val downloadId: String,
        val bindingId: String,
        val batchId: String? = null,
        val memberPosition: Int? = null,
        val expectedIntentRevision: Long? = null,
    ) : PodcastAction {
        init { require((batchId == null) == (memberPosition == null && expectedIntentRevision == null)) }
    }
    data class ResumeDownload(val downloadId: String) : PodcastAction
    data class CancelDownload(val downloadId: String) : PodcastAction
    data class RemoveDownload(val downloadId: String) : PodcastAction
    data class OpenPublisher(val url: String) : PodcastAction
}

/** A projection of real shared records, never an invented source result. */
data class PodcastEpisodeUiState(
    val episode: MediaItem,
    val show: PodcastShow? = null,
    val progress: MediaProgress? = null,
    val download: DownloadRecord? = null,
    /** Paired typed catalogue projection only; legacy rows stay in [download]. */
    val typedDownload: DownloadSurfaceEntry? = null,
    val surfaceDownloaded: Boolean = false,
    val saved: Boolean = false,
    val queued: Boolean = false,
    val offlineOnly: Boolean = false,
    /** Disclosure can describe currently loaded audio, not necessarily the next Play target. */
    val binding: PlaybackBinding? = null,
    val bindingContext: PodcastBindingContext = PodcastBindingContext.PREVIOUS,
    val progressBinding: PlaybackBinding? = null,
    val downloadedBinding: PlaybackBinding? = null,
    /** Canonical paired playback evidence; null keeps the legacy snapshot fallback. */
    val progressView: PodcastProgressView? = null,
    /** Unambiguous saved typed binding from the full download projection, never newest-row. */
    val savedPlayBindingId: String? = null,
) {
    val listingDurationMs: Long? get() = episode.durationMs?.takeIf { it > 0L }
    val canonicalProgress: PodcastEpisodeProgress? get() = progressView?.forEpisode(episode.id)
        ?.takeIf { it.source == PodcastProgressSource.CANONICAL }
    /** Historical listening remains intact even when the next selection is a different recording. */
    val historicalPositionMs: Long get() = canonicalProgress?.positionMs ?: progress?.positionMs?.coerceAtLeast(0L) ?: 0L
    /** The one row the existing download controls operate, by its actual physical job id. */
    val activeDownload: DownloadSurfaceEntry? get() = typedDownload ?: download?.toDownloadSurfaceEntry()
    // PlayEpisode is a fresh Play, not a request to resume the displayed current binding.
    // The resolver prefers a completed local artifact; without one, a fresh online first-result
    // search is still unknown. Neither CURRENT nor the newest historical receipt pins that search.
    val nextPlayBinding: PlaybackBinding? get() {
        // A typed download has exact execution/recording lineage, but no legacy PlaybackBinding.
        // Never invent one; the playback owner resolves the typed asset independently.
        if (typedDownload != null) return null
        val record = download?.takeIf { downloaded && !it.cleanupPending } ?: return null
        val asset = record.asset ?: return null
        return downloadedBinding?.takeIf {
            record.executionBindingId == it.id && asset.executionBindingId == it.id &&
                asset.kind == OfflineAssetKind.FILE && !asset.representationId.isNullOrBlank() &&
                record.representationId == asset.representationId && it.recordingIdFor(episode.id) != null
        }
    }
    val progressRecording: PodcastProgressRecording get() {
        canonicalProgress?.let { return progressView?.recordingState(episode.id) ?: PodcastProgressRecording.UNVERIFIED }
        val historical = progressBinding?.takeIf { it.id == progress?.executionBindingId }
            ?.recordingIdFor(episode.id) ?: return PodcastProgressRecording.UNVERIFIED
        val selected = nextPlayBinding?.recordingIdFor(episode.id) ?: return PodcastProgressRecording.UNVERIFIED
        return if (historical == selected) PodcastProgressRecording.MATCHED else PodcastProgressRecording.CHANGED
    }
    // RSS duration describes the requested listing, which may differ from the resolved recording.
    // Never use that estimate as a denominator for real listening progress or remaining audio.
    // Likewise, recording A's measured timeline must never be presented as recording B's.
    val progressDurationMs: Long? get() {
        val canonical = canonicalProgress
        return if (canonical != null) {
            canonical.durationMs.takeIf { progressRecording == PodcastProgressRecording.MATCHED }
        } else progress?.durationMs?.takeIf { it > 0L && progressRecording == PodcastProgressRecording.MATCHED }
    }
    val positionMs: Long get() = canonicalProgress?.positionMs
        ?.takeIf { progressRecording == PodcastProgressRecording.MATCHED }
        ?: historicalPositionMs.takeIf { progressRecording == PodcastProgressRecording.MATCHED } ?: 0L
    val played: Boolean get() = canonicalProgress?.played ?: (progress?.played == true)
    // A status enum alone is not proof of usable local media. The owner must have committed its
    // asset (or the paired typed projection must carry exact-use checked readiness).
    val downloaded: Boolean get() = surfaceDownloaded || activeDownload?.ready == true
    // A duration estimate or seek to the end is not confirmation that an episode was played.
    // Only the coordinator's confirmed natural-end/manual flag can remove it from Continue.
    val inProgress: Boolean get() = !played && historicalPositionMs > 0L
    // Online podcast playback is resolved centrally from the complete discovery title to
    // the FIRST kmpYT result. An RSS enclosure is metadata, not our playback endpoint.
    // Eligibility to try resolution is not a promise that a matching/playable result exists.
    val hasAudioSource: Boolean get() = if (episode.source.provider == SourceProvider.LOCAL) {
        !episode.source.localAssetId.isNullOrBlank()
    } else episode.title.isNotBlank()
    val canPlay: Boolean get() = downloaded || (!offlineOnly && hasAudioSource)
    val canResume: Boolean get() = inProgress && progressRecording == PodcastProgressRecording.MATCHED
    /**
     * Exact displayed target. Canonical current progress uses the PODCAST queue cursor even when
     * MUSIC is the active native context; legacy progress requires one queued occurrence with the
     * exact downloaded binding/recording. No same-media, newest, or current-music substitute.
     */
    private val canonicalResumeTarget: PodcastResumeTarget? get() {
        val view = progressView ?: return null
        if (view.currentEpisodeId != episode.id) return null
        val occurrenceId = view.currentOccurrenceId ?: return null
        val bindingId = view.currentBindingId ?: return null
        val recordingId = view.currentRecordingId ?: return null
        val revision = view.queueRevision ?: return null
        return PodcastResumeTarget(occurrenceId, bindingId, recordingId, queueRevision = revision)
    }
    private val legacyResumeTarget: PodcastResumeTarget? get() {
        val binding = nextPlayBinding ?: return null
        val recording = binding.recordingIdFor(episode.id) ?: return null
        return progressView?.queuedTargets?.get(episode.id).orEmpty()
            .filter { it.bindingId == binding.id && it.legacyRecordingId == recording }
            .singleOrNull()
    }
    val resumeActionable: Boolean get() = canResume &&
        (canonicalResumeTarget != null || legacyResumeTarget != null)
    val resumeTarget: PodcastResumeTarget?
        get() = if (!resumeActionable) null else canonicalResumeTarget ?: legacyResumeTarget
    val resumeOccurrenceId: String? get() = resumeTarget?.occurrenceId
    val resumeBindingId: String? get() = resumeTarget?.bindingId
    val resumeQueueRevision: Long? get() = resumeTarget?.queueRevision
    val noLongerInFeed: Boolean get() = show?.let {
        it.lastRefreshEpochMs != null && episode.id !in it.episodeIds
    } ?: false
}

fun AppSnapshot.podcastEpisodeUiState(episode: MediaItem, currentBinding: PlaybackBinding? = null,
    downloadSurface: DownloadSurface? = null, progressView: PodcastProgressView? = null): PodcastEpisodeUiState {
    // App data guarantees one authoritative record per item; attempt history is separate.
    val download = downloads.values.firstOrNull { it.mediaId == episode.id }
    val surfaceEntry = downloadSurface?.forMedia(episode.id)?.takeIf { it.origin == DownloadSurfaceOrigin.TYPED_CATALOGUE }
    val activeBinding = currentBinding?.takeIf { it.mediaId == episode.id }
    val downloadedBinding = download?.takeIf { it.hasCommittedAssetFor(episode.id) }?.asset?.executionBindingId
        ?.let { id -> playbackBindings[id]?.takeIf { it.id == id && it.mediaId == episode.id } }
    val progressBinding = progress[episode.id]?.executionBindingId
        ?.let { id -> playbackBindings[id]?.takeIf { it.id == id && it.mediaId == episode.id } }
    val previousBinding = if (activeBinding == null && downloadedBinding == null) playbackBindings.values
        .asSequence().filter { it.mediaId == episode.id }
        .maxWithOrNull(compareBy<PlaybackBinding> { it.resolvedEpochMs }.thenBy { it.id }) else null
    return PodcastEpisodeUiState(
        episode = episode,
        show = episode.showId?.let(shows::get),
        progress = progress[episode.id],
        download = download,
        typedDownload = surfaceEntry,
        surfaceDownloaded = downloadSurface?.isDownloaded(episode.id) ?: (surfaceEntry?.ready == true),
        saved = episode.id in savedEpisodeIds,
        queued = queue(QueueContext.PODCAST).let { queue ->
            queue.entries.drop(queue.currentIndex.coerceAtLeast(0)).any { it.mediaId == episode.id }
        },
        offlineOnly = settings.offlineOnly,
        binding = activeBinding ?: downloadedBinding ?: previousBinding,
        bindingContext = when {
            activeBinding != null -> PodcastBindingContext.CURRENT
            downloadedBinding != null -> PodcastBindingContext.DOWNLOADED
            else -> PodcastBindingContext.PREVIOUS
        },
        progressBinding = progressBinding,
        downloadedBinding = downloadedBinding,
        progressView = progressView,
        savedPlayBindingId = downloadSurface?.unambiguousCatalogueBinding(episode.id),
    )
}

fun PodcastShowUiState.visibleEpisodes(): List<MediaItem> {
    val downloadedIds = if (options.filter == PodcastEpisodeFilter.DOWNLOADED) {
        downloads?.readyMediaIds ?: snapshot.downloads.values
            .filter { it.hasCommittedAssetFor(it.mediaId) }.mapTo(mutableSetOf()) { it.mediaId }
    } else emptySet()
    return snapshot.media.values
        .asSequence()
        .filter { it.kind == MediaKind.EPISODE && it.showId == showId }
        .filter { podcastMatchesQuery(options.query, it.title, it.creator, it.description) }
        .filter { item ->
            val canonical = progressView?.forEpisode(item.id)
            val progress = snapshot.progress[item.id]
            when (options.filter) {
                PodcastEpisodeFilter.ALL -> true
                PodcastEpisodeFilter.UNPLAYED -> canonical?.played?.not() ?: (progress?.played != true)
                PodcastEpisodeFilter.IN_PROGRESS -> canonical?.let { !it.played && it.positionMs > 0L }
                    ?: (progress != null && !progress.played && progress.positionMs > 0L)
                PodcastEpisodeFilter.PLAYED -> canonical?.played ?: (progress?.played == true)
                PodcastEpisodeFilter.DOWNLOADED -> item.id in downloadedIds
            }
        }
        .toList()
        .let { episodes ->
            // Unknown publication dates stay at the end in either direction, rather than becoming "new".
            val known = episodes.filter { it.publishedEpochMs != null }
            val unknown = episodes.filter { it.publishedEpochMs == null }.sortedBy { it.id }
            val sorted = when (options.sort) {
                PodcastEpisodeSort.NEWEST_FIRST -> known.sortedWith(compareByDescending<MediaItem> { it.publishedEpochMs }.thenBy { it.id })
                PodcastEpisodeSort.OLDEST_FIRST -> known.sortedWith(compareBy<MediaItem> { it.publishedEpochMs }.thenBy { it.id })
            }
            sorted + unknown
        }
}

internal fun PodcastsUiState.continueEpisodes(): List<MediaItem> = if (progressView != null) {
    snapshot.media.values.filter { item ->
        item.kind == MediaKind.EPISODE && progressView.isInProgress(item.id)
    }.sortedWith(compareByDescending<MediaItem> {
        progressView.forEpisode(it.id)?.updatedEpochMs ?: 0L
    }.thenBy { it.id })
} else snapshot.media.values
    .filter { it.kind == MediaKind.EPISODE && snapshot.progress[it.id]?.let { progress ->
        !progress.played && progress.positionMs > 0L
    } == true }
    .sortedWith(compareByDescending<MediaItem> { snapshot.progress[it.id]?.updatedEpochMs ?: 0L }.thenBy { it.id })

internal fun PodcastsUiState.newEpisodes(): List<MediaItem> {
    val refreshedShowEpisodes = snapshot.shows.values.filter { it.lastRefreshEpochMs != null }
        .associate { it.id to it.episodeIds.toSet() }
    return snapshot.media.values
        .filter { item ->
            val progressAllowsNew = if (progressView != null) {
                progressView.forEpisode(item.id)?.let { !it.played && it.positionMs <= 0L } != false
            } else snapshot.progress[item.id]?.let { !it.played && it.positionMs <= 0L } != false
            item.kind == MediaKind.EPISODE && item.showId in snapshot.followedShowIds && progressAllowsNew &&
                refreshedShowEpisodes[item.showId]?.let { item.id in it } != false
        }
        .sortedWith(compareByDescending<MediaItem> { it.publishedEpochMs ?: Long.MIN_VALUE }.thenBy { it.id })
}

internal fun PodcastsUiState.followedShows(): List<PodcastShow> = snapshot.shows.values
    .filter { it.id in snapshot.followedShowIds }
    .sortedWith(compareBy<PodcastShow> { it.title.lowercase() }.thenBy { it.id })

private fun DownloadRecord.hasCommittedAssetFor(mediaId: String): Boolean =
    status == DownloadStatus.COMPLETE && this.mediaId == mediaId &&
        asset?.let { it.mediaId == mediaId && it.sizeBytes > 0L && it.uri.isNotBlank() } == true
