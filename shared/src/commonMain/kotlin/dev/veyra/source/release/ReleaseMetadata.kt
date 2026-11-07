package dev.veyra.source.release

import kotlinx.serialization.Serializable

/** MusicBrainz core metadata only. No tags, scores, annotations, artwork or audio locators. */
@Serializable
enum class ReleaseMembershipStatus { DETAILS_UNLOADED, UNKNOWN, OBSERVED_COMPLETE, INCOMPLETE }

@Serializable
enum class ReleaseProblemCode {
    MISSING_VALUE, INVALID_VALUE, INVALID_ID, MISSING_TITLE, INVALID_TITLE, INVALID_POSITION,
    DUPLICATE_POSITION, DUPLICATE_ID, POSITION_GAP, MISSING_TRACKS, TRACK_COUNT_MISMATCH, TRACK_OFFSET_GAP,
    MEDIUM_LIMIT, TRACK_LIMIT, RELATION_LIMIT, MALFORMED_ROW, PAGINATION_MISMATCH, SEARCH_LIMIT,
}

/** Finite field names/ordinals, never a provider body, exception message or private URL. */
@Serializable
data class ReleaseMetadataProblem(
    val code: ReleaseProblemCode,
    val field: ReleaseMetadataField,
    val mediumOrdinal: Int? = null,
    val trackOrdinal: Int? = null,
)

@Serializable
enum class ReleaseMetadataField {
    RELEASE_ID, TITLE, RELEASE_GROUP, ARTIST_CREDIT, STATUS, DATE, COUNTRY, RELEASE_EVENT,
    LABEL, BARCODE, DISAMBIGUATION, LANGUAGE, SCRIPT, PACKAGING, MEDIA, MEDIUM_ID,
    MEDIUM_POSITION, MEDIUM_TITLE, FORMAT, TRACK_COUNT, TRACK_OFFSET, TRACKS, TRACK_ID,
    TRACK_POSITION, TRACK_NUMBER, TRACK_TITLE, TRACK_LENGTH, RECORDING_ID, RECORDING_TITLE,
    SEARCH_ROWS, SEARCH_COUNT, SEARCH_OFFSET,
}

@Serializable
data class ReleaseArtistCredit(
    val sourceArrayOrdinal: Int,
    val artistMbid: String?,
    val creditedName: String?,
    val joinPhrase: String?,
    val artistName: String? = null,
)

/** Type belongs to this group; it is not an edition-level claim of recording equivalence. */
@Serializable
data class ReleaseGroupMetadata(
    val mbid: String?,
    val title: String?,
    val primaryType: String?,
    val secondaryTypes: List<String>,
)

@Serializable
data class ReleaseEventMetadata(
    val sourceArrayOrdinal: Int,
    val date: String?,
    val areaMbid: String?,
    val areaName: String?,
    val countryCodes: List<String>,
)

@Serializable
data class ReleaseLabelMetadata(
    val sourceArrayOrdinal: Int,
    val labelMbid: String?,
    val name: String?,
    val catalogueNumber: String?,
)

@Serializable
data class ReleaseSummaryMetadata(
    val releaseMbid: String,
    /** The unchanged release.title; never artist/group/edition concatenation. */
    val completeTitle: String,
    val releaseGroup: ReleaseGroupMetadata?,
    val artistCredits: List<ReleaseArtistCredit>,
    val status: String?,
    val date: String?,
    val country: String?,
    val releaseEvents: List<ReleaseEventMetadata>,
    val labels: List<ReleaseLabelMetadata>,
    val barcode: String?,
    val disambiguation: String?,
    val textLanguage: String?,
    val textScript: String?,
    val packaging: String?,
    val packagingMbid: String?,
    val problems: List<ReleaseMetadataProblem>,
) {
    val sourceUrl: String get() = "https://musicbrainz.org/release/$releaseMbid"
}

@Serializable
data class ReleaseTrackObservation(
    val sourceArrayOrdinal: Int,
    val declaredPosition: Int?,
    /** Printed numbers can be A1, 1.01, etc.; they are not invented ordinal integers. */
    val printedNumber: String?,
    val trackMbid: String?,
    val completeTrackTitle: String?,
    val artistCredits: List<ReleaseArtistCredit>,
    val lengthMs: Long?,
    val recordingMbid: String?,
    /** Kept separately; it never repairs or replaces a missing/different track title. */
    val recordingTitle: String?,
    val problems: List<ReleaseMetadataProblem>,
)

@Serializable
data class ReleaseMediumObservation(
    val sourceArrayOrdinal: Int,
    val mediumMbid: String?,
    val declaredPosition: Int?,
    val title: String?,
    val format: String?,
    val declaredTrackCount: Int?,
    val trackOffset: Int?,
    val tracks: List<ReleaseTrackObservation>,
    val membershipStatus: ReleaseMembershipStatus,
    val problems: List<ReleaseMetadataProblem>,
)

/** Fixed-field canonical hashing uses this value only, not fetch time/ETag or raw JSON. */
@Serializable
data class ReleaseCoreMetadata(
    val release: ReleaseSummaryMetadata,
    val media: List<ReleaseMediumObservation>,
    val membershipStatus: ReleaseMembershipStatus,
    val problems: List<ReleaseMetadataProblem>,
)

data class ReleaseObservation(
    val requestedReleaseMbid: String,
    val metadata: ReleaseCoreMetadata,
    val fetchedAtEpochMs: Long,
    val coreDigest: String,
    val etag: String?,
) {
    val observedReleaseMbid: String get() = metadata.release.releaseMbid
    val completeTitle: String get() = metadata.release.completeTitle
    val sourceUrl: String get() = metadata.release.sourceUrl
    override fun toString() = "ReleaseObservation(membership=${metadata.membershipStatus},metadata=redacted)"
}

/** Array position is preserved even when a malformed search hit cannot supply valid metadata. */
@Serializable
data class ReleaseSearchHit(
    val sourceArrayOrdinal: Int,
    val absoluteOrdinal: Int,
    val metadata: ReleaseSummaryMetadata?,
    val problems: List<ReleaseMetadataProblem>,
    val detailState: ReleaseMembershipStatus = ReleaseMembershipStatus.DETAILS_UNLOADED,
)

data class ReleaseSearchQuery(
    val text: String,
    val albumsOnly: Boolean = false,
    val officialOnly: Boolean = false,
) {
    override fun toString() = "ReleaseSearchQuery(albumsOnly=$albumsOnly,officialOnly=$officialOnly,text=redacted)"
}

enum class ReleaseSearchTraversal { MORE_AVAILABLE, OBSERVED_TO_END, INCOMPLETE }

/** App-memory cursor only. Catalogue search results are not a provider-atomic snapshot. */
class ReleaseSearchCursor internal constructor(
    internal val owner: Any,
    internal val query: ReleaseSearchQuery,
    val nextOffset: Int,
) {
    override fun toString() = "ReleaseSearchCursor(offset=$nextOffset,query=redacted)"
}

data class ReleaseSearchPage(
    val query: ReleaseSearchQuery,
    val requestedOffset: Int,
    val reportedOffset: Int?,
    val reportedCount: Long?,
    val hits: List<ReleaseSearchHit>,
    val traversal: ReleaseSearchTraversal,
    val problems: List<ReleaseMetadataProblem>,
    val fetchedAtEpochMs: Long,
    val nextCursor: ReleaseSearchCursor?,
) {
    override fun toString() = "ReleaseSearchPage(offset=$requestedOffset,count=${hits.size},traversal=$traversal,metadata=redacted)"
}

/** Staleness is a display/cache policy, never permission for a timer or offline network fetch. */
data class ReleaseMetadataRead<T>(val value: T, val fromCache: Boolean, val stale: Boolean)

enum class ReleaseFailureCode {
    INVALID_QUERY, INVALID_RELEASE_ID, INVALID_CURSOR, OFFLINE, CLOSED, QUEUE_FULL,
    PROVIDER_COOLDOWN, NETWORK, HTTP, REDIRECTED, NON_PUBLIC_ORIGIN, INVALID_RESPONSE,
    INVALID_ENCODING, INVALID_CONTENT_TYPE, RESPONSE_LIMIT, IDENTITY_MISMATCH, INVALID_TITLE,
}

class ReleaseMetadataException(
    val code: ReleaseFailureCode,
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
) : Exception("release_metadata_${code.name.lowercase()}")

internal fun releaseFailure(code: ReleaseFailureCode, status: Int? = null, retryAfterMs: Long? = null): Nothing =
    throw ReleaseMetadataException(code, status, retryAfterMs)

internal val RELEASE_MBID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** Same accepted full-title boundary as central acquisition; no normalization or truncation. */
internal fun validReleaseTitle(value: String): Boolean {
    if (value.length !in 1..16_384 || value.isBlank()) return false
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.code < 32 || char.code in 127..159) return false
        if (char.isHighSurrogate()) {
            if (index == value.length || !value[index++].isLowSurrogate()) return false
        } else if (char.isLowSurrogate()) return false
    }
    return true
}
