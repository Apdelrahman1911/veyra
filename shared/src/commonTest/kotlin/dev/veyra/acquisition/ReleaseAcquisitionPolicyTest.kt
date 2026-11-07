package dev.veyra.acquisition

import dev.veyra.model.VeyraException
import dev.veyra.source.release.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReleaseAcquisitionPolicyTest {
    @Test fun parentUsesCompleteEditionTitleNotGroupArtistOrEditionConcatenation() {
        val observation = release()
        assertEquals("  ڤيرا / Quiet Currents (إصدار)  ", ReleaseAcquisitionPolicy.parentTitle(observation))
        assertNotEquals(observation.metadata.release.releaseGroup!!.title, observation.completeTitle)
        assertEquals("EP", observation.metadata.release.releaseGroup!!.primaryType)
    }

    @Test fun incompleteCatalogueStillAllowsTruthfulParentTitleRequest() {
        val observation = release().let { it.copy(metadata = it.metadata.copy(membershipStatus = ReleaseMembershipStatus.INCOMPLETE)) }
        assertEquals(observation.completeTitle, ReleaseAcquisitionPolicy.parentTitle(observation))
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.requireWholeCatalogMembership(observation) }
    }

    @Test fun changedOrInvalidEditionDoesNotBecomeAnotherSelectedEdition() {
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.parentTitle(release().copy(requestedReleaseMbid = mbid(2))) }
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.parentTitle(release().copy(requestedReleaseMbid = "not-an-edition")) }
    }

    @Test fun sourceOrdinalNotPrintedOrDeclaredPositionSelectsTrack() {
        val first = track(ordinal = 7, title = " B — الأولى ")
        val second = track(ordinal = 9, title = " B — الثانية ")
        val observation = release(listOf(first, second))
        assertEquals(first, ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 7))
        assertEquals(second, ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 9))
        assertEquals(first.recordingMbid, second.recordingMbid)
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 1) }
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(observation, 0, 7) }
    }

    @Test fun missingTrackTitleDoesNotFallBackToRecordingOrNextTrack() {
        val observation = release(listOf(track(title = null), track(1, "Usable later title")))
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 0) }
        assertEquals("Usable later title", ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 1).completeTrackTitle)
    }

    @Test fun explicitlyInvalidTitleMetadataIsNotRepairedByNonblankDtoBytes() {
        listOf(ReleaseProblemCode.MISSING_TITLE, ReleaseProblemCode.INVALID_TITLE).forEach { code ->
            val observation = release(listOf(track().copy(problems = listOf(
                ReleaseMetadataProblem(code, ReleaseMetadataField.TRACK_TITLE),
            ))))
            assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 0) }
            assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.requireWholeCatalogMembership(observation) }
        }
    }

    @Test fun missingTrackOrMediumMbidDoesNotEraseStableOccurrence() {
        val observation = release(listOf(track().copy(trackMbid = null)))
        assertNull(observation.metadata.media.single().mediumMbid)
        assertNull(ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 0).trackMbid)
    }

    @Test fun ambiguousSourceOrdinalsAreRefusedRatherThanFirstMatchingRow() {
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(release(listOf(track(), track())), 4, 0) }
        val observation = release().let { it.copy(metadata = it.metadata.copy(media = it.metadata.media + it.metadata.media)) }
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 0) }
    }

    @Test fun explicitValidTrackIsAllowedEvenWhenOtherCatalogueMetadataIsIncomplete() {
        val observation = release(listOf(track(), track(1, null))).let { it.copy(metadata = it.metadata.copy(
            membershipStatus = ReleaseMembershipStatus.INCOMPLETE,
            media = it.metadata.media.map { medium -> medium.copy(membershipStatus = ReleaseMembershipStatus.INCOMPLETE) },
        )) }
        assertEquals("  B / العنوان الكامل  ", ReleaseAcquisitionPolicy.catalogTrack(observation, 4, 0).completeTrackTitle)
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.requireWholeCatalogMembership(observation) }
    }

    @Test fun wholeCatalogueRejectsUnknownOrUntitledRequiredOccurrences() {
        ReleaseAcquisitionPolicy.requireWholeCatalogMembership(release())
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.requireWholeCatalogMembership(release(listOf(track(title = null)))) }
        val unloaded = release().let { it.copy(metadata = it.metadata.copy(membershipStatus = ReleaseMembershipStatus.DETAILS_UNLOADED)) }
        assertFailsWith<VeyraException> { ReleaseAcquisitionPolicy.requireWholeCatalogMembership(unloaded) }
    }

    @Test fun freezingDetachesNestedCallerOwnedRelationsWithoutChangingTheirOrder() {
        val types = mutableListOf("Live", "Remix")
        val countries = mutableListOf("GB", "EG")
        val credits = mutableListOf(ReleaseArtistCredit(3, mbid(8), "اسم", " & ", "Canonical"))
        val problems = mutableListOf(ReleaseMetadataProblem(ReleaseProblemCode.MISSING_VALUE, ReleaseMetadataField.FORMAT))
        val tracks = mutableListOf(track().copy(artistCredits = credits, problems = problems))
        val original = release(tracks).let { value -> value.copy(metadata = value.metadata.copy(
            release = value.metadata.release.copy(
                releaseGroup = value.metadata.release.releaseGroup!!.copy(secondaryTypes = types),
                artistCredits = credits,
                releaseEvents = listOf(ReleaseEventMetadata(6, "2026-11", mbid(5), "Authored area", countries)),
                problems = problems,
            ),
            media = value.metadata.media.map { it.copy(problems = problems) },
            problems = problems,
        )) }
        val frozen = ReleaseAcquisitionPolicy.freeze(original)
        assertEquals(original, frozen)
        types.clear(); countries.clear(); credits.clear(); problems.clear(); tracks.clear()
        assertEquals(listOf("Live", "Remix"), frozen.metadata.release.releaseGroup!!.secondaryTypes)
        assertEquals(listOf("GB", "EG"), frozen.metadata.release.releaseEvents.single().countryCodes)
        assertEquals(1, frozen.metadata.release.artistCredits.size)
        assertEquals(1, frozen.metadata.release.problems.size)
        assertEquals(1, frozen.metadata.problems.size)
        assertEquals(1, frozen.metadata.media.single().problems.size)
        assertEquals(1, frozen.metadata.media.single().tracks.single().problems.size)
        assertEquals(1, frozen.metadata.media.single().tracks.single().artistCredits.size)
        assertEquals(1, frozen.metadata.media.single().tracks.size)
    }

    private fun release(tracks: List<ReleaseTrackObservation> = listOf(track())): ReleaseObservation = ReleaseObservation(
        requestedReleaseMbid = mbid(1),
        metadata = ReleaseCoreMetadata(
            release = ReleaseSummaryMetadata(
                releaseMbid = mbid(1), completeTitle = "  ڤيرا / Quiet Currents (إصدار)  ",
                releaseGroup = ReleaseGroupMetadata(mbid(2), "Different conceptual group", "EP", emptyList()),
                artistCredits = emptyList(), status = "Official", date = "2026-11", country = "GB",
                releaseEvents = emptyList(), labels = emptyList(), barcode = null, disambiguation = "Authored edition",
                textLanguage = "ara", textScript = "Arab", packaging = null, packagingMbid = null, problems = emptyList(),
            ),
            media = listOf(ReleaseMediumObservation(4, null, 1, null, "CD", tracks.size, 0,
                tracks, ReleaseMembershipStatus.OBSERVED_COMPLETE, emptyList())),
            membershipStatus = ReleaseMembershipStatus.OBSERVED_COMPLETE, problems = emptyList(),
        ),
        fetchedAtEpochMs = 123_000, coreDigest = "a".repeat(64), etag = null,
    )

    private fun track(ordinal: Int = 0, title: String? = "  B / العنوان الكامل  ") = ReleaseTrackObservation(
        sourceArrayOrdinal = ordinal, declaredPosition = 1, printedNumber = "A1", trackMbid = mbid(10 + ordinal),
        completeTrackTitle = title, artistCredits = emptyList(), lengthMs = 120_000,
        recordingMbid = mbid(3), recordingTitle = "Not the track title", problems = emptyList(),
    )

    private fun mbid(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
}
