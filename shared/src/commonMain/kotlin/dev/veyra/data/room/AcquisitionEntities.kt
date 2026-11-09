package dev.veyra.data.room

import androidx.room.*
import dev.veyra.model.*
import dev.veyra.source.release.*

/**
 * Normalized immutable D010/D011 provenance alongside the ONE existing physical download set.
 * No raw response/JSON/list/cursor/blob columns. Historical captures do not look up today's
 * catalogue to fill missing values. Canonical Room commands enforce variant and graph invariants
 * inside their IMMEDIATE transaction and retain the intended-graph readback guard.
 */

data class CapturedArtistColumns(
    val id: String,
    val name: String,
    val sortName: String,
    val description: String,
    val artworkUrl: String?,
    @Embedded(prefix = "source_") val source: SourceRef?,
)

data class CapturedMediaColumns(
    val id: String,
    val title: String,
    val kind: MediaKind,
    @Embedded(prefix = "source_") val source: SourceRef,
    val creator: String,
    val artworkUrl: String?,
    val advisoryDurationMs: Long?,
    val showId: String?,
    val description: String,
    val publishedEpochMs: Long?,
    val explicit: Boolean,
    val albumLabel: String?,
    val artistLabel: String?,
    val episodeNumber: Int?,
    val seasonNumber: Int?,
    val albumId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val localArtworkPath: String?,
    @Embedded(prefix = "attribution_") val attribution: Attribution?,
)

data class CapturedAlbumColumns(
    val id: String,
    val title: String,
    val artworkUrl: String?,
    val description: String,
    val releaseYear: Int?,
    @Embedded(prefix = "source_") val source: SourceRef?,
)

data class CapturedShowColumns(
    val id: String,
    val title: String,
    val feedUrl: String,
    val publisher: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val language: String?,
    val explicit: Boolean,
    val etag: String?,
    val lastModified: String?,
    val lastRefreshEpochMs: Long?,
    val copyright: String?,
    val localArtworkPath: String?,
    @Embedded(prefix = "attribution_") val attribution: Attribution?,
)

@Entity(
    tableName = "acquisition_requests", primaryKeys = ["id"],
)
data class AcquisitionRequestEntity(
    val id: String,
    val kind: AcquisitionKind,
    val completeTitle: String,
    val capturedEpochMs: Long,
    val catalogRevision: Long?,
    val providerRevision: String?,
)

@Entity(
    tableName = "request_media_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestMediaMetadataEntity(
    val requestId: String,
    @Embedded(prefix = "media_") val media: CapturedMediaColumns,
)

@Entity(
    tableName = "request_media_artists", primaryKeys = ["requestId", "position"],
    foreignKeys = [
        ForeignKey(entity = RequestMediaMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestMediaArtistEntity(
    val requestId: String,
    val position: Int,
    val artistId: String,
    @Embedded(prefix = "metadata_") val metadata: CapturedArtistColumns?,
)

@Entity(
    tableName = "request_album_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestAlbumMetadataEntity(
    val requestId: String,
    @Embedded(prefix = "album_") val album: CapturedAlbumColumns,
)

@Entity(
    tableName = "request_album_artists", primaryKeys = ["requestId", "position"],
    foreignKeys = [
        ForeignKey(entity = RequestAlbumMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestAlbumArtistEntity(
    val requestId: String,
    val position: Int,
    val artistId: String,
    @Embedded(prefix = "metadata_") val metadata: CapturedArtistColumns?,
)

/** Missing catalogue media is a null embedded capture; repeated media IDs remain distinct positions. */
@Entity(
    tableName = "request_album_members", primaryKeys = ["requestId", "position"],
    foreignKeys = [
        ForeignKey(entity = RequestAlbumMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestAlbumMemberEntity(
    val requestId: String,
    val position: Int,
    val mediaId: String,
    val discNumber: Int?,
    val trackNumber: Int?,
    @Embedded(prefix = "media_") val media: CapturedMediaColumns?,
)

@Entity(
    tableName = "request_album_member_artists", primaryKeys = ["requestId", "memberPosition", "artistPosition"],
    foreignKeys = [
        ForeignKey(entity = RequestAlbumMemberEntity::class, parentColumns = ["requestId", "position"], childColumns = ["requestId", "memberPosition"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId", "memberPosition"]),
    ],
)
data class RequestAlbumMemberArtistEntity(
    val requestId: String,
    val memberPosition: Int,
    val artistPosition: Int,
    val artistId: String,
)

@Entity(
    tableName = "request_show_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestShowMetadataEntity(
    val requestId: String,
    @Embedded(prefix = "show_") val show: CapturedShowColumns,
)

@Entity(
    tableName = "request_show_aliases", primaryKeys = ["requestId", "feedUrl"],
    foreignKeys = [
        ForeignKey(entity = RequestShowMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestShowAliasEntity(
    val requestId: String,
    val feedUrl: String,
)

@Entity(
    tableName = "request_show_members", primaryKeys = ["requestId", "position"],
    foreignKeys = [
        ForeignKey(entity = RequestShowMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestShowMemberEntity(
    val requestId: String,
    val position: Int,
    val episodeId: String,
)

@Entity(
    tableName = "request_source_collection_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class RequestSourceCollectionMetadataEntity(
    val requestId: String,
    val provider: SourceProvider,
    val collectionId: String,
    val completeTitle: String,
    val publicUrl: String?,
    val declaredKind: AcquisitionEntryKind?,
    val declaredScope: CollectionScope?,
    val creator: String?,
    val artworkUrl: String?,
    val description: String?,
    val observationProvenance: String?,
)

/** Immutable CC0 core observation. Source URL is derived from observed edition ID, never an audio locator. */
@Entity(
    tableName = "release_observations", primaryKeys = ["id"],
    indices = [
        Index(value = ["requestedReleaseMbid"]),
        Index(value = ["observedReleaseMbid"]),
        Index(value = ["coreDigest"]),
    ],
)
data class ReleaseObservationEntity(
    val id: String,
    val requestedReleaseMbid: String,
    val observedReleaseMbid: String,
    val completeTitle: String,
    val status: String?,
    val date: String?,
    val country: String?,
    val barcode: String?,
    val disambiguation: String?,
    val textLanguage: String?,
    val textScript: String?,
    val packaging: String?,
    val packagingMbid: String?,
    val membershipStatus: ReleaseMembershipStatus,
    val fetchedAtEpochMs: Long,
    val coreDigest: String,
    val etag: String?,
)

@Entity(
    tableName = "release_observation_groups", primaryKeys = ["observationId"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseObservationGroupEntity(
    val observationId: String,
    val mbid: String?,
    val title: String?,
    val primaryType: String?,
)

@Entity(
    tableName = "release_group_secondary_types", primaryKeys = ["observationId", "position"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationGroupEntity::class, parentColumns = ["observationId"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseGroupSecondaryTypeEntity(
    val observationId: String,
    val position: Int,
    val value: String,
)

@Entity(
    tableName = "release_artist_credits", primaryKeys = ["observationId", "position"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseArtistCreditEntity(
    val observationId: String,
    val position: Int,
    val sourceArrayOrdinal: Int,
    val artistMbid: String?,
    val creditedName: String?,
    val joinPhrase: String?,
    val artistName: String?,
)

@Entity(
    tableName = "release_events", primaryKeys = ["observationId", "eventPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseEventEntity(
    val observationId: String,
    val eventPosition: Int,
    val sourceArrayOrdinal: Int,
    val date: String?,
    val areaMbid: String?,
    val areaName: String?,
)

@Entity(
    tableName = "release_event_countries", primaryKeys = ["observationId", "eventPosition", "countryPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseEventEntity::class, parentColumns = ["observationId", "eventPosition"], childColumns = ["observationId", "eventPosition"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId", "eventPosition"]),
    ],
)
data class ReleaseEventCountryEntity(
    val observationId: String,
    val eventPosition: Int,
    val countryPosition: Int,
    val countryCode: String,
)

@Entity(
    tableName = "release_labels", primaryKeys = ["observationId", "position"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseLabelEntity(
    val observationId: String,
    val position: Int,
    val sourceArrayOrdinal: Int,
    val labelMbid: String?,
    val name: String?,
    val catalogueNumber: String?,
)

@Entity(
    tableName = "release_media", primaryKeys = ["observationId", "sourceArrayOrdinal"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
        Index(value = ["observationId", "listPosition"], unique = true),
    ],
)
data class ReleaseMediumEntity(
    val observationId: String,
    val sourceArrayOrdinal: Int,
    val listPosition: Int,
    val mediumMbid: String?,
    val declaredPosition: Int?,
    val title: String?,
    val format: String?,
    val declaredTrackCount: Int?,
    val trackOffset: Int?,
    val membershipStatus: ReleaseMembershipStatus,
)

/** Recording MBID is not unique: every source-array track occurrence, including duplicates and missing IDs, survives. */
@Entity(
    tableName = "release_tracks", primaryKeys = ["observationId", "mediumArrayOrdinal", "sourceArrayOrdinal"],
    foreignKeys = [
        ForeignKey(entity = ReleaseMediumEntity::class, parentColumns = ["observationId", "sourceArrayOrdinal"], childColumns = ["observationId", "mediumArrayOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId", "mediumArrayOrdinal"]),
        Index(value = ["observationId", "mediumArrayOrdinal", "listPosition"], unique = true),
    ],
)
data class ReleaseTrackEntity(
    val observationId: String,
    val mediumArrayOrdinal: Int,
    val sourceArrayOrdinal: Int,
    val listPosition: Int,
    val declaredPosition: Int?,
    val printedNumber: String?,
    val trackMbid: String?,
    val completeTrackTitle: String?,
    val lengthMs: Long?,
    val recordingMbid: String?,
    val recordingTitle: String?,
)

@Entity(
    tableName = "release_track_artist_credits", primaryKeys = ["observationId", "mediumArrayOrdinal", "trackArrayOrdinal", "creditPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseTrackEntity::class, parentColumns = ["observationId", "mediumArrayOrdinal", "sourceArrayOrdinal"], childColumns = ["observationId", "mediumArrayOrdinal", "trackArrayOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId", "mediumArrayOrdinal", "trackArrayOrdinal"]),
    ],
)
data class ReleaseTrackArtistCreditEntity(
    val observationId: String,
    val mediumArrayOrdinal: Int,
    val trackArrayOrdinal: Int,
    val creditPosition: Int,
    val sourceArrayOrdinal: Int,
    val artistMbid: String?,
    val creditedName: String?,
    val joinPhrase: String?,
    val artistName: String?,
)

/** List position preserves repeated problems; affected ordinals are distinct from the owning occurrence. */
@Entity(
    tableName = "release_summary_problems", primaryKeys = ["observationId", "problemPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseSummaryProblemEntity(
    val observationId: String,
    val problemPosition: Int,
    @Embedded(prefix = "problem_") val problem: ReleaseMetadataProblem,
)

/** List position preserves repeated problems; affected ordinals are distinct from the owning occurrence. */
@Entity(
    tableName = "release_core_problems", primaryKeys = ["observationId", "problemPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class ReleaseCoreProblemEntity(
    val observationId: String,
    val problemPosition: Int,
    @Embedded(prefix = "problem_") val problem: ReleaseMetadataProblem,
)

/** List position preserves repeated problems; affected ordinals are distinct from the owning occurrence. */
@Entity(
    tableName = "release_medium_problems", primaryKeys = ["observationId", "ownerMediumOrdinal", "problemPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseMediumEntity::class, parentColumns = ["observationId", "sourceArrayOrdinal"], childColumns = ["observationId", "ownerMediumOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId", "ownerMediumOrdinal"]),
    ],
)
data class ReleaseMediumProblemEntity(
    val observationId: String,
    val ownerMediumOrdinal: Int,
    val problemPosition: Int,
    @Embedded(prefix = "problem_") val problem: ReleaseMetadataProblem,
)

/** List position preserves repeated problems; affected ordinals are distinct from the owning occurrence. */
@Entity(
    tableName = "release_track_problems", primaryKeys = ["observationId", "ownerMediumOrdinal", "ownerTrackOrdinal", "problemPosition"],
    foreignKeys = [
        ForeignKey(entity = ReleaseTrackEntity::class, parentColumns = ["observationId", "mediumArrayOrdinal", "sourceArrayOrdinal"], childColumns = ["observationId", "ownerMediumOrdinal", "ownerTrackOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId", "ownerMediumOrdinal", "ownerTrackOrdinal"]),
    ],
)
data class ReleaseTrackProblemEntity(
    val observationId: String,
    val ownerMediumOrdinal: Int,
    val ownerTrackOrdinal: Int,
    val problemPosition: Int,
    @Embedded(prefix = "problem_") val problem: ReleaseMetadataProblem,
)

@Entity(
    tableName = "request_release_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["observationId"]),
    ],
)
data class RequestReleaseMetadataEntity(
    val requestId: String,
    val observationId: String,
)

@Entity(
    tableName = "request_media_release_occurrences", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = RequestMediaMetadataEntity::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ReleaseTrackEntity::class, parentColumns = ["observationId", "mediumArrayOrdinal", "sourceArrayOrdinal"], childColumns = ["observationId", "mediumArrayOrdinal", "trackArrayOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["observationId", "mediumArrayOrdinal", "trackArrayOrdinal"]),
    ],
)
data class RequestMediaReleaseOccurrenceEntity(
    val requestId: String,
    val observationId: String,
    val mediumArrayOrdinal: Int,
    val trackArrayOrdinal: Int,
)

@Entity(
    tableName = "release_catalog_entries", primaryKeys = ["requestedReleaseMbid"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["latestObservationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["latestObservationId"]),
    ],
)
data class ReleaseCatalogEntryEntity(
    val requestedReleaseMbid: String,
    val latestObservationId: String,
    val updatedEpochMs: Long,
)

/** Refreshing a catalogue pointer never changes this explicitly pinned observation. */
@Entity(
    tableName = "saved_releases", primaryKeys = ["requestedReleaseMbid"],
    foreignKeys = [
        ForeignKey(entity = ReleaseObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["observationId"]),
    ],
)
data class SavedReleaseEntity(
    val requestedReleaseMbid: String,
    val observationId: String,
    val pinnedEpochMs: Long,
)

/** Typed raw-zero observation XOR unchanged schema2 receipt. Null legacy raw fields stay unknown, not reconstructed. */
@Entity(
    tableName = "first_result_receipts", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["legacyMediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"], unique = true),
        Index(value = ["legacyMediaId"]),
    ],
)
data class FirstResultReceiptEntity(
    val id: String,
    val origin: AcquisitionOrigin,
    val requestId: String?,
    val legacyMediaId: String?,
    val queryTitle: String,
    val rawOrdinal: Int?,
    val rawKind: AcquisitionEntryKind?,
    val rawId: String?,
    val rawTitle: String?,
    val rawPublicUrl: String?,
    val rawIssue: SourceEntryIssue?,
    val canonicalUrl: String?,
    val observedEpochMs: Long?,
    val legacyFirstResultKind: FirstResultKind?,
    val legacyFirstResultId: String?,
    val legacyFirstResultTitle: String?,
    val legacySelectedVideoId: String?,
    val legacyResolvedEpochMs: Long?,
    val legacySelectionOrdinal: Int?,
)

@Entity(
    tableName = "source_recordings", primaryKeys = ["id"],
    indices = [
        Index(value = ["provider", "canonicalId"], unique = true),
    ],
)
data class SourceRecordingEntity(
    val id: String,
    val provider: SourceProvider,
    val canonicalId: String,
    val canonicalUrl: String,
)

/** kind is CATALOG_MEDIA or SOURCE_RECORDING; the canonical owner enforces the exact XOR variant. */
@Entity(
    tableName = "executable_subjects", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = SourceRecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["mediaId"], unique = true),
        Index(value = ["recordingId"], unique = true),
    ],
)
data class ExecutableSubjectEntity(
    val id: String,
    val kind: String,
    val mediaId: String?,
    val recordingId: String?,
)

@Entity(
    tableName = "execution_bindings", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = FirstResultReceiptEntity::class, parentColumns = ["id"], childColumns = ["firstReceiptId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = SourceRecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["firstReceiptId"], unique = true),
        Index(value = ["recordingId"]),
    ],
)
data class ExecutionBindingEntity(
    val id: String,
    val subjectId: String,
    val firstReceiptId: String,
    val recordingId: String?,
    val origin: ExecutionBindingOrigin,
)

@Entity(
    tableName = "acquisition_outcomes", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = FirstResultReceiptEntity::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["receiptId"]),
        Index(value = ["bindingId"]),
    ],
)
data class AcquisitionOutcomeEntity(
    val requestId: String,
    val kind: String,
    val receiptId: String?,
    val bindingId: String?,
    val collectionKind: AcquisitionEntryKind?,
    val collectionId: String?,
    val collectionCanonicalUrl: String?,
    val collectionScope: CollectionScope?,
    val failureStage: AcquisitionFailureStage?,
    val problemCode: AcquisitionProblemCode?,
    val sourceProblemCode: SourceProblemCode?,
    val sourceHttpStatus: Int?,
)

@Entity(
    tableName = "acquisition_jobs", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
    ],
)
data class AcquisitionJobEntity(
    val requestId: String,
    val state: AcquisitionJobState,
    val leaseToken: String?,
    val leaseOwner: String?,
    val claimedEpochMs: Long?,
    val expiresEpochMs: Long?,
    val updatedEpochMs: Long,
)

@Entity(
    tableName = "collection_runs", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["parentRequestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = FirstResultReceiptEntity::class, parentColumns = ["id"], childColumns = ["parentReceiptId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["parentRequestId"]),
        Index(value = ["parentReceiptId"]),
    ],
)
data class CollectionRunEntity(
    val id: String,
    val parentRequestId: String,
    val parentReceiptId: String,
    val collectionKind: AcquisitionEntryKind,
    val collectionId: String,
    val collectionCanonicalUrl: String,
    val scope: CollectionScope,
    val nextPageIndex: Int,
    val nextOrdinal: Int,
    val knownHoleCount: Int,
    val traversal: CollectionTraversal,
    val observedMemberCount: Int,
    val nonPlayableMemberCount: Int,
    val problemCode: CollectionProblemCode?,
    val sourceProblemCode: SourceProblemCode?,
    val sourceHttpStatus: Int?,
    val retryableFailuresAtFrontier: Int,
    val stoppedReason: String?,
    val startedEpochMs: Long,
    val updatedEpochMs: Long,
)

@Entity(
    tableName = "collection_observations", primaryKeys = ["runId"],
    foreignKeys = [
        ForeignKey(entity = CollectionRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["parentRequestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["parentRequestId"]),
    ],
)
data class CollectionObservationEntity(
    val runId: String,
    val parentRequestId: String,
    val collectionKind: AcquisitionEntryKind,
    val collectionId: String,
    val collectionCanonicalUrl: String,
    val scope: CollectionScope,
    val title: String,
    val titleOrigin: CollectionTitleOrigin,
    val sourceUrl: String,
    val consistency: CollectionConsistency,
)

@Entity(
    tableName = "collection_identity_evidence", primaryKeys = ["runId", "evidence"],
    foreignKeys = [
        ForeignKey(entity = CollectionObservationEntity::class, parentColumns = ["runId"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId"]),
    ],
)
data class CollectionIdentityEvidenceEntity(
    val runId: String,
    val evidence: CollectionIdentityEvidence,
)

@Entity(
    tableName = "collection_page_commits", primaryKeys = ["runId", "pageIndex"],
    foreignKeys = [
        ForeignKey(entity = CollectionRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId"]),
    ],
)
data class CollectionPageCommitEntity(
    val runId: String,
    val pageIndex: Int,
    val startOrdinal: Int,
    val memberCount: Int,
    val traversal: CollectionTraversal,
    val observedMemberCount: Int,
    val nonPlayableMemberCount: Int,
    val problemCode: CollectionProblemCode?,
    val sourceProblemCode: SourceProblemCode?,
    val sourceHttpStatus: Int?,
    val receivedEpochMs: Long,
)

@Entity(
    tableName = "collection_page_failures", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = CollectionRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId", "pageIndex", "startOrdinal"]),
        Index(value = ["runId"]),
    ],
)
data class CollectionPageFailureEntity(
    val id: String,
    val runId: String,
    val pageIndex: Int,
    val startOrdinal: Int,
    val traversal: CollectionTraversal,
    val observedMemberCount: Int,
    val nonPlayableMemberCount: Int,
    val problemCode: CollectionProblemCode?,
    val sourceProblemCode: SourceProblemCode?,
    val sourceHttpStatus: Int?,
    val receivedEpochMs: Long,
)

@Entity(
    tableName = "collection_members", primaryKeys = ["runId", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = CollectionObservationEntity::class, parentColumns = ["runId"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionPageCommitEntity::class, parentColumns = ["runId", "pageIndex"], childColumns = ["runId", "pageIndex"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["runId", "pageIndex"]),
    ],
)
data class CollectionMemberEntity(
    val runId: String,
    val ordinal: Int,
    val pageIndex: Int,
    @Embedded(prefix = "raw_") val entry: SourceEntry,
    @Embedded(prefix = "display_") val display: SourceDisplayItem?,
)

@Entity(
    tableName = "collection_snapshots", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = CollectionRunEntity::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["runId"]),
        Index(value = ["id", "runId"], unique = true),
    ],
)
data class CollectionSnapshotEntity(
    val id: String,
    val runId: String,
    /** A later run header must not retroactively fill an earlier header-less sealed prefix. */
    val observationCaptured: Boolean,
    val lastCommittedPageIndex: Int?,
    val traversal: CollectionTraversal,
    val observedMemberCount: Int,
    val nonPlayableMemberCount: Int,
    val problemCode: CollectionProblemCode?,
    val sourceProblemCode: SourceProblemCode?,
    val sourceHttpStatus: Int?,
    val sealedEpochMs: Long,
)

@Entity(
    tableName = "collection_snapshot_members", primaryKeys = ["snapshotId", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = CollectionSnapshotEntity::class, parentColumns = ["id", "runId"], childColumns = ["snapshotId", "runId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionMemberEntity::class, parentColumns = ["runId", "ordinal"], childColumns = ["runId", "sourceOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["snapshotId", "runId"]),
        Index(value = ["runId", "sourceOrdinal"]),
    ],
)
data class CollectionSnapshotMemberEntity(
    val snapshotId: String,
    val ordinal: Int,
    val runId: String,
    val sourceOrdinal: Int,
)

@Entity(
    tableName = "request_collection_member_metadata", primaryKeys = ["requestId"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionSnapshotMemberEntity::class, parentColumns = ["snapshotId", "ordinal"], childColumns = ["snapshotId", "ordinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["snapshotId", "ordinal"]),
    ],
)
data class RequestCollectionMemberMetadataEntity(
    val requestId: String,
    val snapshotId: String,
    val ordinal: Int,
)

@Entity(
    tableName = "admission_scopes", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["parentRequestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionSnapshotEntity::class, parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["parentRequestId"]),
        Index(value = ["snapshotId"]),
    ],
)
data class AdmissionScopeEntity(
    val id: String,
    val kind: AdmissionScopeKind,
    val parentRequestId: String?,
    val snapshotId: String?,
    val controlsMode: PlaybackControlsMode,
    val acknowledgesUnobservedSuffix: Boolean,
    val admittedEpochMs: Long,
)

@Entity(
    tableName = "admission_scope_members", primaryKeys = ["scopeId", "position"],
    foreignKeys = [
        ForeignKey(entity = AdmissionScopeEntity::class, parentColumns = ["id"], childColumns = ["scopeId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionSnapshotMemberEntity::class, parentColumns = ["snapshotId", "ordinal"], childColumns = ["snapshotId", "snapshotOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["scopeId"]),
        Index(value = ["snapshotId", "snapshotOrdinal"]),
        Index(value = ["requestId"]),
    ],
)
data class AdmissionScopeMemberEntity(
    val scopeId: String,
    val position: Int,
    val snapshotId: String?,
    val snapshotOrdinal: Int?,
    val requestId: String?,
    val disposition: AdmissionDisposition,
    val exclusion: AdmissionExclusion?,
)

@Entity(
    tableName = "execution_occurrences", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = CollectionSnapshotMemberEntity::class, parentColumns = ["snapshotId", "ordinal"], childColumns = ["snapshotId", "snapshotOrdinal"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AdmissionScopeMemberEntity::class, parentColumns = ["scopeId", "position"], childColumns = ["scopeId", "scopePosition"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestId"]),
        Index(value = ["snapshotId", "snapshotOrdinal"]),
        Index(value = ["scopeId", "scopePosition"]),
        Index(value = ["subjectId"]),
        Index(value = ["bindingId"]),
    ],
)
data class ExecutionOccurrenceEntity(
    val id: String,
    val origin: AcquisitionOrigin,
    val requestId: String?,
    val snapshotId: String?,
    val snapshotOrdinal: Int?,
    val scopeId: String?,
    val scopePosition: Int?,
    val subjectId: String?,
    val bindingId: String?,
    val controlsMode: PlaybackControlsMode,
    val selectionState: OccurrenceSelectionState,
    val admittedEpochMs: Long?,
)

@Entity(
    tableName = "execution_queue_revisions", primaryKeys = ["contextId"],
    foreignKeys = [
        ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["contextId"]),
    ],
)
data class ExecutionQueueRevisionEntity(
    val contextId: QueueContext,
    val editRevision: Long,
)

@Entity(
    tableName = "queue_members", primaryKeys = ["occurrenceId"],
    foreignKeys = [
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["occurrenceId"]),
        Index(value = ["contextId"]),
        Index(value = ["contextId", "position"], unique = true),
        Index(value = ["contextId", "originalPosition"], unique = true),
        Index(value = ["contextId", "occurrenceId"], unique = true),
    ],
)
data class ExecutionQueueMemberEntity(
    val occurrenceId: String,
    val contextId: QueueContext,
    val position: Int,
    val originalPosition: Int,
)

@Entity(
    tableName = "queue_heads", primaryKeys = ["contextId"],
    foreignKeys = [
        ForeignKey(entity = QueueContextEntity::class, parentColumns = ["id"], childColumns = ["contextId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionQueueMemberEntity::class, parentColumns = ["contextId", "occurrenceId"], childColumns = ["contextId", "occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["contextId"]),
        Index(value = ["contextId", "occurrenceId"]),
    ],
)
data class ExecutionQueueHeadEntity(
    val contextId: QueueContext,
    val occurrenceId: String,
)

@Entity(
    tableName = "occurrence_checkpoints", primaryKeys = ["occurrenceId"],
    foreignKeys = [
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["occurrenceId"]),
        Index(value = ["bindingId"]),
    ],
)
data class OccurrenceCheckpointEntity(
    val occurrenceId: String,
    val bindingId: String,
    val positionMs: Long,
    val measuredDurationMs: Long?,
    val observedEpochMs: Long,
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val endedNaturally: Boolean,
)

@Entity(
    tableName = "source_recording_resume", primaryKeys = ["subjectId", "controlsMode"],
    foreignKeys = [
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["occurrenceId"]),
    ],
)
data class SourceRecordingResumeEntity(
    val subjectId: String,
    val controlsMode: PlaybackControlsMode,
    val occurrenceId: String,
)

@Entity(
    tableName = "catalog_progress", primaryKeys = ["mediaId"],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["lastOccurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["executionBindingId"]),
        Index(value = ["lastOccurrenceId"]),
    ],
)
data class CatalogProgressEntity(
    val mediaId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val played: Boolean,
    val updatedEpochMs: Long,
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val executionBindingId: String?,
    val lastOccurrenceId: String?,
)

@Entity(
    tableName = "listening_history", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["subjectId", "startedEpochMs"]),
        Index(value = ["subjectId"]),
        Index(value = ["occurrenceId"]),
        Index(value = ["bindingId"]),
        Index(value = ["position"], unique = true),
    ],
)
data class ListeningHistoryEntity(
    val id: String,
    val subjectId: String,
    val origin: AcquisitionOrigin,
    val occurrenceId: String?,
    val bindingId: String?,
    val startedEpochMs: Long,
    val lastPositionMs: Long,
    val endedNaturally: Boolean,
    val position: Int,
)

@Entity(
    tableName = "favorite_subjects", primaryKeys = ["subjectId"],
    foreignKeys = [
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["subjectId"]),
        Index(value = ["requestId"]),
        Index(value = ["bindingId"]),
        Index(value = ["occurrenceId"]),
    ],
)
data class FavoriteSubjectEntity(
    val subjectId: String,
    val origin: SavedLibraryOrigin,
    val requestId: String?,
    val bindingId: String?,
    val occurrenceId: String?,
    val controlsMode: PlaybackControlsMode?,
)

@Entity(
    tableName = "playlist_members", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["playlistId"]),
        Index(value = ["subjectId"]),
        Index(value = ["requestId"]),
        Index(value = ["bindingId"]),
        Index(value = ["occurrenceId"]),
        Index(value = ["playlistId", "position"], unique = true),
    ],
)
data class PlaylistMemberEntity(
    val id: String,
    val playlistId: String,
    val subjectId: String,
    val origin: SavedLibraryOrigin,
    val requestId: String?,
    val bindingId: String?,
    val occurrenceId: String?,
    val controlsMode: PlaybackControlsMode?,
    val position: Int,
)

@Entity(
    tableName = "download_batches", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = AdmissionScopeEntity::class, parentColumns = ["id"], childColumns = ["scopeId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["scopeId"]),
        Index(value = ["id", "scopeId"], unique = true),
    ],
)
data class DownloadBatchEntity(
    val id: String,
    val scopeId: String,
    val intent: DownloadBatchIntent,
    val intentRevision: Long,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
)

@Entity(
    tableName = "download_batch_members", primaryKeys = ["batchId", "position"],
    foreignKeys = [
        ForeignKey(entity = DownloadBatchEntity::class, parentColumns = ["id", "scopeId"], childColumns = ["batchId", "scopeId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AdmissionScopeMemberEntity::class, parentColumns = ["scopeId", "position"], childColumns = ["scopeId", "scopeMemberPosition"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionOccurrenceEntity::class, parentColumns = ["id"], childColumns = ["occurrenceId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["requestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["requiredBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = DownloadClaimEntity::class, parentColumns = ["id"], childColumns = ["claimId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["batchId", "scopeId"]),
        Index(value = ["scopeId", "scopeMemberPosition"]),
        Index(value = ["occurrenceId"]),
        Index(value = ["requestId"]),
        Index(value = ["requiredBindingId"]),
        Index(value = ["claimId"], unique = true),
    ],
)
data class DownloadBatchMemberEntity(
    val batchId: String,
    val position: Int,
    val scopeId: String,
    val scopeMemberPosition: Int,
    val occurrenceId: String,
    val requestId: String,
    val requiredBindingId: String?,
    val claimId: String?,
    val state: DownloadBatchMemberState,
    @Embedded(prefix = "failure_") val failure: AppFailure?,
)

/** SCHEMA2_STANDALONE alone may have unknown requested binding. An empty claim join is never deletion authority. */
@Entity(
    tableName = "download_claims", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["physicalJobId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["standaloneJobId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = DownloadBatchMemberEntity::class, parentColumns = ["batchId", "position"], childColumns = ["batchId", "memberPosition"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["requestedBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["physicalJobId"]),
        Index(value = ["standaloneJobId"], unique = true),
        Index(value = ["batchId", "memberPosition"], unique = true),
        Index(value = ["requestedBindingId"]),
    ],
)
data class DownloadClaimEntity(
    val id: String,
    val ownerKind: String,
    val physicalJobId: String,
    val standaloneJobId: String?,
    val batchId: String?,
    val memberPosition: Int?,
    val requestedBindingId: String?,
    val intent: DownloadBatchIntent,
    val retainCompletedAsset: Boolean,
    val updatedEpochMs: Long,
    val generation: Long,
    val ownerIntentRevision: Long,
)

/**
 * Explicit adopted NEW generation after unknown schema2 bytes/owners positively drained.
 * Old attempts keep their original producer. This history neither rewrites them nor grants
 * removal authority, and no row is created by migration merely because old C was unknown.
 */
@Entity(
    tableName = "download_producer_adoptions", primaryKeys = ["jobId", "newOwnershipGeneration"],
    foreignKeys = [
        ForeignKey(entity = DownloadEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["oldMediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["oldBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = SourceRecordingEntity::class, parentColumns = ["id"], childColumns = ["oldRecordingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = AcquisitionRequestEntity::class, parentColumns = ["id"], childColumns = ["newRequestId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["newBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = SourceRecordingEntity::class, parentColumns = ["id"], childColumns = ["newRecordingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["jobId"]),
        Index(value = ["oldMediaId"]),
        Index(value = ["oldBindingId"]),
        Index(value = ["oldRecordingId"]),
        Index(value = ["newRequestId"]),
        Index(value = ["newBindingId"]),
        Index(value = ["newRecordingId"]),
        Index(value = ["subjectId"]),
    ],
)
data class PhysicalDownloadAdoptionEntity(
    val jobId: String,
    val newOwnershipGeneration: Long,
    val oldOwnershipGeneration: Long,
    val oldMediaId: String,
    val oldBindingId: String?,
    val oldRecordingId: String?,
    val newRequestId: String,
    val newBindingId: String,
    val newRecordingId: String,
    val subjectId: String,
    val adoptedEpochMs: Long,
)

/** Immutable R1 generation retained after the live file is deleted. Historical materializer IDs are facts, not FKs to a future job or delete authority. */
@Entity(
    tableName = "materialization_receipts", primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(entity = MediaEntity::class, parentColumns = ["id"], childColumns = ["mediaId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutableSubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = SourceRecordingEntity::class, parentColumns = ["id"], childColumns = ["recordingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["executionBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["subjectId"]),
        Index(value = ["recordingId"]),
        Index(value = ["executionBindingId"]),
    ],
)
data class MaterializationReceiptEntity(
    val id: String,
    val mediaId: String?,
    val subjectId: String,
    val producerOrigin: PhysicalDownloadProducerOrigin,
    val recordingId: String?,
    val executionBindingId: String?,
    val sizeBytes: Long,
    val kind: OfflineAssetKind,
    val mimeType: String?,
    val codecs: String?,
    val sha256: String?,
    val representationId: String?,
    val validatorGeneration: Long,
    val completedEpochMs: Long,
    val measuredDurationMs: Long?,
    val materializerDownloadId: String?,
    val materializerAttemptId: String?,
    val ownershipGeneration: Long,
)

@Entity(
    tableName = "asset_uses", primaryKeys = ["requestedBindingId", "materializationAssetId"],
    foreignKeys = [
        ForeignKey(entity = ExecutionBindingEntity::class, parentColumns = ["id"], childColumns = ["requestedBindingId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
        ForeignKey(entity = MaterializationReceiptEntity::class, parentColumns = ["id"], childColumns = ["materializationAssetId"], onDelete = ForeignKey.NO_ACTION, deferred = true),
    ],
    indices = [
        Index(value = ["requestedBindingId"]),
        Index(value = ["materializationAssetId"]),
    ],
)
data class AssetUseEntity(
    val requestedBindingId: String,
    val materializationAssetId: String,
    val compatibilityPolicyVersion: Int,
    val createdEpochMs: Long,
)
