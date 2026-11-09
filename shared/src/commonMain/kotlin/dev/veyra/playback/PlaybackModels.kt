package dev.veyra.playback

import dev.veyra.acquisition.PreparedAcquisitionAdmission
import dev.veyra.data.QueueAdmissionOperation
import dev.veyra.model.*
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

enum class PlaybackPhase { IDLE, RESOLVING, PREPARING, READY, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }
enum class EnqueuePosition { NEXT, END }
enum class SleepTimerMode { DURATION, END_OF_EPISODE }
enum class CrossfadePhase { PREPARING, READY, OVERLAPPING }

data class CrossfadeState(
    val phase: CrossfadePhase,
    /** Catalogue compatibility only. Neutral recordings deliberately have no MediaItem. */
    val incomingItem: MediaItem?,
    val incomingEntryId: String,
    val durationMs: Long? = null,
    val fraction: Float = 0f,
    val incomingExecutionBinding: PlaybackBinding? = null,
    val incomingSelection: PlaybackSelection? = null,
    /** Exact silent-deck admission token, never an identity synthesized from a mediaId. */
    val incomingGeneration: Long? = null,
)

data class SleepTimerState(
    val mode: SleepTimerMode,
    val remainingMs: Long? = null,
    val boundEntryId: String? = null,
)

data class PlaybackState(
    val initialized: Boolean = false,
    val activeContext: QueueContext = QueueContext.MUSIC,
    val viewedContext: QueueContext = QueueContext.MUSIC,
    val queues: Map<QueueContext, QueueSnapshot> = QueueContext.entries.associateWith { QueueSnapshot() },
    val currentItem: MediaItem? = null,
    val currentEntryId: String? = null,
    val generation: Long = 0,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val desiredPlaying: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long? = null,
    val seekable: Boolean = false,
    val speed: Float = 1f,
    val effectiveSpeed: Float = 1f,
    val pendingSeekMs: Long? = null,
    val failure: AppFailure? = null,
    val sleepTimer: SleepTimerState? = null,
    val crossfade: CrossfadeState? = null,
    val executionBinding: PlaybackBinding? = null,
    val undoableQueueContexts: Set<QueueContext> = emptySet(),
    /** Canonical schema3 selections/order. Legacy queue/media projection is not their authority. */
    val currentSelection: PlaybackSelection? = null,
    val executionQueues: Map<QueueContext, ExecutionQueue> = emptyMap(),
    val queueSelections: Map<ExecutionOccurrenceId, PlaybackSelection> = emptyMap(),
) {
    val queue: QueueSnapshot get() = queues[activeContext] ?: QueueSnapshot()
    val isBuffering: Boolean get() = phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING)
    val hasCurrent: Boolean get() = currentSelection != null || currentItem != null
    val controlsMode: PlaybackControlsMode? get() = currentSelection?.controlsMode ?: currentItem?.let {
        if (it.kind == MediaKind.EPISODE) PlaybackControlsMode.PODCAST else PlaybackControlsMode.MUSIC
    }
}

sealed interface PlaybackCommand {
    /** Same-owner durable admission/checkpoint/optional activation, before any source call. */
    data class AdmitAcquisition(
        val prepared: PreparedAcquisitionAdmission,
        val context: QueueContext,
        val expectedQueueRevision: Long,
        val operation: QueueAdmissionOperation,
        val activate: Boolean = false,
        val startOccurrenceId: ExecutionOccurrenceId? = null,
        val startFromBeginning: Boolean = false,
        /** Saved-intent admission: the actor/resolver must never fall back to online preparation. */
        val localOnly: Boolean = false,
    ) : PlaybackCommand

    data class PlayOccurrence(
        val context: QueueContext,
        val expectedQueueRevision: Long,
        val occurrenceId: ExecutionOccurrenceId,
        val expectedBindingId: ExecutionBindingId? = null,
        val startFromBeginning: Boolean = false,
    ) : PlaybackCommand

    /**
     * Exact existing occurrence activation that always uses the occurrence/canonical checkpoint
     * resume policy, never the fresh-capture Play path. [localOnly] forbids online preparation
     * when no exact local materialization exists (saved-R1 intents).
     */
    data class ResumeOccurrence(
        val context: QueueContext,
        val expectedQueueRevision: Long,
        val occurrenceId: ExecutionOccurrenceId,
        val expectedBindingId: ExecutionBindingId? = null,
        val localOnly: Boolean = false,
    ) : PlaybackCommand

    /**
     * Explicit replacement and activation. Merely opening a collection must not dispatch this.
     * [startFromBeginning] is a podcast-only, selected-episode restart intent: checkpoint the
     * outgoing occurrence, reset the selected episode, then prepare a new occurrence at zero.
     * It does not claim a native seek completed or reset other collection members.
     */
    data class Play(
        val context: QueueContext,
        val items: List<MediaItem>,
        val startIndex: Int = 0,
        val shuffle: Boolean? = null,
        val startFromBeginning: Boolean = false,
    ) : PlaybackCommand
    data class PlayEntry(val context: QueueContext, val entryId: String) : PlaybackCommand
    data class SelectContext(val context: QueueContext) : PlaybackCommand
    data object PlayPause : PlaybackCommand
    data object Resume : PlaybackCommand
    data object Pause : PlaybackCommand
    data object Next : PlaybackCommand
    data object Previous : PlaybackCommand
    data class Seek(val positionMs: Long, val expectedEntryId: String? = null, val expectedGeneration: Long? = null) : PlaybackCommand
    data class SkipBy(val offsetMs: Long, val expectedEntryId: String? = null, val expectedGeneration: Long? = null) : PlaybackCommand
    /** Capture identity at the native callback; read configured seconds only inside the actor. */
    data class SkipConfigured(val forward: Boolean, val expectedEntryId: String, val expectedGeneration: Long) : PlaybackCommand
    data object SkipBack : PlaybackCommand
    data object SkipForward : PlaybackCommand
    data class Enqueue(val context: QueueContext, val items: List<MediaItem>, val position: EnqueuePosition = EnqueuePosition.END) : PlaybackCommand
    /** In schema3 [entryId] is an exact ExecutionOccurrenceId.value, never a catalogue ID. */
    data class Remove(val context: QueueContext, val entryId: String, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class Move(val context: QueueContext, val entryId: String, val toIndex: Int, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class Clear(val context: QueueContext, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class ClearUpcoming(val context: QueueContext, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class UndoQueueEdit(val context: QueueContext, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class SetShuffle(val enabled: Boolean, val context: QueueContext = QueueContext.MUSIC, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class SetRepeat(val mode: RepeatMode, val context: QueueContext = QueueContext.MUSIC, val expectedQueueRevision: Long? = null) : PlaybackCommand
    data class SetRate(val speed: Float, val forThisShow: Boolean = false) : PlaybackCommand
    data class SleepDuration(val durationMs: Long) : PlaybackCommand
    data object SleepAtEndOfEpisode : PlaybackCommand
    /** Long-form timer bound to the displayed exact occurrence/load, not whichever later plays. */
    data class SleepAtRecordingEnd(val expectedEntryId: String, val expectedGeneration: Long) : PlaybackCommand
    data object CancelSleepTimer : PlaybackCommand
    data class MarkPlayed(val mediaId: String, val played: Boolean) : PlaybackCommand
    data class ResetProgress(val mediaId: String) : PlaybackCommand
    data object Stop : PlaybackCommand
    data object FlushProgress : PlaybackCommand
}

sealed interface PlaybackResult {
    data object Accepted : PlaybackResult
    data class Failure(val failure: AppFailure) : PlaybackResult
}

fun PlaybackResult.getOrThrow() {
    if (this is PlaybackResult.Failure) throw VeyraException(failure)
}

interface Clock {
    fun epochMs(): Long
    fun monotonicMs(): Long
}

@OptIn(ExperimentalTime::class)
class SystemClock : Clock {
    private val start = TimeSource.Monotonic.markNow()
    override fun epochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
    override fun monotonicMs(): Long = start.elapsedNow().inWholeMilliseconds
}
