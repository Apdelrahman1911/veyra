package dev.veyra.ui.podcasts

import dev.veyra.data.PlaybackAcquisitionView
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutionBindingOrigin
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.QueueContext

enum class PodcastProgressSource { CANONICAL, LEGACY }

/**
 * One episode's durable playback evidence. [positionMs] is historical evidence, not a promise
 * that a future fresh Play will reuse [recordingId]. [bindingValid] proves only that the stored
 * canonical binding still names this catalogue episode and a real recording.
 */
data class PodcastEpisodeProgress(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val played: Boolean,
    val updatedEpochMs: Long,
    val bindingId: String?,
    val recordingId: String?,
    val occurrenceId: String?,
    val source: PodcastProgressSource,
    val bindingValid: Boolean,
)

/**
 * A read-only projection of the canonical playback view. It never invents a legacy
 * PlaybackBinding and never turns a title/source ID into recording identity. [currentEpisodeId]
 * and [currentRecordingId] describe the one canonical PODCAST queue occurrence that is actually
 * selected now; other rows remain historical/unverified until a fresh Play establishes a
 * recording. A row's played flag is independent of resume compatibility.
 */
data class PodcastProgressView(
    val episodes: Map<String, PodcastEpisodeProgress>,
    val currentEpisodeId: String? = null,
    val currentRecordingId: String? = null,
) {
    fun forEpisode(mediaId: String): PodcastEpisodeProgress? = episodes[mediaId]

    fun isPlayed(mediaId: String): Boolean = episodes[mediaId]?.played == true

    fun isInProgress(mediaId: String): Boolean = episodes[mediaId]?.let { !it.played && it.positionMs > 0L } == true

    fun expectedRecording(mediaId: String): String? =
        if (currentEpisodeId == mediaId) currentRecordingId else null

    fun recordingState(mediaId: String): PodcastProgressRecording {
        val progress = episodes[mediaId] ?: return PodcastProgressRecording.UNVERIFIED
        val expected = expectedRecording(mediaId) ?: return PodcastProgressRecording.UNVERIFIED
        val actual = progress.recordingId?.takeIf { progress.bindingValid } ?: return PodcastProgressRecording.UNVERIFIED
        return if (actual == expected) PodcastProgressRecording.MATCHED else PodcastProgressRecording.CHANGED
    }
}

/** Null means the caller has no paired canonical view and must keep the schema2 fallback. */
fun podcastProgressView(view: PlaybackAcquisitionView?): PodcastProgressView? {
    if (view == null) return null
    val snapshot = view.snapshot
    val state = view.acquisition
    val entries = linkedMapOf<String, PodcastEpisodeProgress>()
    snapshot.progress.forEach { (mediaId, progress) ->
        if (snapshot.media[mediaId]?.kind == MediaKind.EPISODE) {
            entries[mediaId] = legacyProgress(mediaId, progress, snapshot.playbackBindings)
        }
    }
    view.catalogProgress.forEach { (mediaId, progress) ->
        if (snapshot.media[mediaId]?.kind != MediaKind.EPISODE) return@forEach
        val bindingId = progress.executionBindingId?.let(::ExecutionBindingId)
        val binding = bindingId?.let(state.bindings::get)
        val subject = binding?.subjectId?.let(state.subjects::get) as? ExecutableSubject.CatalogMedia
        val recording = binding?.recordingId?.let(state.recordings::get)
        val valid = subject?.mediaId == mediaId && recording != null
        val occurrenceId = view.checkpoints.values
            .filter { bindingId != null && it.bindingId == bindingId }
            .maxByOrNull { it.observedEpochMs }?.occurrenceId?.value
        entries[mediaId] = PodcastEpisodeProgress(
            mediaId = mediaId,
            positionMs = progress.positionMs.coerceAtLeast(0L),
            durationMs = progress.durationMs?.takeIf { it > 0L },
            played = progress.played,
            updatedEpochMs = progress.updatedEpochMs,
            bindingId = progress.executionBindingId,
            recordingId = recording?.canonicalId?.takeIf { valid },
            occurrenceId = occurrenceId,
            source = PodcastProgressSource.CANONICAL,
            bindingValid = valid,
        )
    }
    val queue = state.queues[QueueContext.PODCAST]
    val occurrence = queue?.currentOccurrenceId?.let(state.occurrences::get)
    val subject = occurrence?.subjectId?.let(state.subjects::get) as? ExecutableSubject.CatalogMedia
    val currentEpisodeId = subject?.mediaId?.takeIf { snapshot.media[it]?.kind == MediaKind.EPISODE }
    val currentRecordingId = occurrence?.bindingId?.let(state.bindings::get)?.recordingId
        ?.let(state.recordings::get)?.canonicalId
    return PodcastProgressView(entries, currentEpisodeId, currentRecordingId)
}

private fun legacyProgress(mediaId: String, progress: MediaProgress,
    bindings: Map<String, PlaybackBinding>): PodcastEpisodeProgress {
    val binding = progress.executionBindingId?.let(bindings::get)
    val recordingId = binding?.recordingIdFor(mediaId)
    return PodcastEpisodeProgress(
        mediaId = mediaId,
        positionMs = progress.positionMs.coerceAtLeast(0L),
        durationMs = progress.durationMs?.takeIf { it > 0L },
        played = progress.played,
        updatedEpochMs = progress.updatedEpochMs,
        bindingId = progress.executionBindingId,
        recordingId = recordingId,
        occurrenceId = null,
        source = PodcastProgressSource.LEGACY,
        bindingValid = recordingId != null,
    )
}

/** Same exact legacy receipt rule as the existing UI; never infer from a title or media id. */
internal fun PlaybackBinding.recordingIdFor(mediaId: String): String? = selectedVideoId?.takeIf {
    this.mediaId == mediaId && id.isNotBlank() && queryTitle.isNotBlank() && selectionOrdinal == 0 &&
        firstResultKind == FirstResultKind.VIDEO && firstResultId == it && it.matchesPodcastVideoId()
}

private fun String.matchesPodcastVideoId(): Boolean = length == 11 && all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
}
