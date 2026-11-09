package dev.veyra.source.release

import kotlinx.serialization.json.*
import kotlin.test.*

class MusicBrainzReleaseParserTest {
    private val f = ReleaseMetadataFixtures
    private fun parse(root: JsonObject = f.release(), at: Long = 100, etag: String? = "\"one\"") =
        MusicBrainzReleaseParser.release(root.toString(), f.releaseId, at, etag)

    @Test fun preservesExactEditionUnicodeCreditsMediumIdsAndRepeatedRecordings() {
        val root = f.release(media = listOf(f.medium(), f.medium(2, f.id(11), listOf(f.track(1, f.id(201))))))
        val result = parse(root)
        assertEquals(f.title, result.completeTitle)
        assertEquals(f.releaseId, result.requestedReleaseMbid)
        assertEquals(f.releaseId, result.observedReleaseMbid)
        val release = result.metadata.release
        assertEquals("EP", release.releaseGroup?.primaryType)
        assertNotEquals(release.completeTitle, release.releaseGroup?.title)
        assertEquals("2026-11", release.date)
        assertEquals("Digipak", release.packaging)
        assertEquals(f.packagingId, release.packagingMbid)
        assertEquals(listOf(" & ", ""), release.artistCredits.map { it.joinPhrase })
        assertNotEquals(release.artistCredits.first().creditedName, release.artistCredits.first().artistName)
        assertEquals("0000012345678", release.barcode)
        assertEquals("QC-001-A", release.labels.single().catalogueNumber)
        assertEquals("Authored area", release.releaseEvents.single().areaName)
        assertEquals(listOf(0, 1), result.metadata.media.map { it.sourceArrayOrdinal })
        assertEquals(listOf(f.mediumId, f.id(11)), result.metadata.media.map { it.mediumMbid })
        val tracks = result.metadata.media.flatMap { it.tracks }
        assertEquals(2, tracks.size)
        assertEquals(2, tracks.map { it.trackMbid }.distinct().size)
        assertEquals(listOf(f.recordingId, f.recordingId), tracks.map { it.recordingMbid })
        assertEquals(ReleaseMembershipStatus.OBSERVED_COMPLETE, result.metadata.membershipStatus)
    }

    @Test fun coreDigestExcludesFetchTimeEtagAndNonCorePayloads() {
        val first = parse()
        val extra = JsonObject(f.release() + mapOf(
            "annotation" to JsonPrimitive("not core"), "score" to JsonPrimitive(99),
            "tags" to JsonArray(listOf(JsonPrimitive("not CC0"))), "rating" to JsonPrimitive(5),
            "relations" to JsonArray(listOf(JsonPrimitive("https://example.invalid/audio?token=private"))),
            "cover-art-archive" to buildJsonObject { put("artwork", true) },
        ))
        val second = parse(extra, 999, "W/\"two\"")
        assertEquals(first.metadata, second.metadata)
        assertEquals(first.coreDigest, second.coreDigest)
        assertNotEquals(first.fetchedAtEpochMs, second.fetchedAtEpochMs)
        assertNotEquals(first.etag, second.etag)
        assertTrue(Regex("[a-f0-9]{64}").matches(first.coreDigest))
        assertNotEquals(first.coreDigest, parse(f.release(title = f.title + "!")).coreDigest)
        assertFalse(second.metadata.toString().contains("private"))
    }

    @Test fun completeTitleBoundaryNeverTrimsTruncatesOrRepairsSurrogates() {
        val exact = " " + "ع".repeat(16_382) + "!"
        assertEquals(exact, parse(f.release(title = exact)).completeTitle)
        for (invalid in listOf("x".repeat(16_385), " ", "a\u0000b", "\uD800x", "x\uDC00")) {
            assertEquals(ReleaseFailureCode.INVALID_TITLE,
                assertFailsWith<ReleaseMetadataException> { parse(f.release(title = invalid)) }.code)
        }
        assertEquals("🌊".repeat(8_192), parse(f.release(title = "🌊".repeat(8_192))).completeTitle)
    }

    @Test fun missingReleaseTitleDoesNotBorrowTheGroupTitle() {
        assertEquals(ReleaseFailureCode.INVALID_TITLE, assertFailsWith<ReleaseMetadataException> {
            parse(JsonObject(f.release() - "title"))
        }.code)
    }

    @Test fun missingTrackTitleDoesNotBorrowTheRecordingTitle() {
        val result = parse(f.release(media = listOf(f.medium(tracks = listOf(f.track(title = null))))))
        val track = result.metadata.media.single().tracks.single()
        assertNull(track.completeTrackTitle)
        assertEquals("Different recording title", track.recordingTitle)
        assertTrue(track.problems.any { it.code == ReleaseProblemCode.MISSING_TITLE })
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, result.metadata.membershipStatus)
    }

    @Test fun missingCountsOffsetsAndTrackArraysAreNotComplete() {
        for (field in listOf("track-count", "track-offset", "tracks")) {
            val result = parse(f.release(media = listOf(JsonObject(f.medium() - field))))
            assertNotEquals(ReleaseMembershipStatus.OBSERVED_COMPLETE, result.metadata.membershipStatus)
            assertTrue(result.metadata.media.single().problems.isNotEmpty())
        }
    }

    @Test fun unknownMediaDiffersFromAnExplicitObservedZeroTrackMedium() {
        assertEquals(ReleaseMembershipStatus.UNKNOWN, parse(f.release(media = emptyList())).metadata.membershipStatus)
        assertEquals(ReleaseMembershipStatus.UNKNOWN, parse(JsonObject(f.release() - "media")).metadata.membershipStatus)
        val zero = parse(f.release(media = listOf(f.medium(tracks = emptyList()))))
        assertEquals(ReleaseMembershipStatus.OBSERVED_COMPLETE, zero.metadata.membershipStatus)
        assertTrue(zero.metadata.media.single().tracks.isEmpty())
    }

    @Test fun malformedRowsKeepTheirRawOccurrencePositionsInsteadOfFiltering() {
        val rows = listOf(f.track(), JsonNull, buildJsonObject { put("recording", buildJsonObject { put("title", "not a track") }) })
        val result = parse(f.release(media = listOf(f.medium(tracks = rows))))
        val tracks = result.metadata.media.single().tracks
        assertEquals(listOf(0, 1, 2), tracks.map { it.sourceArrayOrdinal })
        assertNull(tracks[1].trackMbid)
        assertTrue(tracks[1].problems.any { it.code == ReleaseProblemCode.MALFORMED_ROW })
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, result.metadata.membershipStatus)
    }

    @Test fun duplicateIdsAndReorderedDeclaredPositionsAreNotSilentlyRepaired() {
        val repeated = f.track(2, f.id(101))
        val result = parse(f.release(media = listOf(
            f.medium(tracks = listOf(repeated, f.track(1, f.id(101)))),
            f.medium(1, f.mediumId, emptyList()),
        )))
        assertEquals(listOf(2, 1), result.metadata.media.first().tracks.map { it.declaredPosition })
        assertTrue(result.metadata.media[1].problems.any { it.code == ReleaseProblemCode.DUPLICATE_ID })
        assertTrue(result.metadata.media[1].problems.any { it.code == ReleaseProblemCode.DUPLICATE_POSITION })
        assertTrue(result.metadata.media.first().tracks[1].problems.any { it.code == ReleaseProblemCode.DUPLICATE_ID })
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, result.metadata.membershipStatus)
    }

    @Test fun sameReleaseGroupCannotSubstituteADifferentEditionId() {
        val failure = assertFailsWith<ReleaseMetadataException> { parse(f.release(id = f.id(999))) }
        assertEquals(ReleaseFailureCode.IDENTITY_MISMATCH, failure.code)
    }

    @Test fun finiteMediaAndTotalTrackLimitsRemainVisibleIncomplete() {
        val manyMedia = parse(f.release(media = List(101) { f.medium(it + 1, f.id(10_000 + it), emptyList()) }))
        assertEquals(100, manyMedia.metadata.media.size)
        assertTrue(manyMedia.metadata.problems.any { it.code == ReleaseProblemCode.MEDIUM_LIMIT })
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, manyMedia.metadata.membershipStatus)
        val minimal = List(5_001) { index -> buildJsonObject {
            put("id", f.id(20_000 + index)); put("position", index + 1); put("title", "Authored $index")
        } }
        val manyTracks = parse(f.release(media = listOf(f.medium(tracks = minimal))))
        assertEquals(5_000, manyTracks.metadata.media.single().tracks.size)
        assertTrue(manyTracks.metadata.media.single().problems.any { it.code == ReleaseProblemCode.TRACK_LIMIT })
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, manyTracks.metadata.membershipStatus)
    }

    @Test fun searchRetainsMalformedAndDuplicateHitsWithDetailsUnloaded() {
        val root = f.search(listOf(f.summary(), JsonNull, buildJsonObject { put("id", "wrong") }, f.summary()))
        val parsed = MusicBrainzReleaseParser.search(root.toString(), 0, 25, 200)
        assertEquals(listOf(0, 1, 2, 3), parsed.hits.map { it.absoluteOrdinal })
        assertNull(parsed.hits[1].metadata)
        assertNull(parsed.hits[2].metadata)
        assertEquals(parsed.hits[0].metadata?.releaseMbid, parsed.hits[3].metadata?.releaseMbid)
        assertTrue(parsed.hits.all { it.detailState == ReleaseMembershipStatus.DETAILS_UNLOADED })
    }

    @Test fun wrongOffsetsCountsAndEmptyOrLimitedSuffixNeverClaimComplete() {
        val roots = listOf(f.search(offset = 1), f.search(count = 0), f.search(emptyList(), count = 10))
        for (root in roots) {
            val parsed = MusicBrainzReleaseParser.search(root.toString(), 0, 25, 200)
            assertEquals(ReleaseSearchTraversal.INCOMPLETE, parsed.traversal)
            assertNull(parsed.nextOffset)
        }
        val last = MusicBrainzReleaseParser.search(f.search(List(25) { f.summary() }, 175, 999).toString(), 175, 25, 200)
        assertEquals(ReleaseSearchTraversal.INCOMPLETE, last.traversal)
        assertTrue(last.problems.any { it.code == ReleaseProblemCode.SEARCH_LIMIT })
        assertNull(last.nextOffset)
    }

    @Test fun numericStringsAreNotInventedPositionsAndCreditNamesAreNotBorrowed() {
        val badTrack = JsonObject(f.track() + ("position" to JsonPrimitive("1")))
        val credit = JsonObject(f.credit() - "name")
        val root = JsonObject(f.release(media = listOf(f.medium(tracks = listOf(badTrack)))) +
            ("artist-credit" to JsonArray(listOf(credit))))
        val result = parse(root)
        assertNull(result.metadata.media.single().tracks.single().declaredPosition)
        assertNull(result.metadata.release.artistCredits.single().creditedName)
        assertEquals("Canonical artist name", result.metadata.release.artistCredits.single().artistName)
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, result.metadata.membershipStatus)
    }

    @Test fun rejectsOversizeDeepAndMalformedJsonBeforeUnboundedParsing() {
        assertEquals(ReleaseFailureCode.RESPONSE_LIMIT, assertFailsWith<ReleaseMetadataException> {
            MusicBrainzReleaseParser.release("x".repeat(MusicBrainzReleaseParser.MAX_RESPONSE_BYTES + 1), f.releaseId, 0, null)
        }.code)
        for (text in listOf("[".repeat(65) + "0" + "]".repeat(65), "{", "[]", "{\"title\":false}")) {
            assertFailsWith<ReleaseMetadataException> { MusicBrainzReleaseParser.release(text, f.releaseId, 0, null) }
        }
    }

    @Test fun relationCeilingsAndInvalidOptionalFieldsAreExplicitNotRawBlobs() {
        val root = JsonObject(f.release() + mapOf("artist-credit" to JsonArray(List(129) { f.credit() }),
            "packaging-id" to JsonPrimitive("not-an-id")))
        val result = parse(root)
        assertEquals(128, result.metadata.release.artistCredits.size)
        assertNull(result.metadata.release.packagingMbid)
        assertTrue(result.metadata.release.problems.any { it.code == ReleaseProblemCode.RELATION_LIMIT })
        assertTrue(result.metadata.release.problems.any { it.code == ReleaseProblemCode.INVALID_ID })
    }
}
