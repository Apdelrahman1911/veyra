package dev.veyra.data

import dev.veyra.model.*

/** One read barrier over the existing Room graph, including non-catalogue listening history. */
data class PlaybackAcquisitionView(
    val library: LibraryAcquisitionView,
    val checkpoints: Map<ExecutionOccurrenceId, OccurrenceCheckpoint>,
    val neutralResume: Map<Pair<ExecutableSubjectId, PlaybackControlsMode>, ExecutionOccurrenceId>,
    val history: List<ExecutionListeningHistory>,
    /** Genuine catalogue-only progress, joined against canonical execution bindings. */
    val catalogProgress: Map<String, MediaProgress>,
) {
    val snapshot: AppSnapshot get() = library.snapshot
    val acquisition: AcquisitionRepositoryState get() = library.acquisition
}

data class ExecutionListeningHistory(
    val id: String,
    val subjectId: ExecutableSubjectId,
    val origin: AcquisitionOrigin,
    val occurrenceId: ExecutionOccurrenceId?,
    val bindingId: ExecutionBindingId?,
    val startedEpochMs: Long,
    val lastPositionMs: Long,
    val endedNaturally: Boolean,
)

data class ExecutionQueueExpectation(
    val context: QueueContext,
    val editRevision: Long,
    val currentOccurrenceId: ExecutionOccurrenceId?,
)

/** A checkpoint is a native observation, not a requested seek, optimistic position or title join. */
data class ExecutionCheckpointWrite(
    val context: QueueContext,
    val checkpoint: OccurrenceCheckpoint,
    /** Absent until the originating native load has actually reported playback. */
    val historyId: String? = null,
    val historyStartedEpochMs: Long? = null,
)

/** Seeding a new native epoch preserves last real position; it is not a new measured sample. */
data class ExecutionLoadAdmission(
    val bindingId: ExecutionBindingId,
    val expectedCheckpoint: OccurrenceCheckpoint?,
    val sessionId: String,
    val generation: Long,
    val positionMs: Long,
    val measuredDurationMs: Long?,
    val nowEpochMs: Long,
)

/** Named mutations only. No arbitrary AppSnapshot/SQL transform crosses the playback port. */
sealed interface AcquisitionPlaybackCommand {
    data class AdmitQueue(
        val admission: QueueAcquisitionAdmission,
        val activate: Boolean,
        val expectedActiveContext: QueueContext,
        val startOccurrenceId: ExecutionOccurrenceId?,
        val startFromBeginning: Boolean,
        val nowEpochMs: Long,
        val outgoing: ExecutionCheckpointWrite?,
    ) : AcquisitionPlaybackCommand

    data class Activate(
        val queue: ExecutionQueueExpectation,
        val occurrenceId: ExecutionOccurrenceId,
        val expectedBindingId: ExecutionBindingId?,
        val expectedActiveContext: QueueContext,
        val positionMs: Long,
        val speed: Float,
        val startFromBeginning: Boolean,
        val nowEpochMs: Long,
        val outgoing: ExecutionCheckpointWrite?,
    ) : AcquisitionPlaybackCommand

    /** Existing exact occurrences only; queue removal never erases their immutable lineage. */
    data class EditQueue(
        val expected: ExecutionQueueExpectation,
        val displayedOrder: List<ExecutionOccurrenceId>,
        val originalOrder: List<ExecutionOccurrenceId>,
        val currentOccurrenceId: ExecutionOccurrenceId?,
        val positionMs: Long,
        val speed: Float,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val outgoing: ExecutionCheckpointWrite?,
        /** Only the controller's explicit Undo action may restore removed retained occurrences. */
        val restoreRetainedOccurrences: Boolean = false,
    ) : AcquisitionPlaybackCommand

    data class AdmitLoad(val queue: ExecutionQueueExpectation, val load: ExecutionLoadAdmission) : AcquisitionPlaybackCommand
    /** Reserve the exact immediate music neighbour's epoch before its source/native preload. */
    data class AdmitStage(
        val queue: ExecutionQueueExpectation,
        val outgoingBindingId: ExecutionBindingId,
        val outgoingSessionId: String,
        val outgoingGeneration: Long,
        val incomingOccurrenceId: ExecutionOccurrenceId,
        val load: ExecutionLoadAdmission,
    ) : AcquisitionPlaybackCommand
    data class Checkpoint(val write: ExecutionCheckpointWrite) : AcquisitionPlaybackCommand

    /** Durable cursor+outgoing sample+incoming epoch commit before native handover ACK. */
    data class Handover(
        val queue: ExecutionQueueExpectation,
        val outgoing: ExecutionCheckpointWrite,
        val incoming: ExecutionCheckpointWrite,
    ) : AcquisitionPlaybackCommand

    /** User intent only. Neutral subjects can never acquire catalogue/RSS progress through this. */
    data class CatalogProgress(
        val mediaId: String,
        val played: Boolean?,
        val resetPosition: Boolean,
        val nowEpochMs: Long,
        val outgoing: ExecutionCheckpointWrite?,
    ) : AcquisitionPlaybackCommand
}

interface PlaybackAcquisitionRepository : LibraryTransactionOwned {
    suspend fun playbackView(): PlaybackAcquisitionView
    suspend fun applyPlayback(command: AcquisitionPlaybackCommand): PlaybackAcquisitionView
}
