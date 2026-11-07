package dev.veyra.source.release

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8

/** No JSON element escapes this parser. Returned values contain only the selected CC0 fields. */
internal object MusicBrainzReleaseParser {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_MEDIA = 100
    const val MAX_TRACKS = 5_000
    private const val MAX_RELATIONS = 128
    private val canonical = Json { encodeDefaults = true; explicitNulls = true }

    fun release(text: String, requestedMbid: String, fetchedAtEpochMs: Long, etag: String?): ReleaseObservation {
        if (!RELEASE_MBID.matches(requestedMbid)) releaseFailure(ReleaseFailureCode.INVALID_RELEASE_ID)
        val root = root(text)
        val id = root.string("id")?.takeIf(RELEASE_MBID::matches)?.lowercase()
            ?: releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
        if (id != requestedMbid.lowercase()) releaseFailure(ReleaseFailureCode.IDENTITY_MISMATCH)
        if (root.string("title")?.let(::validReleaseTitle) != true) releaseFailure(ReleaseFailureCode.INVALID_TITLE)
        val issues = mutableListOf<ReleaseMetadataProblem>()
        val summary = requireNotNull(summary(root, issues))
        val rows = array(root, "media", ReleaseMetadataField.MEDIA, issues, required = true)
        val media = mutableListOf<ReleaseMediumObservation>()
        var remaining = MAX_TRACKS
        val positions = hashSetOf<Int>()
        val mediumIds = hashSetOf<String>()
        val trackIds = hashSetOf<String>()
        if (rows != null && rows.size > MAX_MEDIA) issues.add(problem(ReleaseProblemCode.MEDIUM_LIMIT, ReleaseMetadataField.MEDIA))
        rows?.take(MAX_MEDIA)?.forEachIndexed { ordinal, element ->
            val medium = medium(element as? JsonObject, ordinal, remaining, positions, mediumIds, trackIds)
            media += medium
            remaining -= medium.tracks.size
        }
        val membership = when {
            rows == null || rows.isEmpty() -> ReleaseMembershipStatus.UNKNOWN
            rows.size > MAX_MEDIA || media.any { it.membershipStatus != ReleaseMembershipStatus.OBSERVED_COMPLETE } ->
                ReleaseMembershipStatus.INCOMPLETE
            else -> ReleaseMembershipStatus.OBSERVED_COMPLETE
        }
        val core = ReleaseCoreMetadata(summary, media.toList(), membership, issues.toList())
        return ReleaseObservation(requestedMbid.lowercase(), core, fetchedAtEpochMs, digest(core), safeEtag(etag))
    }

    data class Search(
        val offset: Int?, val count: Long?, val hits: List<ReleaseSearchHit>,
        val traversal: ReleaseSearchTraversal, val problems: List<ReleaseMetadataProblem>, val nextOffset: Int?,
    )

    fun search(text: String, requestedOffset: Int, limit: Int, maximumRows: Int): Search {
        require(requestedOffset >= 0 && limit > 0 && maximumRows >= requestedOffset + limit)
        val root = root(text)
        val issues = mutableListOf<ReleaseMetadataProblem>()
        val offset = integer(root, "offset", ReleaseMetadataField.SEARCH_OFFSET, issues, required = true)
        val count = long(root, "count", ReleaseMetadataField.SEARCH_COUNT, issues, required = true)
        val rows = array(root, "releases", ReleaseMetadataField.SEARCH_ROWS, issues, required = true)
            ?: releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
        if (offset != requestedOffset || (count != null && count < requestedOffset.toLong() + rows.size)) {
            issues.add(problem(ReleaseProblemCode.PAGINATION_MISMATCH, ReleaseMetadataField.SEARCH_ROWS))
        }
        if (rows.size > limit) issues.add(problem(ReleaseProblemCode.SEARCH_LIMIT, ReleaseMetadataField.SEARCH_ROWS))
        val hits = rows.take(limit).mapIndexed { index, element ->
            val rowIssues = mutableListOf<ReleaseMetadataProblem>()
            val row = element as? JsonObject
            val metadata = if (row == null) {
                rowIssues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.SEARCH_ROWS)); null
            } else summary(row, rowIssues)
            ReleaseSearchHit(index, requestedOffset + index, metadata, rowIssues.toList())
        }
        val observedEnd = requestedOffset + hits.size
        val more = count != null && observedEnd.toLong() < count
        if (more && (hits.isEmpty() || observedEnd >= maximumRows)) {
            issues.add(problem(if (hits.isEmpty()) ReleaseProblemCode.PAGINATION_MISMATCH else ReleaseProblemCode.SEARCH_LIMIT,
                ReleaseMetadataField.SEARCH_ROWS))
        }
        val traversal = when {
            issues.isNotEmpty() -> ReleaseSearchTraversal.INCOMPLETE
            more -> ReleaseSearchTraversal.MORE_AVAILABLE
            else -> ReleaseSearchTraversal.OBSERVED_TO_END
        }
        return Search(offset, count, hits, traversal, issues.toList(),
            observedEnd.takeIf { traversal == ReleaseSearchTraversal.MORE_AVAILABLE })
    }

    fun digest(value: ReleaseCoreMetadata): String = canonical.encodeToString(value).encodeUtf8().sha256().hex()

    /** Logical retained-payload budget, not a JVM/native heap-size measurement. */
    fun payloadBytes(value: ReleaseCoreMetadata): Int = canonical.encodeToString(value).encodeUtf8().size
    fun payloadBytes(value: List<ReleaseSearchHit>): Int = canonical.encodeToString(value).encodeUtf8().size

    fun safeEtag(value: String?): String? = value?.takeIf {
        val quoted = it.removePrefix("W/")
        it.length <= 512 && quoted.length >= 2 && quoted.first() == '"' && quoted.last() == '"' &&
            quoted.substring(1, quoted.lastIndex).all { char -> char.code == 33 || char.code in 35..126 }
    }

    private fun summary(root: JsonObject, issues: MutableList<ReleaseMetadataProblem>): ReleaseSummaryMetadata? {
        val id = mbid(root, "id", ReleaseMetadataField.RELEASE_ID, issues, required = true)
        val title = text(root, "title", ReleaseMetadataField.TITLE, issues, required = true, title = true)
        if (id == null || title == null) return null
        val group = objectValue(root, "release-group", ReleaseMetadataField.RELEASE_GROUP, issues)?.let { value ->
            ReleaseGroupMetadata(
                mbid(value, "id", ReleaseMetadataField.RELEASE_GROUP, issues, required = true),
                text(value, "title", ReleaseMetadataField.RELEASE_GROUP, issues, title = true),
                text(value, "primary-type", ReleaseMetadataField.RELEASE_GROUP, issues, max = 256),
                strings(value, "secondary-types", ReleaseMetadataField.RELEASE_GROUP, issues),
            )
        }
        val credits = credits(root, issues)
        val events = relation(root, "release-events", ReleaseMetadataField.RELEASE_EVENT, issues).mapIndexed { index, row ->
            val value = row as? JsonObject
            if (value == null) issues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.RELEASE_EVENT))
            val area = objectValue(value, "area", ReleaseMetadataField.RELEASE_EVENT, issues)
            ReleaseEventMetadata(index,
                text(value, "date", ReleaseMetadataField.DATE, issues, max = 32),
                mbid(area, "id", ReleaseMetadataField.RELEASE_EVENT, issues),
                text(area, "name", ReleaseMetadataField.RELEASE_EVENT, issues),
                strings(area, "iso-3166-1-codes", ReleaseMetadataField.RELEASE_EVENT, issues))
        }
        val labels = relation(root, "label-info", ReleaseMetadataField.LABEL, issues).mapIndexed { index, row ->
            val value = row as? JsonObject
            if (value == null) issues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.LABEL))
            val label = objectValue(value, "label", ReleaseMetadataField.LABEL, issues)
            ReleaseLabelMetadata(index, mbid(label, "id", ReleaseMetadataField.LABEL, issues),
                text(label, "name", ReleaseMetadataField.LABEL, issues),
                text(value, "catalog-number", ReleaseMetadataField.LABEL, issues))
        }
        val representation = objectValue(root, "text-representation", ReleaseMetadataField.LANGUAGE, issues)
        val status = text(root, "status", ReleaseMetadataField.STATUS, issues, max = 256)
        val date = text(root, "date", ReleaseMetadataField.DATE, issues, max = 32)
        val country = text(root, "country", ReleaseMetadataField.COUNTRY, issues, max = 32)
        val barcode = text(root, "barcode", ReleaseMetadataField.BARCODE, issues, max = 256)
        val disambiguation = text(root, "disambiguation", ReleaseMetadataField.DISAMBIGUATION, issues)
        val language = text(representation, "language", ReleaseMetadataField.LANGUAGE, issues, max = 32)
        val script = text(representation, "script", ReleaseMetadataField.SCRIPT, issues, max = 32)
        val packaging = text(root, "packaging", ReleaseMetadataField.PACKAGING, issues, max = 256)
        val packagingId = mbid(root, "packaging-id", ReleaseMetadataField.PACKAGING, issues)
        return ReleaseSummaryMetadata(id, title, group, credits, status, date, country, events, labels,
            barcode, disambiguation, language, script, packaging, packagingId, issues.toList())
    }

    private fun medium(
        root: JsonObject?, ordinal: Int, capacity: Int, positions: MutableSet<Int>, mediumIds: MutableSet<String>,
        trackIds: MutableSet<String>,
    ): ReleaseMediumObservation {
        val issues = mutableListOf<ReleaseMetadataProblem>()
        if (root == null) issues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.MEDIA, ordinal))
        val id = mbid(root, "id", ReleaseMetadataField.MEDIUM_ID, issues, medium = ordinal)
        if (id != null && !mediumIds.add(id)) issues.add(problem(ReleaseProblemCode.DUPLICATE_ID, ReleaseMetadataField.MEDIUM_ID, ordinal))
        val position = integer(root, "position", ReleaseMetadataField.MEDIUM_POSITION, issues, required = true, minimum = 1, medium = ordinal)
        if (position != null && !positions.add(position)) issues.add(problem(ReleaseProblemCode.DUPLICATE_POSITION, ReleaseMetadataField.MEDIUM_POSITION, ordinal))
        if (position != null && position != ordinal + 1) issues.add(problem(ReleaseProblemCode.POSITION_GAP, ReleaseMetadataField.MEDIUM_POSITION, ordinal))
        val title = text(root, "title", ReleaseMetadataField.MEDIUM_TITLE, issues, medium = ordinal)
        val format = text(root, "format", ReleaseMetadataField.FORMAT, issues, max = 256, medium = ordinal)
        val count = integer(root, "track-count", ReleaseMetadataField.TRACK_COUNT, issues, required = true, medium = ordinal)
        val offset = integer(root, "track-offset", ReleaseMetadataField.TRACK_OFFSET, issues, required = true, medium = ordinal)
        if (offset != null && offset != 0) issues.add(problem(ReleaseProblemCode.TRACK_OFFSET_GAP, ReleaseMetadataField.TRACK_OFFSET, ordinal))
        val rows = array(root, "tracks", ReleaseMetadataField.TRACKS, issues, required = true, medium = ordinal)
        if (rows == null) issues.add(problem(ReleaseProblemCode.MISSING_TRACKS, ReleaseMetadataField.TRACKS, ordinal))
        if (rows != null && count != null && count != rows.size) issues.add(problem(ReleaseProblemCode.TRACK_COUNT_MISMATCH, ReleaseMetadataField.TRACK_COUNT, ordinal))
        if (rows != null && rows.size > capacity) issues.add(problem(ReleaseProblemCode.TRACK_LIMIT, ReleaseMetadataField.TRACKS, ordinal))
        val trackPositions = hashSetOf<Int>()
        val tracks = rows?.take(capacity)?.mapIndexed { index, row ->
            track(row as? JsonObject, ordinal, index, offset, trackPositions, trackIds)
        }.orEmpty()
        val membership = when {
            rows == null -> ReleaseMembershipStatus.UNKNOWN
            issues.any { it.field in MEMBERSHIP_FIELDS } || tracks.any { row -> row.problems.any { it.field in MEMBERSHIP_FIELDS } } ->
                ReleaseMembershipStatus.INCOMPLETE
            else -> ReleaseMembershipStatus.OBSERVED_COMPLETE
        }
        return ReleaseMediumObservation(ordinal, id, position, title, format, count, offset, tracks.toList(), membership, issues.toList())
    }

    private fun track(root: JsonObject?, medium: Int, ordinal: Int, offset: Int?, positions: MutableSet<Int>, ids: MutableSet<String>): ReleaseTrackObservation {
        val issues = mutableListOf<ReleaseMetadataProblem>()
        if (root == null) issues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.TRACKS, medium, ordinal))
        val id = mbid(root, "id", ReleaseMetadataField.TRACK_ID, issues, required = true, medium = medium, track = ordinal)
        if (id != null && !ids.add(id)) issues.add(problem(ReleaseProblemCode.DUPLICATE_ID, ReleaseMetadataField.TRACK_ID, medium, ordinal))
        val position = integer(root, "position", ReleaseMetadataField.TRACK_POSITION, issues, required = true, minimum = 1, medium = medium, track = ordinal)
        if (position != null && !positions.add(position)) issues.add(problem(ReleaseProblemCode.DUPLICATE_POSITION, ReleaseMetadataField.TRACK_POSITION, medium, ordinal))
        if (position != null && (offset == null || position.toLong() != offset.toLong() + ordinal + 1)) {
            issues.add(problem(ReleaseProblemCode.POSITION_GAP, ReleaseMetadataField.TRACK_POSITION, medium, ordinal))
        }
        val number = text(root, "number", ReleaseMetadataField.TRACK_NUMBER, issues, max = 256, medium = medium, track = ordinal)
        val title = text(root, "title", ReleaseMetadataField.TRACK_TITLE, issues, required = true, title = true, medium = medium, track = ordinal)
        val artists = credits(root, issues, medium, ordinal)
        val length = long(root, "length", ReleaseMetadataField.TRACK_LENGTH, issues, medium = medium, track = ordinal)
        val recording = objectValue(root, "recording", ReleaseMetadataField.RECORDING_ID, issues, medium, ordinal)
        val recordingId = mbid(recording, "id", ReleaseMetadataField.RECORDING_ID, issues, medium = medium, track = ordinal)
        val recordingTitle = text(recording, "title", ReleaseMetadataField.RECORDING_TITLE, issues, title = true, medium = medium, track = ordinal)
        return ReleaseTrackObservation(ordinal, position, number, id, title, artists, length, recordingId, recordingTitle, issues.toList())
    }

    private fun credits(root: JsonObject?, issues: MutableList<ReleaseMetadataProblem>, medium: Int? = null, track: Int? = null): List<ReleaseArtistCredit> =
        relation(root, "artist-credit", ReleaseMetadataField.ARTIST_CREDIT, issues, medium, track).mapIndexed { index, row ->
            val value = row as? JsonObject
            if (value == null) issues.add(problem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.ARTIST_CREDIT, medium, track))
            val artist = objectValue(value, "artist", ReleaseMetadataField.ARTIST_CREDIT, issues, medium, track)
            ReleaseArtistCredit(index,
                mbid(artist, "id", ReleaseMetadataField.ARTIST_CREDIT, issues, required = true, medium = medium, track = track),
                text(value, "name", ReleaseMetadataField.ARTIST_CREDIT, issues, required = true, medium = medium, track = track),
                text(value, "joinphrase", ReleaseMetadataField.ARTIST_CREDIT, issues, medium = medium, track = track),
                text(artist, "name", ReleaseMetadataField.ARTIST_CREDIT, issues, medium = medium, track = track))
        }

    private fun relation(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, medium: Int? = null, track: Int? = null): List<JsonElement> {
        val rows = array(root, key, field, issues, medium = medium, track = track).orEmpty()
        if (rows.size > MAX_RELATIONS) issues.add(problem(ReleaseProblemCode.RELATION_LIMIT, field, medium, track))
        return rows.take(MAX_RELATIONS)
    }

    private fun strings(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>): List<String> =
        relation(root, key, field, issues).mapNotNull { element ->
            val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            value?.takeIf { it.length <= 256 && validOptionalText(it) } ?: run {
                issues.add(problem(ReleaseProblemCode.INVALID_VALUE, field)); null
            }
        }

    private fun array(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, required: Boolean = false, medium: Int? = null, track: Int? = null): JsonArray? {
        val value = root?.get(key)
        if (value == null || value == JsonNull) {
            if (required) issues.add(problem(ReleaseProblemCode.MISSING_VALUE, field, medium, track))
            return null
        }
        return (value as? JsonArray) ?: run { issues.add(problem(ReleaseProblemCode.INVALID_VALUE, field, medium, track)); null }
    }

    private fun objectValue(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, medium: Int? = null, track: Int? = null): JsonObject? {
        val value = root?.get(key)
        if (value == null || value == JsonNull) return null
        return (value as? JsonObject) ?: run { issues.add(problem(ReleaseProblemCode.INVALID_VALUE, field, medium, track)); null }
    }

    private fun text(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, required: Boolean = false,
        title: Boolean = false, max: Int = 16_384, medium: Int? = null, track: Int? = null): String? {
        val value = root?.get(key)
        if (value == null || value == JsonNull) {
            if (required) issues.add(problem(if (title) ReleaseProblemCode.MISSING_TITLE else ReleaseProblemCode.MISSING_VALUE, field, medium, track))
            return null
        }
        val result = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (result == null || result.length > max || (required && result.isBlank()) ||
            (if (title) !validReleaseTitle(result) else !validOptionalText(result))) {
            issues.add(problem(if (title) ReleaseProblemCode.INVALID_TITLE else ReleaseProblemCode.INVALID_VALUE, field, medium, track)); return null
        }
        return result
    }

    private fun mbid(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, required: Boolean = false, medium: Int? = null, track: Int? = null): String? {
        val value = root?.get(key)
        if (value == null || value == JsonNull) {
            if (required) issues.add(problem(ReleaseProblemCode.MISSING_VALUE, field, medium, track))
            return null
        }
        return root.string(key)?.takeIf(RELEASE_MBID::matches)?.lowercase() ?: run {
            issues.add(problem(ReleaseProblemCode.INVALID_ID, field, medium, track)); null
        }
    }

    private fun integer(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, required: Boolean = false,
        minimum: Int = 0, medium: Int? = null, track: Int? = null): Int? {
        val value = long(root, key, field, issues, required, medium, track) ?: return null
        if (value !in minimum.toLong()..Int.MAX_VALUE.toLong()) {
            issues.add(problem(ReleaseProblemCode.INVALID_VALUE, field, medium, track)); return null
        }
        return value.toInt()
    }

    private fun long(root: JsonObject?, key: String, field: ReleaseMetadataField, issues: MutableList<ReleaseMetadataProblem>, required: Boolean = false, medium: Int? = null, track: Int? = null): Long? {
        val value = root?.get(key)
        if (value == null || value == JsonNull) {
            if (required) issues.add(problem(ReleaseProblemCode.MISSING_VALUE, field, medium, track))
            return null
        }
        return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: run {
            issues.add(problem(ReleaseProblemCode.INVALID_VALUE, field, medium, track)); null
        }
    }

    private fun root(text: String): JsonObject {
        if (text.length > MAX_RESPONSE_BYTES || text.encodeToByteArray().size > MAX_RESPONSE_BYTES) releaseFailure(ReleaseFailureCode.RESPONSE_LIMIT)
        // Bound parser recursion before invoking kotlinx JSON on an untrusted document.
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 64) releaseFailure(ReleaseFailureCode.INVALID_RESPONSE) }
                '}', ']' -> { depth--; if (depth < 0) releaseFailure(ReleaseFailureCode.INVALID_RESPONSE) }
            }
        }
        if (quoted || depth != 0) releaseFailure(ReleaseFailureCode.INVALID_RESPONSE)
        return try { Json.parseToJsonElement(text) as? JsonObject ?: releaseFailure(ReleaseFailureCode.INVALID_RESPONSE) }
        catch (failure: ReleaseMetadataException) { throw failure }
        catch (_: Exception) { releaseFailure(ReleaseFailureCode.INVALID_RESPONSE) }
    }

    private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun validOptionalText(value: String): Boolean = value.isEmpty() || validReleaseTitle(value) ||
        (value.length <= 16_384 && value.isBlank() && value.all { it.code >= 32 && it.code !in 127..159 && !it.isSurrogate() })
    private fun problem(code: ReleaseProblemCode, field: ReleaseMetadataField, medium: Int? = null, track: Int? = null) =
        ReleaseMetadataProblem(code, field, medium, track)

    private val MEMBERSHIP_FIELDS = setOf(ReleaseMetadataField.MEDIA, ReleaseMetadataField.MEDIUM_ID,
        ReleaseMetadataField.MEDIUM_POSITION, ReleaseMetadataField.TRACK_COUNT, ReleaseMetadataField.TRACK_OFFSET,
        ReleaseMetadataField.TRACKS, ReleaseMetadataField.TRACK_ID, ReleaseMetadataField.TRACK_POSITION, ReleaseMetadataField.TRACK_TITLE)
}
