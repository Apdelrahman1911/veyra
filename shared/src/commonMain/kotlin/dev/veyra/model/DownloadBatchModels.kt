package dev.veyra.model

enum class AdmissionScopeKind { WHOLE_OBSERVED_LISTING, AVAILABLE_SUBSET, SELECTED_SUBSET, CATALOG_LEAVES }
enum class AdmissionDisposition { INCLUDED, EXCLUDED }
enum class AdmissionExclusion { UNSELECTED, KNOWN_HOLE, MISSING_COMPLETE_TITLE, UNSUPPORTED_MEMBER }

/** Excluded holes need no invented child request. Every included position has its own request. */
data class AdmissionScopeMember(
    val position: Int,
    val snapshotMember: CollectionSnapshotMemberRef?,
    val requestId: AcquisitionRequestId?,
    val disposition: AdmissionDisposition,
    val exclusion: AdmissionExclusion?,
) {
    init {
        require(position >= 0)
        if (disposition == AdmissionDisposition.INCLUDED) require(requestId != null && exclusion == null)
        else require(exclusion != null)
    }
}

data class AdmissionScope(
    val id: AdmissionScopeId,
    val kind: AdmissionScopeKind,
    val parentRequestId: AcquisitionRequestId?,
    val snapshotId: CollectionSnapshotId?,
    val controlsMode: PlaybackControlsMode,
    val members: List<AdmissionScopeMember>,
    val acknowledgesUnobservedSuffix: Boolean,
    val admittedEpochMs: Long,
) {
    init { require(admittedEpochMs >= 0) }
}

enum class DownloadBatchIntent { ACTIVE, PAUSED, CANCELLED }
enum class DownloadBatchMemberState {
    PENDING_SELECTION, WAITING_FOR_JOB, TRANSFERRING, VERIFYING, AVAILABLE,
    PAUSED, CANCELLED, UNPLAYABLE, NEEDS_COLLECTION_ACTION, OUTCOME_UNKNOWN, FAILED,
}

data class DownloadBatchMember(
    val position: Int,
    val scopeMemberPosition: Int,
    val occurrenceId: ExecutionOccurrenceId,
    val requestId: AcquisitionRequestId,
    val requiredBindingId: ExecutionBindingId?,
    val claimId: DownloadClaimId?,
    val state: DownloadBatchMemberState,
    val failure: AppFailure? = null,
)

/** No parent download file. Counts remain per admitted occurrence, including repeated recordings. */
data class DownloadBatch(
    val id: DownloadBatchId,
    val scopeId: AdmissionScopeId,
    val intent: DownloadBatchIntent,
    val intentRevision: Long,
    val members: List<DownloadBatchMember>,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
)

sealed interface DownloadClaimOwner {
    data class Standalone(val jobId: String) : DownloadClaimOwner
    /** Migrated real owner. Missing producer/requested binding stays unknown, never invented. */
    data class Schema2Standalone(val jobId: String) : DownloadClaimOwner
    data class BatchMember(val batchId: DownloadBatchId, val position: Int) : DownloadClaimOwner
}

/** Existing leased executor owns physical work. Per-owner intent never silently revokes another owner. */
data class DownloadClaim(
    val id: DownloadClaimId,
    val owner: DownloadClaimOwner,
    val physicalJobId: String,
    val requestedBindingId: ExecutionBindingId?,
    val intent: DownloadBatchIntent,
    val retainCompletedAsset: Boolean,
    val updatedEpochMs: Long,
    /** Monotone per-claim CAS version; identity is never recycled for another owner. */
    val generation: Long = 0,
    /** Exact batch intentRevision, or the standalone owner's independently persisted intent. */
    val ownerIntentRevision: Long = 0,
) {
    init {
        require(generation >= 0 && ownerIntentRevision >= 0)
        require(requestedBindingId != null || owner is DownloadClaimOwner.Schema2Standalone)
    }
}

/** Persisted provenance only; every process still performs exact file/hash/native validation. */
data class AssetUse(
    val requestedBindingId: ExecutionBindingId,
    val materializationAssetId: MaterializationAssetId,
    val compatibilityPolicyVersion: Int,
    val createdEpochMs: Long,
)
