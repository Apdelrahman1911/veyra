package dev.veyra.model

import dev.veyra.source.release.ReleaseObservation
import kotlin.jvm.JvmInline

// Source-only D010 contract. These values require normalized Room implementation;
// no serializer, alternate store, or source/execution capability is introduced here.
private fun requireAcquisitionId(value: String) {
    require(value.length in 1..256 && value.isNotBlank() && value.none { it.code < 32 || it.code in 127..159 })
}

@JvmInline value class AcquisitionRequestId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class AcquisitionAttemptToken(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class FirstResultReceiptId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class CollectionObservationId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class CollectionSnapshotId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class AdmissionScopeId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class ExecutionOccurrenceId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class ExecutionBindingId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class ExecutableSubjectId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class SourceRecordingId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class DownloadBatchId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class DownloadClaimId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class MaterializationAssetId(val value: String) { init { requireAcquisitionId(value) } }
@JvmInline value class ReleaseObservationId(val value: String) { init { requireAcquisitionId(value) } }

enum class AcquisitionKind { MEDIA, ALBUM, RELEASE, SHOW, SOURCE_COLLECTION, COLLECTION_MEMBER }

/** A missing artist is explicit; later catalogue lookup must not complete historical metadata. */
data class CapturedArtistCredit(val artistId: String, val metadata: Artist?)

/** The full immutable core value is shared by ID in normalized storage, never a JSON blob. */
data class CapturedReleaseObservation(val id: ReleaseObservationId, val value: ReleaseObservation)

data class ReleaseTrackCapture(
    val observation: CapturedReleaseObservation,
    val mediumSourceArrayOrdinal: Int,
    val trackSourceArrayOrdinal: Int,
) {
    init { require(mediumSourceArrayOrdinal >= 0 && trackSourceArrayOrdinal >= 0) }
}

/** Detached admitted values, not pointers to current catalogue rows. Capture owns deep copying. */
sealed interface RequestedMetadata {
    val kind: AcquisitionKind
    val completeTitle: String
}

data class MediaCapture(
    val media: MediaItem,
    val artists: List<CapturedArtistCredit> = emptyList(),
    val releaseTrack: ReleaseTrackCapture? = null,
) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.MEDIA
    override val completeTitle: String get() = media.title
}

/** Catalogue track membership is requested metadata, never resolved source membership. */
data class AlbumMemberCapture(
    val position: Int,
    val mediaId: String,
    val media: MediaItem?,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
) {
    init { require(position >= 0) }
}

data class AlbumCapture(
    val album: Album,
    val artists: List<CapturedArtistCredit> = emptyList(),
    val members: List<AlbumMemberCapture> = emptyList(),
) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.ALBUM
    override val completeTitle: String get() = album.title
}

/** RELEASE does not assert Album classification and does not replace edition title with group title. */
data class ReleaseCapture(val observation: CapturedReleaseObservation) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.RELEASE
    override val completeTitle: String get() = observation.value.completeTitle
}

data class ShowCapture(val show: PodcastShow) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.SHOW
    override val completeTitle: String get() = show.title
}

data class SourceCollectionMetadata(
    val provider: SourceProvider,
    val id: String,
    val completeTitle: String,
    val publicUrl: String?,
    val declaredKind: AcquisitionEntryKind?,
    val declaredScope: CollectionScope?,
    val creator: String? = null,
    val artworkUrl: String? = null,
    val description: String? = null,
    val observationProvenance: String? = null,
)

data class SourceCollectionCapture(val collection: SourceCollectionMetadata) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.SOURCE_COLLECTION
    override val completeTitle: String get() = collection.completeTitle
}

/** B's frozen raw entry/title and exact snapshot occurrence survive a later first result C. */
data class CollectionMemberCapture(
    val snapshotId: CollectionSnapshotId,
    val member: CollectionMember,
) : RequestedMetadata {
    override val kind: AcquisitionKind get() = AcquisitionKind.COLLECTION_MEMBER
    override val completeTitle: String get() = member.entry.title.orEmpty()
}

data class AcquisitionRequest(
    val id: AcquisitionRequestId,
    val completeTitle: String,
    val metadata: RequestedMetadata,
    val capturedEpochMs: Long,
    val catalogRevision: Long? = null,
    val providerRevision: String? = null,
) {
    init {
        require(completeTitle == metadata.completeTitle)
        require(capturedEpochMs >= 0 && (catalogRevision == null || catalogRevision >= 0))
    }
    val kind: AcquisitionKind get() = metadata.kind
    /** Opaque wire correlation, not a claim that an app catalogue revision is provider-atomic. */
    val selectionMetadataRevision: String? get() = providerRevision ?: catalogRevision?.let { "catalog:$it" }
    override fun toString() = "AcquisitionRequest(kind=$kind,metadata=redacted)"
}

enum class AcquisitionEntryKind { VIDEO, CHANNEL, PLAYLIST }
enum class SourceEntryIssue { UNAVAILABLE, UNSUPPORTED, INVALID_METADATA, TEXT_LIMIT, PARSE_LIMIT }
enum class SourceProblemCode {
    NETWORK, ACCESS_REQUIRED, NOT_FOUND, RATE_LIMITED, REDIRECTED, HTTP_ERROR, RESPONSE_LIMIT, INVALID_RESPONSE,
}
data class SourceProblem(val code: SourceProblemCode, val httpStatus: Int? = null)

/** Every upstream public raw field, including null versus empty. Null kind means unknown. */
data class SourceEntry(
    val kind: AcquisitionEntryKind?,
    val id: String?,
    val title: String?,
    val publicUrl: String?,
    val issue: SourceEntryIssue? = null,
    val ordinal: Int = 0,
) {
    init { require(ordinal >= 0) }
    override fun toString() = "SourceEntry(kind=$kind,ordinal=$ordinal,issue=$issue,metadata=redacted)"
}

/** Display metadata is optional and can never replace raw entry identity or fill a hole. */
data class SourceDisplayItem(
    val id: String,
    val title: String,
    val publicUrl: String,
    val kind: AcquisitionEntryKind,
    val thumbnailUrl: String? = null,
    val subtitle: String? = null,
    val durationText: String? = null,
) {
    override fun toString() = "SourceDisplayItem(kind=$kind,metadata=redacted)"
}

/** Source observation has no app receipt/binding ID; the durable commit assigns those IDs. */
data class FirstResultObservation(
    val requestId: AcquisitionRequestId,
    val queryTitle: String,
    val entry: SourceEntry,
    val canonicalUrl: String?,
) {
    override fun toString() = "FirstResultObservation(kind=${entry.kind},ordinal=${entry.ordinal},metadata=redacted)"
}

enum class AcquisitionProblemCode {
    INVALID_REQUEST, NO_FIRST_RESULT, SOURCE_FAILURE, INVALID_FIRST_RESULT,
    FIRST_RESULT_UNAVAILABLE, FIRST_RESULT_UNSUPPORTED, MEMBER_NOT_PLAYABLE,
}
data class AcquisitionProblem(val code: AcquisitionProblemCode, val sourceProblem: SourceProblem? = null)

/** This is a source selection, not a native target, binding, playback plan or Ready state. */
sealed interface SourceAcquisitionResult {
    val requestId: AcquisitionRequestId

    data class Single(
        override val requestId: AcquisitionRequestId,
        val receipt: FirstResultObservation,
        val recording: SourceDisplayItem,
    ) : SourceAcquisitionResult

    data class Collection(
        override val requestId: AcquisitionRequestId,
        val receipt: FirstResultObservation,
        val identity: CollectionIdentity,
        val scope: CollectionScope,
    ) : SourceAcquisitionResult

    data class Unplayable(
        override val requestId: AcquisitionRequestId,
        val receipt: FirstResultObservation?,
        val problem: AcquisitionProblem,
    ) : SourceAcquisitionResult
}
