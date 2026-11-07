package dev.veyra.model

enum class CollectionScope { PLAYLIST_MEMBERS, CHANNEL_VIDEOS }
data class CollectionIdentity(val kind: AcquisitionEntryKind, val id: String, val canonicalUrl: String) {
    init { require(kind == AcquisitionEntryKind.PLAYLIST || kind == AcquisitionEntryKind.CHANNEL) }
}
enum class CollectionTitleOrigin { PLAYLIST_METADATA, PLAYLIST_HEADER, CHANNEL_METADATA }
enum class CollectionIdentityEvidence { PLAYLIST_LIST, PLAYLIST_METADATA, PLAYLIST_HEADER, PLAYLIST_CANONICAL, CHANNEL_METADATA }
enum class CollectionConsistency { UNVERSIONED_OBSERVATION }

data class CollectionObservation(
    val id: CollectionObservationId,
    val parentRequestId: AcquisitionRequestId,
    val identity: CollectionIdentity,
    val scope: CollectionScope,
    val title: String,
    val titleOrigin: CollectionTitleOrigin,
    val identityEvidence: Set<CollectionIdentityEvidence>,
    val sourceUrl: String,
    val consistency: CollectionConsistency = CollectionConsistency.UNVERSIONED_OBSERVATION,
)

data class CollectionMemberReference(
    val observationId: CollectionObservationId,
    val parentRequestId: AcquisitionRequestId,
    val identity: CollectionIdentity,
    val scope: CollectionScope,
    val ordinal: Int,
) {
    init { require(ordinal >= 0) }
}

data class CollectionMember(
    val reference: CollectionMemberReference,
    val entry: SourceEntry,
    val display: SourceDisplayItem?,
) {
    /** Eligibility only, not a selected recording or proof that title search succeeds. */
    val isVideoCandidate: Boolean get() = entry.kind == AcquisitionEntryKind.VIDEO && entry.issue == null &&
        entry.id != null && entry.title != null && display != null
}

enum class CollectionTraversal { MORE_AVAILABLE, OBSERVED_TO_END, INCOMPLETE }
enum class CollectionPageDisposition { OBSERVED_DELTA, FAILED_ATTEMPT }
enum class CollectionProblemCode {
    INVALID_SELECTION, SOURCE_FAILURE, INVALID_RESPONSE, IDENTITY_UNVERIFIED, IDENTITY_MISMATCH,
    SCOPE_UNVERIFIED, TITLE_UNAVAILABLE, MEMBERSHIP_UNVERIFIED, PARSE_LIMIT, MEMBER_LIMIT,
    PAGE_LIMIT, INVALID_CONTINUATION, CONTINUATION_UNAVAILABLE, CONTINUATION_MISMATCH,
    REPEATED_CONTINUATION, EXPIRED_CONTINUATION, CONSUMED_CONTINUATION,
}
data class CollectionProblem(val code: CollectionProblemCode, val sourceProblem: SourceProblem? = null)
data class CollectionExtent(
    val traversal: CollectionTraversal,
    val observedMemberCount: Int,
    val nonPlayableMemberCount: Int,
    val problem: CollectionProblem? = null,
) {
    init { require(observedMemberCount >= 0 && nonPlayableMemberCount in 0..observedMemberCount) }
    val hasKnownHoles: Boolean get() = nonPlayableMemberCount > 0
}

/** Safe public delta/failure data only. The continuation is deliberately not in this value. */
data class CollectionPageData(
    val selection: SourceAcquisitionResult.Collection,
    val observationId: CollectionObservationId,
    val observation: CollectionObservation?,
    val disposition: CollectionPageDisposition,
    val pageIndex: Int,
    val startOrdinal: Int,
    val members: List<CollectionMember>,
    val extent: CollectionExtent,
) {
    init { require(pageIndex >= 0 && startOrdinal >= 0) }
}

data class CollectionLimits(val maxPages: Int = 100, val maxMembers: Int = 5_000, val maxMembersPerPage: Int = 500) {
    init { require(maxPages in 1..100 && maxMembers in 1..5_000 && maxMembersPerPage in 1..500) }
}

/** Immutable sealed prefix. A null cursor is never used as an end-of-list oracle. */
data class CollectionSnapshot(
    val id: CollectionSnapshotId,
    val observationId: CollectionObservationId,
    val parentRequestId: AcquisitionRequestId,
    val parentReceiptId: FirstResultReceiptId,
    val observation: CollectionObservation?,
    val lastCommittedPageIndex: Int?,
    val members: List<CollectionMember>,
    val extent: CollectionExtent,
    val sealedEpochMs: Long,
) {
    init { require(sealedEpochMs >= 0) }
}
