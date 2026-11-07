package dev.veyra.model

/** Behaviour is independent of genuine catalogue identity or the currently browsed screen. */
enum class PlaybackControlsMode { MUSIC, PODCAST }
enum class AcquisitionOrigin { TYPED, SCHEMA2 }
enum class AcquisitionFailureStage { ADMISSION, SEARCH, SELECTION, RESTORE }

/** Legacy absence is a different variant, never a manufactured typed request/raw receipt. */
sealed interface FirstResultReceipt {
    val id: FirstResultReceiptId

    data class Observed(
        override val id: FirstResultReceiptId,
        val observation: FirstResultObservation,
        val observedEpochMs: Long,
    ) : FirstResultReceipt {
        init { require(observedEpochMs >= 0) }
    }

    /** Every existing schema2 field is retained; raw URL/issue/captured metadata remain unknown. */
    data class Schema2(
        override val id: FirstResultReceiptId,
        val mediaId: String,
        val queryTitle: String,
        val firstResultKind: FirstResultKind,
        val firstResultId: String,
        val firstResultTitle: String,
        val canonicalUrl: String?,
        val selectedVideoId: String?,
        val resolvedEpochMs: Long,
        val selectionOrdinal: Int,
    ) : FirstResultReceipt
}

data class SourceRecording(
    val id: SourceRecordingId,
    val canonicalId: String,
    val canonicalUrl: String,
    val provider: SourceProvider = SourceProvider.YOUTUBE,
) {
    init { require(provider == SourceProvider.YOUTUBE) }
}

sealed interface ExecutableSubject {
    val id: ExecutableSubjectId

    data class CatalogMedia(override val id: ExecutableSubjectId, val mediaId: String) : ExecutableSubject {
        init { require(id.value == mediaId) }
    }

    data class SourceRecordingSubject(
        override val id: ExecutableSubjectId,
        val recordingId: SourceRecordingId,
    ) : ExecutableSubject
}

enum class ExecutionBindingOrigin { TYPED, SCHEMA2_EXECUTABLE, SCHEMA2_NON_EXECUTABLE }
data class ExecutionBinding(
    val id: ExecutionBindingId,
    val subjectId: ExecutableSubjectId,
    val firstReceiptId: FirstResultReceiptId,
    val recordingId: SourceRecordingId?,
    val origin: ExecutionBindingOrigin,
) {
    init { require((recordingId == null) == (origin == ExecutionBindingOrigin.SCHEMA2_NON_EXECUTABLE)) }
}

/** Accepted once per request. A later inspection/transfer failure does not rewrite selection. */
sealed interface AcquisitionOutcome {
    val requestId: AcquisitionRequestId
    val receiptId: FirstResultReceiptId?

    data class Single(
        override val requestId: AcquisitionRequestId,
        override val receiptId: FirstResultReceiptId,
        val bindingId: ExecutionBindingId,
    ) : AcquisitionOutcome

    data class Collection(
        override val requestId: AcquisitionRequestId,
        override val receiptId: FirstResultReceiptId,
        val identity: CollectionIdentity,
        val scope: CollectionScope,
    ) : AcquisitionOutcome

    data class Unplayable(
        override val requestId: AcquisitionRequestId,
        override val receiptId: FirstResultReceiptId?,
        val stage: AcquisitionFailureStage,
        val problem: AcquisitionProblem,
    ) : AcquisitionOutcome
}

enum class AcquisitionJobState { READY, CLAIMED, COMMITTED, OUTCOME_UNKNOWN }
data class AcquisitionLease(
    val requestId: AcquisitionRequestId,
    val token: AcquisitionAttemptToken,
    val owner: String,
    val claimedEpochMs: Long,
    val expiresEpochMs: Long,
) {
    init { require(owner.isNotBlank() && claimedEpochMs >= 0 && expiresEpochMs > claimedEpochMs) }
    override fun toString() = "AcquisitionLease(owner=redacted,token=redacted)"
}

data class AcquisitionRecord(
    val request: AcquisitionRequest,
    val state: AcquisitionJobState,
    val lease: AcquisitionLease? = null,
    val outcome: AcquisitionOutcome? = null,
    val updatedEpochMs: Long,
)

data class CollectionSnapshotMemberRef(val snapshotId: CollectionSnapshotId, val ordinal: Int) {
    init { require(ordinal >= 0) }
}
data class AdmissionScopeMemberRef(val scopeId: AdmissionScopeId, val position: Int) {
    init { require(position >= 0) }
}

enum class OccurrenceSelectionState { PENDING, BOUND, UNPLAYABLE, NEEDS_COLLECTION_ACTION, OUTCOME_UNKNOWN, LEGACY_UNBOUND }
data class ExecutionOccurrence(
    val id: ExecutionOccurrenceId,
    val origin: AcquisitionOrigin,
    val requestId: AcquisitionRequestId?,
    val snapshotMember: CollectionSnapshotMemberRef?,
    val scopeMember: AdmissionScopeMemberRef?,
    val subjectId: ExecutableSubjectId?,
    val bindingId: ExecutionBindingId?,
    val controlsMode: PlaybackControlsMode,
    val selectionState: OccurrenceSelectionState,
    val admittedEpochMs: Long?,
) {
    init {
        require((requestId == null) == (origin == AcquisitionOrigin.SCHEMA2))
        require(admittedEpochMs == null || admittedEpochMs >= 0)
        if (selectionState == OccurrenceSelectionState.BOUND) require(subjectId != null && bindingId != null)
        else require(bindingId == null)
        if (selectionState == OccurrenceSelectionState.LEGACY_UNBOUND) require(origin == AcquisitionOrigin.SCHEMA2)
    }
}

/** Occurrences remain durable after queue removal; no current-media lookup chooses their binding. */
data class ExecutionQueue(
    val context: QueueContext,
    val editRevision: Long,
    val displayedOrder: List<ExecutionOccurrenceId>,
    val originalOrder: List<ExecutionOccurrenceId>,
    val currentOccurrenceId: ExecutionOccurrenceId?,
    val positionMs: Long,
    val speed: Float,
    val shuffle: Boolean,
    val repeat: RepeatMode,
)

/** Native observation fields are admitted only with the exact occurrence/binding/generation fence. */
data class OccurrenceCheckpoint(
    val occurrenceId: ExecutionOccurrenceId,
    val bindingId: ExecutionBindingId,
    val positionMs: Long,
    val measuredDurationMs: Long?,
    val observedEpochMs: Long,
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val endedNaturally: Boolean,
)
