package dev.veyra.playback

import dev.veyra.model.*
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

enum class PlaybackPhase { IDLE, RESOLVING, PREPARING, READY, PLAYING, PAUSED, BUFFERING, ENDED, ERROR }
enum class EnqueuePosition { NEXT, END }
enum class SleepTimerMode { DURATION, END_OF_EPISODE }
enum class CrossfadePhase { PREPARING, READY, OVERLAPPING }

data class CrossfadeState(
    val phase: CrossfadePhase,
    val incomingItem: MediaItem,
    val incomingEntryId: String,
    val durationMs: Long? = null,
    val fraction: Float = 0f,
    val incomingExecutionBinding: PlaybackBinding? = null,
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
) {
    val queue: QueueSnapshot get() = queues[activeContext] ?: QueueSnapshot()
    val isBuffering: Boolean get() = phase in setOf(PlaybackPhase.RESOLVING, PlaybackPhase.PREPARING, PlaybackPhase.BUFFERING)
}

sealed interface PlaybackCommand {
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
    data object SkipBack : PlaybackCommand
    data object SkipForward : PlaybackCommand
    data class Enqueue(val context: QueueContext, val items: List<MediaItem>, val position: EnqueuePosition = EnqueuePosition.END) : PlaybackCommand
    data class Remove(val context: QueueContext, val entryId: String) : PlaybackCommand
    data class Move(val context: QueueContext, val entryId: String, val toIndex: Int) : PlaybackCommand
    data class Clear(val context: QueueContext) : PlaybackCommand
    data class ClearUpcoming(val context: QueueContext) : PlaybackCommand
    data class UndoQueueEdit(val context: QueueContext) : PlaybackCommand
    data class SetShuffle(val enabled: Boolean, val context: QueueContext = QueueContext.MUSIC) : PlaybackCommand
    data class SetRepeat(val mode: RepeatMode, val context: QueueContext = QueueContext.MUSIC) : PlaybackCommand
    data class SetRate(val speed: Float, val forThisShow: Boolean = false) : PlaybackCommand
    data class SleepDuration(val durationMs: Long) : PlaybackCommand
    data object SleepAtEndOfEpisode : PlaybackCommand
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
