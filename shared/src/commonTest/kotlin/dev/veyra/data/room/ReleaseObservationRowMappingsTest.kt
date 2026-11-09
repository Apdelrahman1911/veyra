package dev.veyra.data.room

import dev.veyra.model.CapturedReleaseObservation
import dev.veyra.model.ReleaseObservationId
import dev.veyra.source.release.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Authored core metadata only. These mapper/DAO-double tests are not Room or migration proof. */
class ReleaseObservationRowMappingsTest {
    @Test fun emptyGraphAndEmptyCapturesRoundTrip() {
        assertEquals(ReleaseObservationRows(), emptyList<CapturedReleaseObservation>().toReleaseObservationRows())
        assertEquals(emptyMap(), ReleaseObservationRows().toCapturedReleaseObservations())
    }

    @Test fun completeNestedCaptureRoundTripsWithoutFillingOrNormalizingFields() {
        val capture = captured()
        val restored = listOf(capture).toReleaseObservationRows().toCapturedReleaseObservations()
        assertEquals(mapOf(capture.id to capture), restored)
        assertEquals(TITLE, restored.getValue(capture.id).value.completeTitle)
        assertNotEquals(capture.value.requestedReleaseMbid, capture.value.observedReleaseMbid)
        assertEquals("https://musicbrainz.org/release/${mbid(2)}", restored.getValue(capture.id).value.sourceUrl)
        assertNull(restored.getValue(capture.id).value.metadata.media.first().tracks[1].completeTrackTitle)
        assertEquals("Not a track-title replacement", restored.getValue(capture.id).value.metadata.media.first().tracks[1].recordingTitle)
    }

    @Test fun everyScalarUsesItsExactNormalizedColumn() {
        val rows = listOf(captured()).toReleaseObservationRows()
        assertEquals(ReleaseObservationEntity(
            id = "observation-a", requestedReleaseMbid = mbid(1), observedReleaseMbid = mbid(2), completeTitle = TITLE,
            status = "Official", date = "2026", country = "", barcode = "000001", disambiguation = "Edition 2",
            textLanguage = "ara", textScript = "Arab", packaging = "Digipak", packagingMbid = mbid(10),
            membershipStatus = ReleaseMembershipStatus.INCOMPLETE, fetchedAtEpochMs = 123_456_789,
            coreDigest = "a".repeat(64), etag = "W/\"authored\"",
        ), rows.observations.single())
        assertEquals(ReleaseObservationGroupEntity("observation-a", mbid(3), "Conceptual group", "EP"), rows.groups.single())
        assertEquals(listOf(
            ReleaseGroupSecondaryTypeEntity("observation-a", 0, "Live"),
            ReleaseGroupSecondaryTypeEntity("observation-a", 1, ""),
            ReleaseGroupSecondaryTypeEntity("observation-a", 2, "Live"),
        ), rows.groupSecondaryTypes)
        assertEquals(ReleaseArtistCreditEntity("observation-a", 0, 6, mbid(4), "  الاسم المعتمد  ", " feat. ", "Canonical artist"), rows.artistCredits.first())
        assertEquals(ReleaseEventEntity("observation-a", 0, 8, "2026-01", mbid(5), ""), rows.events.first())
        assertEquals(ReleaseEventCountryEntity("observation-a", 0, 0, "EG"), rows.eventCountries.first())
        assertEquals(ReleaseLabelEntity("observation-a", 0, 4, mbid(9), "Authored label", " CAT-01 "), rows.labels.first())
        assertEquals(ReleaseMediumEntity("observation-a", 7, 0, mbid(8), 2, "", "CD", 3, 1, ReleaseMembershipStatus.INCOMPLETE), rows.media.first())
        assertEquals(ReleaseTrackEntity("observation-a", 7, 9, 0, 2, "B2", mbid(6), "  مسار / B  ", 123_456, mbid(7), "Independent recording C"), rows.tracks.first())
        assertEquals(ReleaseTrackArtistCreditEntity("observation-a", 7, 9, 0, 6, mbid(4), "  الاسم المعتمد  ", " feat. ", "Canonical artist"), rows.trackArtistCredits.first())
        assertEquals(ReleaseSummaryProblemEntity("observation-a", 0, SUMMARY_PROBLEM), rows.summaryProblems.first())
        assertEquals(ReleaseCoreProblemEntity("observation-a", 0, CORE_PROBLEM), rows.coreProblems.first())
        assertEquals(ReleaseMediumProblemEntity("observation-a", 7, 0, MEDIUM_PROBLEM), rows.mediumProblems.first())
        assertEquals(ReleaseTrackProblemEntity("observation-a", 7, 9, 0, TRACK_PROBLEM), rows.trackProblems.first())
    }

    @Test fun arbitraryDaoRowOrderDoesNotChangeAnyDtoListOrder() {
        val first = captured()
        val second = captured("observation-b").mapRelease { it.copy(completeTitle = "Another edition title") }
        val rows = listOf(first, second).toReleaseObservationRows()
        assertEquals(mapOf(first.id to first, second.id to second), rows.reversedTables().toCapturedReleaseObservations())
    }

    @Test fun absentGroupAndPresentAllNullEmptyGroupStayDistinct() {
        val absent = captured("absent").mapRelease { it.copy(releaseGroup = null) }
        val present = captured("present").mapRelease { it.copy(releaseGroup = ReleaseGroupMetadata(null, null, null, emptyList())) }
        val rows = listOf(absent, present).toReleaseObservationRows()
        assertEquals(listOf(ReleaseObservationGroupEntity("present", null, null, null)), rows.groups)
        assertTrue(rows.groupSecondaryTypes.isEmpty())
        assertEquals(mapOf(absent.id to absent, present.id to present), rows.toCapturedReleaseObservations())
    }

    @Test fun nullableStringsRemainNullVersusEmptyAtEveryLevel() {
        val nulls = nullableCapture("nulls", null)
        val empty = nullableCapture("empty", "")
        val rows = listOf(nulls, empty).toReleaseObservationRows()
        assertEquals(mapOf(nulls.id to nulls, empty.id to empty), rows.toCapturedReleaseObservations())
        assertNull(rows.observations.first().etag)
        assertEquals("", rows.observations.last().etag)
        assertNull(rows.tracks.first().recordingTitle)
        assertEquals("", rows.tracks.last().recordingTitle)
        assertNull(rows.artistCredits.first().joinPhrase)
        assertEquals("", rows.artistCredits.last().joinPhrase)
    }

    @Test fun optionalNumbersAndMalformedDeclaredValuesAreNotRepaired() {
        val base = nullableCapture("numbers", null)
        val malformed = base.mapFirstMedium { medium -> medium.copy(
            declaredPosition = -2, declaredTrackCount = -3, trackOffset = -4,
            tracks = medium.tracks.map { it.copy(declaredPosition = -5, lengthMs = -6) },
        ) }
        val zero = base.copy(id = ReleaseObservationId("zero")).mapFirstMedium { medium -> medium.copy(
            declaredPosition = 0, declaredTrackCount = 0, trackOffset = 0,
            tracks = medium.tracks.map { it.copy(declaredPosition = 0, lengthMs = 0) },
        ) }
        val unknown = base.copy(id = ReleaseObservationId("unknown"))
        val expected = listOf(malformed, zero, unknown)
        assertEquals(expected.associateBy { it.id }, expected.toReleaseObservationRows().toCapturedReleaseObservations())
    }

    @Test fun completenessFieldsRemainIndependentAndNeverFollowRowCounts() {
        for (coreStatus in ReleaseMembershipStatus.entries) {
            for (mediumStatus in ReleaseMembershipStatus.entries) {
                val capture = captured().mapCore { core -> core.copy(
                    membershipStatus = coreStatus,
                    media = core.media.map { it.copy(membershipStatus = mediumStatus) },
                ) }
                assertEquals(capture, listOf(capture).toReleaseObservationRows().toCapturedReleaseObservations()[capture.id])
            }
        }
    }

    @Test fun exactSameObservationIdAndValueMayBeDeduplicated() {
        val first = captured()
        val equalCopy = first.copy(value = first.value.copy(metadata = first.value.metadata.copy(media = first.value.metadata.media.toList())))
        val rows = listOf(first, equalCopy, first).toReleaseObservationRows()
        assertEquals(1, rows.observations.size)
        assertEquals(mapOf(first.id to first), rows.toCapturedReleaseObservations())
    }

    @Test fun sameObservationIdWithAnyChangedCaptureFailsInsteadOfLastWriterWinning() {
        val first = captured()
        for ((name, edit) in captureEdits()) {
            val changed = edit(first)
            assertNotEquals(first, changed, name)
            assertFailsWith<IllegalArgumentException>(name) { listOf(first, changed).toReleaseObservationRows() }
            assertFailsWith<IllegalArgumentException>(name) { listOf(changed, first).toReleaseObservationRows() }
        }
    }

    @Test fun distinctObservationIdsKeepSameEditionDigestAndMetadataAsSeparateCaptures() {
        val first = captured()
        val second = first.copy(id = ReleaseObservationId("observation-b"))
        val rows = listOf(first, second).toReleaseObservationRows()
        assertEquals(2, rows.observations.size)
        assertEquals(6, rows.tracks.size)
        assertEquals(mapOf(first.id to first, second.id to second), rows.toCapturedReleaseObservations())
    }

    @Test fun repeatedCreditsEventsLabelsCountriesAndProblemsRemainOccurrences() {
        val capture = captured()
        val rows = listOf(capture).toReleaseObservationRows()
        assertEquals(3, rows.artistCredits.size)
        assertEquals(3, rows.events.size)
        assertEquals(6, rows.eventCountries.size)
        assertEquals(3, rows.labels.size)
        assertEquals(3, rows.trackArtistCredits.size)
        assertEquals(2, rows.summaryProblems.size)
        assertEquals(2, rows.coreProblems.size)
        assertEquals(3, rows.mediumProblems.size)
        assertEquals(5, rows.trackProblems.size)
        val release = rows.toCapturedReleaseObservations().getValue(capture.id).value.metadata.release
        assertEquals(release.artistCredits[0], release.artistCredits[1])
        assertEquals(release.releaseEvents[0], release.releaseEvents[1])
        assertEquals(release.labels[0], release.labels[1])
        assertEquals(listOf("EG", "", "EG"), release.releaseEvents.first().countryCodes)
        assertEquals(listOf(SUMMARY_PROBLEM, SUMMARY_PROBLEM), release.problems)
    }

    @Test fun sourceOrdinalsAreNotListPositionsOrDeclaredPositions() {
        val rows = listOf(captured()).toReleaseObservationRows()
        assertEquals(listOf(7, 2), rows.media.map { it.sourceArrayOrdinal })
        assertEquals(listOf(0, 1), rows.media.map { it.listPosition })
        assertEquals(listOf(9, 3, 5), rows.tracks.map { it.sourceArrayOrdinal })
        assertEquals(listOf(0, 1, 2), rows.tracks.map { it.listPosition })
        assertEquals(listOf(6, 6, 1), rows.artistCredits.map { it.sourceArrayOrdinal })
        assertEquals(rows.tracks[0].trackMbid, rows.tracks[1].trackMbid)
        assertEquals(rows.tracks[0].recordingMbid, rows.tracks[1].recordingMbid)
        assertEquals(rows.tracks[0].declaredPosition, rows.tracks[1].declaredPosition)
        assertEquals(captured(), rows.reversedTables().toCapturedReleaseObservations()[ReleaseObservationId("observation-a")])
    }

    @Test fun problemAffectedOrdinalsAreNotForeignKeysToTheirOwningOccurrences() {
        val capture = captured().mapFirstTrack { track -> track.copy(problems = track.problems +
            ReleaseMetadataProblem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.TRACKS, -1, null)) }
        val rows = listOf(capture).toReleaseObservationRows()
        val medium = rows.mediumProblems.first()
        val track = rows.trackProblems.first()
        assertEquals(7, medium.ownerMediumOrdinal)
        assertEquals(101, medium.problem.mediumOrdinal)
        assertEquals(103, medium.problem.trackOrdinal)
        assertEquals(7, track.ownerMediumOrdinal)
        assertEquals(9, track.ownerTrackOrdinal)
        assertEquals(109, track.problem.mediumOrdinal)
        assertEquals(113, track.problem.trackOrdinal)
        assertEquals(capture, rows.toCapturedReleaseObservations()[capture.id])
    }

    @Test fun duplicatePrimaryKeysAreRejectedInEveryTableEvenForIdenticalRows() {
        val rows = listOf(captured()).toReleaseObservationRows()
        for ((name, duplicate) in duplicateMutations()) {
            assertFailsWith<IllegalArgumentException>(name) { duplicate(rows).toCapturedReleaseObservations() }
        }
    }

    @Test fun duplicateMediumSourceIdentityIsRejectedEvenWithDistinctListPositions() {
        val capture = captured().mapCore { it.copy(media = it.media + it.media.first()) }
        assertFailsWith<IllegalArgumentException> { listOf(capture).toReleaseObservationRows() }
    }

    @Test fun duplicateTrackSourceIdentityIsRejectedEvenWithDistinctListPositions() {
        val capture = captured().mapFirstMedium { it.copy(tracks = it.tracks + it.tracks.first()) }
        assertFailsWith<IllegalArgumentException> { listOf(capture).toReleaseObservationRows() }
    }

    @Test fun negativeSourceOrdinalsAreRejectedWithoutInventingReplacementOrdinals() {
        val rows = listOf(captured()).toReleaseObservationRows()
        val invalid = listOf(
            rows.copy(artistCredits = rows.artistCredits.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
            rows.copy(events = rows.events.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
            rows.copy(labels = rows.labels.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
            rows.copy(media = rows.media.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
            rows.copy(tracks = rows.tracks.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
            rows.copy(trackArtistCredits = rows.trackArtistCredits.replaceFirst { it.copy(sourceArrayOrdinal = -1) }),
        )
        invalid.forEach { assertFailsWith<IllegalArgumentException> { it.toCapturedReleaseObservations() } }
    }

    @Test fun duplicateListPositionsAreRejectedInEveryOrderedTable() {
        assertInvalidPositions { it + 1 }
    }

    @Test fun gappedListPositionsAreRejectedInEveryOrderedTable() {
        assertInvalidPositions { it + 100 }
    }

    @Test fun negativeListPositionsAreRejectedInEveryOrderedTable() {
        assertInvalidPositions { -1 }
    }

    @Test fun orphanRowsAreRejectedInEveryChildTable() {
        val rows = listOf(captured()).toReleaseObservationRows()
        for ((name, orphan) in orphanMutations()) {
            assertFailsWith<IllegalArgumentException>(name) { orphan(rows).toCapturedReleaseObservations() }
        }
    }

    @Test fun compositeOwnersMustExistEvenWhenTheObservationExists() {
        val rows = listOf(captured()).toReleaseObservationRows()
        val invalid = listOf(
            rows.copy(groups = emptyList()),
            rows.copy(eventCountries = rows.eventCountries.replaceFirst { it.copy(eventPosition = 999) }),
            rows.copy(tracks = rows.tracks.replaceFirst { it.copy(mediumArrayOrdinal = 999) }),
            rows.copy(trackArtistCredits = rows.trackArtistCredits.replaceFirst { it.copy(mediumArrayOrdinal = 999) }),
            rows.copy(trackArtistCredits = rows.trackArtistCredits.replaceFirst { it.copy(trackArrayOrdinal = 999) }),
            rows.copy(mediumProblems = rows.mediumProblems.replaceFirst { it.copy(ownerMediumOrdinal = 999) }),
            rows.copy(trackProblems = rows.trackProblems.replaceFirst { it.copy(ownerMediumOrdinal = 999) }),
            rows.copy(trackProblems = rows.trackProblems.replaceFirst { it.copy(ownerTrackOrdinal = 999) }),
        )
        invalid.forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException> { value.toCapturedReleaseObservations() }
            assertEquals("Orphan release row", failure.message)
        }
    }

    @Test fun invalidPersistedObservationIdFailsInsteadOfBecomingAnotherIdentity() {
        val row = listOf(captured()).toReleaseObservationRows().observations.single()
        for (id in listOf("", " ", "bad\nidentity", "x".repeat(257))) {
            val rows = ReleaseObservationRows(observations = listOf(row.copy(id = id)))
            assertFailsWith<IllegalArgumentException> { rows.toCapturedReleaseObservations() }
        }
    }

    @Test fun normalizationDetachesAllCallerOwnedNestedDtoLists() {
        val original = captured()
        val types = original.value.metadata.release.releaseGroup!!.secondaryTypes.toMutableList()
        val credits = original.value.metadata.release.artistCredits.toMutableList()
        val countries = original.value.metadata.release.releaseEvents.first().countryCodes.toMutableList()
        val events = original.value.metadata.release.releaseEvents.map { it.copy(countryCodes = countries) }.toMutableList()
        // The last event originally has no countries; preserve that distinction before mutation.
        events[events.lastIndex] = events.last().copy(countryCodes = emptyList())
        val labels = original.value.metadata.release.labels.toMutableList()
        val summaryProblems = original.value.metadata.release.problems.toMutableList()
        val coreProblems = original.value.metadata.problems.toMutableList()
        val mediumProblems = original.value.metadata.media.first().problems.toMutableList()
        val trackProblems = original.value.metadata.media.first().tracks.first().problems.toMutableList()
        val tracks = original.value.metadata.media.first().tracks.replaceFirst {
            it.copy(artistCredits = credits, problems = trackProblems)
        }.toMutableList()
        val media = original.value.metadata.media.replaceFirst {
            it.copy(tracks = tracks, problems = mediumProblems)
        }.toMutableList()
        val supplied = original.mapCore { core -> core.copy(
            release = core.release.copy(
                releaseGroup = core.release.releaseGroup!!.copy(secondaryTypes = types),
                artistCredits = credits, releaseEvents = events, labels = labels, problems = summaryProblems,
            ),
            media = media, problems = coreProblems,
        ) }
        assertEquals(original, supplied)
        val rows = listOf(supplied).toReleaseObservationRows()
        types.clear(); credits.clear(); countries.clear(); events.clear(); labels.clear()
        summaryProblems.clear(); coreProblems.clear(); mediumProblems.clear(); trackProblems.clear()
        tracks.clear(); media.clear()
        assertEquals(original, rows.toCapturedReleaseObservations()[original.id])
    }

    @Test fun reconstructionDetachesCallerOwnedRowLists() {
        val capture = captured()
        val (rows, clear) = listOf(capture).toReleaseObservationRows().mutableTables()
        val restored = rows.toCapturedReleaseObservations()
        clear()
        assertEquals(mapOf(capture.id to capture), restored)
        assertEquals(ReleaseObservationRows(), rows)
    }

    @Test fun readHelperGathersExactlyAllFourteenTablesWithoutReordering() = runTest {
        val raw = listOf(captured()).toReleaseObservationRows().reversedTables()
        val dao = RecordingReleaseDao(raw)
        assertEquals(raw, dao.readCapturedReleaseRows())
        assertEquals(TABLES.map { "read:$it" }, dao.calls)
    }

    @Test fun readHelperLeavesFullGraphValidationToReconstruction() = runTest {
        val invalid = listOf(captured()).toReleaseObservationRows().copy(observations = emptyList())
        val dao = RecordingReleaseDao(invalid)
        val raw = dao.readCapturedReleaseRows()
        assertEquals(invalid, raw)
        assertFailsWith<IllegalArgumentException> { raw.toCapturedReleaseObservations() }
    }

    @Test fun appendEmptyAndUnchangedGraphsMakeNoDaoCalls() = runTest {
        val empty = RecordingReleaseDao()
        empty.appendCapturedReleaseRows(ReleaseObservationRows(), ReleaseObservationRows())
        assertTrue(empty.calls.isEmpty())
        val before = listOf(captured()).toReleaseObservationRows()
        val dao = RecordingReleaseDao(before)
        dao.appendCapturedReleaseRows(before, before.reversedTables())
        assertTrue(dao.calls.isEmpty())
        assertEquals(before, dao.rows)
    }

    @Test fun appendNewObservationUsesOnlyParentBeforeChildInsertMethods() = runTest {
        val next = listOf(captured()).toReleaseObservationRows()
        val dao = RecordingReleaseDao()
        dao.appendCapturedReleaseRows(ReleaseObservationRows(), next.reversedTables())
        assertEquals(TABLES.map { "insert:$it" }, dao.calls)
        assertEquals(next, dao.rows)
    }

    @Test fun appendInsertsOnlyEntireNewIdsNotExistingObservationRows() = runTest {
        val first = captured()
        val second = captured("observation-b")
        val before = listOf(first).toReleaseObservationRows()
        val desired = listOf(second, first).toReleaseObservationRows().reversedTables()
        val dao = RecordingReleaseDao(before)
        dao.appendCapturedReleaseRows(before, desired)
        assertEquals(TABLES.map { "insert:$it" }, dao.calls)
        assertEquals(mapOf(first.id to first, second.id to second), dao.rows.toCapturedReleaseObservations())
        assertEquals(listOf("observation-a", "observation-b"), dao.rows.observations.map { it.id })
    }

    @Test fun invalidBeforeGraphFailsBeforeAnyInsertInEveryTable() = runTest {
        val before = listOf(captured()).toReleaseObservationRows()
        val next = listOf(captured(), captured("observation-b")).toReleaseObservationRows()
        for ((name, invalid) in duplicateMutations()) {
            val badBefore = invalid(before)
            val dao = RecordingReleaseDao(badBefore)
            assertFailsWith<IllegalArgumentException>(name) { dao.appendCapturedReleaseRows(badBefore, next) }
            assertTrue(dao.calls.isEmpty(), name)
            assertEquals(badBefore, dao.rows, name)
        }
    }

    @Test fun invalidNextGraphFailsBeforeAnyInsertInEveryTable() = runTest {
        val before = listOf(captured()).toReleaseObservationRows()
        val next = listOf(captured("observation-b"), captured()).toReleaseObservationRows()
        for ((name, invalid) in duplicateMutations()) {
            val dao = RecordingReleaseDao(before)
            assertFailsWith<IllegalArgumentException>(name) { dao.appendCapturedReleaseRows(before, invalid(next)) }
            assertTrue(dao.calls.isEmpty(), name)
            assertEquals(before, dao.rows, name)
        }
    }

    @Test fun removingExistingObservationFailsBeforeWritingAnyNewId() = runTest {
        val before = listOf(captured()).toReleaseObservationRows()
        val dao = RecordingReleaseDao(before)
        val next = listOf(captured("observation-b")).toReleaseObservationRows()
        assertFailsWith<IllegalArgumentException> { dao.appendCapturedReleaseRows(before, next) }
        assertTrue(dao.calls.isEmpty())
        assertEquals(before, dao.rows)
    }

    @Test fun editingExtendingOrShrinkingExistingCaptureFailsBeforeAnyInsert() = runTest {
        val existing = captured()
        val before = listOf(existing).toReleaseObservationRows()
        for ((name, edit) in captureEdits()) {
            val dao = RecordingReleaseDao(before)
            val next = listOf(captured("observation-b"), edit(existing)).toReleaseObservationRows()
            assertFailsWith<IllegalArgumentException>(name) { dao.appendCapturedReleaseRows(before, next) }
            assertTrue(dao.calls.isEmpty(), name)
            assertEquals(before, dao.rows, name)
        }
    }

    @Test fun appendDetachesValidatedInputsBeforeItsFirstDaoSuspension() = runTest {
        val expected = listOf(captured()).toReleaseObservationRows()
        val (next, clear) = expected.mutableTables()
        val dao = RecordingReleaseDao()
        dao.beforeInsert = { clear(); dao.beforeInsert = null }
        dao.appendCapturedReleaseRows(ReleaseObservationRows(), next)
        assertEquals(ReleaseObservationRows(), next)
        assertEquals(expected, dao.rows)
        assertEquals(TABLES.map { "insert:$it" }, dao.calls)
    }

    @Test fun daoFailurePropagatesWithoutRetryOrCompensatingWrites() = runTest {
        val next = listOf(captured()).toReleaseObservationRows()
        val dao = RecordingReleaseDao()
        dao.failAt = "labels"
        val actual = assertFailsWith<IllegalStateException> {
            dao.appendCapturedReleaseRows(ReleaseObservationRows(), next)
        }
        assertSame(dao.failure, actual)
        assertEquals(TABLES.take(TABLES.indexOf("labels") + 1).map { "insert:$it" }, dao.calls)
        // This nontransactional double retains earlier calls. The real Room owner must roll back.
        assertEquals(next.observations, dao.rows.observations)
        assertTrue(dao.rows.labels.isEmpty())
        assertTrue(dao.rows.media.isEmpty())
    }

    @Test fun validationDiagnosticsDoNotIncludeCapturedMetadataOrIds() {
        val rows = listOf(captured()).toReleaseObservationRows()
        val duplicate = rows.copy(observations = rows.observations + rows.observations)
        val duplicateFailure = assertFailsWith<IllegalArgumentException> { duplicate.toCapturedReleaseObservations() }
        assertEquals("Duplicate release row key", duplicateFailure.message)
        val conflictFailure = assertFailsWith<IllegalArgumentException> {
            listOf(captured(), captured().mapRelease { it.copy(completeTitle = "different private-looking title") }).toReleaseObservationRows()
        }
        assertEquals("Conflicting captured release observation", conflictFailure.message)
    }

    private fun assertInvalidPositions(change: (Int) -> Int) {
        val rows = listOf(captured()).toReleaseObservationRows()
        for ((name, invalid) in positionMutations(change)) {
            assertFailsWith<IllegalArgumentException>(name) { invalid(rows).toCapturedReleaseObservations() }
        }
    }
}

private const val TITLE = "  إصدار ڤيرا / A — EP  "
private val SUMMARY_PROBLEM = ReleaseMetadataProblem(ReleaseProblemCode.INVALID_ID, ReleaseMetadataField.RELEASE_ID, 73, null)
private val CORE_PROBLEM = ReleaseMetadataProblem(ReleaseProblemCode.TRACK_COUNT_MISMATCH, ReleaseMetadataField.TRACK_COUNT, null, 99)
private val MEDIUM_PROBLEM = ReleaseMetadataProblem(ReleaseProblemCode.POSITION_GAP, ReleaseMetadataField.TRACK_POSITION, 101, 103)
private val TRACK_PROBLEM = ReleaseMetadataProblem(ReleaseProblemCode.INVALID_TITLE, ReleaseMetadataField.TRACK_TITLE, 109, 113)
private val TABLES = listOf("observations", "groups", "groupSecondaryTypes", "artistCredits", "events", "eventCountries", "labels",
    "media", "tracks", "trackArtistCredits", "summaryProblems", "coreProblems", "mediumProblems", "trackProblems")

private fun captured(id: String = "observation-a"): CapturedReleaseObservation {
    val credit = ReleaseArtistCredit(6, mbid(4), "  الاسم المعتمد  ", " feat. ", "Canonical artist")
    val credits = listOf(credit, credit, ReleaseArtistCredit(1, null, "", null, ""))
    val event = ReleaseEventMetadata(8, "2026-01", mbid(5), "", listOf("EG", "", "EG"))
    val label = ReleaseLabelMetadata(4, mbid(9), "Authored label", " CAT-01 ")
    val tracks = listOf(
        ReleaseTrackObservation(9, 2, "B2", mbid(6), "  مسار / B  ", credits, 123_456, mbid(7), "Independent recording C",
            listOf(TRACK_PROBLEM, TRACK_PROBLEM, ReleaseMetadataProblem(ReleaseProblemCode.MISSING_VALUE, ReleaseMetadataField.FORMAT))),
        ReleaseTrackObservation(3, 2, "1.02", mbid(6), null, emptyList(), null, mbid(7), "Not a track-title replacement",
            listOf(ReleaseMetadataProblem(ReleaseProblemCode.MISSING_TITLE, ReleaseMetadataField.TRACK_TITLE, 7, 3))),
        ReleaseTrackObservation(5, null, "", null, "", emptyList(), 0, null, "",
            listOf(ReleaseMetadataProblem(ReleaseProblemCode.MALFORMED_ROW, ReleaseMetadataField.TRACKS))),
    )
    return CapturedReleaseObservation(ReleaseObservationId(id), ReleaseObservation(
        requestedReleaseMbid = mbid(1),
        metadata = ReleaseCoreMetadata(
            release = ReleaseSummaryMetadata(
                releaseMbid = mbid(2), completeTitle = TITLE,
                releaseGroup = ReleaseGroupMetadata(mbid(3), "Conceptual group", "EP", listOf("Live", "", "Live")),
                artistCredits = credits, status = "Official", date = "2026", country = "",
                releaseEvents = listOf(event, event, ReleaseEventMetadata(0, null, null, null, emptyList())),
                labels = listOf(label, label, ReleaseLabelMetadata(0, null, null, null)),
                barcode = "000001", disambiguation = "Edition 2", textLanguage = "ara", textScript = "Arab",
                packaging = "Digipak", packagingMbid = mbid(10), problems = listOf(SUMMARY_PROBLEM, SUMMARY_PROBLEM),
            ),
            media = listOf(
                ReleaseMediumObservation(7, mbid(8), 2, "", "CD", 3, 1, tracks, ReleaseMembershipStatus.INCOMPLETE,
                    listOf(MEDIUM_PROBLEM, MEDIUM_PROBLEM)),
                ReleaseMediumObservation(2, mbid(8), null, null, null, null, null, emptyList(), ReleaseMembershipStatus.UNKNOWN,
                    listOf(ReleaseMetadataProblem(ReleaseProblemCode.MISSING_TRACKS, ReleaseMetadataField.TRACKS))),
            ),
            membershipStatus = ReleaseMembershipStatus.INCOMPLETE, problems = listOf(CORE_PROBLEM, CORE_PROBLEM),
        ),
        fetchedAtEpochMs = 123_456_789, coreDigest = "a".repeat(64), etag = "W/\"authored\"",
    ))
}

private fun nullableCapture(id: String, text: String?): CapturedReleaseObservation {
    val credits = listOf(ReleaseArtistCredit(0, text, text, text, text))
    return captured(id).let { capture -> capture.copy(value = capture.value.copy(etag = text)) }.mapCore { core -> core.copy(
        release = core.release.copy(
            releaseGroup = ReleaseGroupMetadata(text, text, text, emptyList()), artistCredits = credits,
            status = text, date = text, country = text, barcode = text, disambiguation = text,
            textLanguage = text, textScript = text, packaging = text, packagingMbid = text,
            releaseEvents = listOf(ReleaseEventMetadata(0, text, text, text, emptyList())),
            labels = listOf(ReleaseLabelMetadata(0, text, text, text)), problems = emptyList(),
        ),
        media = listOf(ReleaseMediumObservation(0, text, null, text, text, null, null,
            listOf(ReleaseTrackObservation(0, null, text, text, text, credits, null, text, text, emptyList())),
            ReleaseMembershipStatus.UNKNOWN, emptyList())),
        problems = emptyList(),
    ) }
}

private fun mbid(value: Int) = "00000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
private fun <T> List<T>.replaceFirst(change: (T) -> T): List<T> = listOf(change(first())) + drop(1)
private fun CapturedReleaseObservation.mapCore(change: (ReleaseCoreMetadata) -> ReleaseCoreMetadata) =
    copy(value = value.copy(metadata = change(value.metadata)))
private fun CapturedReleaseObservation.mapRelease(change: (ReleaseSummaryMetadata) -> ReleaseSummaryMetadata) =
    mapCore { it.copy(release = change(it.release)) }
private fun CapturedReleaseObservation.mapFirstMedium(change: (ReleaseMediumObservation) -> ReleaseMediumObservation) =
    mapCore { it.copy(media = it.media.replaceFirst(change)) }
private fun CapturedReleaseObservation.mapFirstTrack(change: (ReleaseTrackObservation) -> ReleaseTrackObservation) =
    mapFirstMedium { it.copy(tracks = it.tracks.replaceFirst(change)) }

private fun captureEdits(): List<Pair<String, (CapturedReleaseObservation) -> CapturedReleaseObservation>> = listOf(
    "requested edition" to { it.copy(value = it.value.copy(requestedReleaseMbid = mbid(20))) },
    "observed edition" to { it.mapRelease { release -> release.copy(releaseMbid = mbid(21)) } },
    "unchanged title bytes" to { it.mapRelease { release -> release.copy(completeTitle = TITLE.trim()) } },
    "status" to { it.mapRelease { release -> release.copy(status = null) } },
    "group absent" to { it.mapRelease { release -> release.copy(releaseGroup = null) } },
    "extra credit" to { it.mapRelease { release -> release.copy(artistCredits = release.artistCredits + release.artistCredits.first()) } },
    "removed label" to { it.mapRelease { release -> release.copy(labels = release.labels.dropLast(1)) } },
    "extra problem" to { it.mapCore { core -> core.copy(problems = core.problems + CORE_PROBLEM) } },
    "reordered media" to { it.mapCore { core -> core.copy(media = core.media.reversed()) } },
    "removed track" to { it.mapFirstMedium { medium -> medium.copy(tracks = medium.tracks.drop(1)) } },
    "extra track" to { it.mapFirstMedium { medium -> medium.copy(tracks = medium.tracks + medium.tracks.first().copy(sourceArrayOrdinal = 100)) } },
    "reordered tracks" to { it.mapFirstMedium { medium -> medium.copy(tracks = medium.tracks.reversed()) } },
    "track null title" to { it.mapFirstTrack { track -> track.copy(completeTrackTitle = null) } },
    "affected ordinal" to { it.mapFirstTrack { track -> track.copy(problems = track.problems.replaceFirst { p -> p.copy(trackOrdinal = null) }) } },
    "completeness" to { it.mapCore { core -> core.copy(membershipStatus = ReleaseMembershipStatus.OBSERVED_COMPLETE) } },
    "fetch time" to { it.copy(value = it.value.copy(fetchedAtEpochMs = it.value.fetchedAtEpochMs + 1)) },
    "digest" to { it.copy(value = it.value.copy(coreDigest = "b".repeat(64))) },
    "etag" to { it.copy(value = it.value.copy(etag = "")) },
)

private typealias RowMutation = (ReleaseObservationRows) -> ReleaseObservationRows

private fun duplicateMutations(): List<Pair<String, RowMutation>> = listOf(
    "observations" to { it.copy(observations = it.observations + it.observations.first()) },
    "groups" to { it.copy(groups = it.groups + it.groups.first()) },
    "groupSecondaryTypes" to { it.copy(groupSecondaryTypes = it.groupSecondaryTypes + it.groupSecondaryTypes.first()) },
    "artistCredits" to { it.copy(artistCredits = it.artistCredits + it.artistCredits.first()) },
    "events" to { it.copy(events = it.events + it.events.first()) },
    "eventCountries" to { it.copy(eventCountries = it.eventCountries + it.eventCountries.first()) },
    "labels" to { it.copy(labels = it.labels + it.labels.first()) },
    "media" to { it.copy(media = it.media + it.media.first()) },
    "tracks" to { it.copy(tracks = it.tracks + it.tracks.first()) },
    "trackArtistCredits" to { it.copy(trackArtistCredits = it.trackArtistCredits + it.trackArtistCredits.first()) },
    "summaryProblems" to { it.copy(summaryProblems = it.summaryProblems + it.summaryProblems.first()) },
    "coreProblems" to { it.copy(coreProblems = it.coreProblems + it.coreProblems.first()) },
    "mediumProblems" to { it.copy(mediumProblems = it.mediumProblems + it.mediumProblems.first()) },
    "trackProblems" to { it.copy(trackProblems = it.trackProblems + it.trackProblems.first()) },
)

private fun positionMutations(change: (Int) -> Int): List<Pair<String, RowMutation>> = listOf(
    "groupSecondaryTypes" to { it.copy(groupSecondaryTypes = it.groupSecondaryTypes.replaceFirst { row -> row.copy(position = change(row.position)) }) },
    "artistCredits" to { it.copy(artistCredits = it.artistCredits.replaceFirst { row -> row.copy(position = change(row.position)) }) },
    "events" to { it.copy(events = it.events.replaceFirst { row -> row.copy(eventPosition = change(row.eventPosition)) }) },
    "eventCountries" to { it.copy(eventCountries = it.eventCountries.replaceFirst { row -> row.copy(countryPosition = change(row.countryPosition)) }) },
    "labels" to { it.copy(labels = it.labels.replaceFirst { row -> row.copy(position = change(row.position)) }) },
    "media" to { it.copy(media = it.media.replaceFirst { row -> row.copy(listPosition = change(row.listPosition)) }) },
    "tracks" to { it.copy(tracks = it.tracks.replaceFirst { row -> row.copy(listPosition = change(row.listPosition)) }) },
    "trackArtistCredits" to { it.copy(trackArtistCredits = it.trackArtistCredits.replaceFirst { row -> row.copy(creditPosition = change(row.creditPosition)) }) },
    "summaryProblems" to { it.copy(summaryProblems = it.summaryProblems.replaceFirst { row -> row.copy(problemPosition = change(row.problemPosition)) }) },
    "coreProblems" to { it.copy(coreProblems = it.coreProblems.replaceFirst { row -> row.copy(problemPosition = change(row.problemPosition)) }) },
    "mediumProblems" to { it.copy(mediumProblems = it.mediumProblems.replaceFirst { row -> row.copy(problemPosition = change(row.problemPosition)) }) },
    "trackProblems" to { it.copy(trackProblems = it.trackProblems.replaceFirst { row -> row.copy(problemPosition = change(row.problemPosition)) }) },
)

private fun orphanMutations(): List<Pair<String, RowMutation>> = listOf(
    "groups" to { it.copy(groups = it.groups.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "groupSecondaryTypes" to { it.copy(groupSecondaryTypes = it.groupSecondaryTypes.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "artistCredits" to { it.copy(artistCredits = it.artistCredits.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "events" to { it.copy(events = it.events.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "eventCountries" to { it.copy(eventCountries = it.eventCountries.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "labels" to { it.copy(labels = it.labels.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "media" to { it.copy(media = it.media.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "tracks" to { it.copy(tracks = it.tracks.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "trackArtistCredits" to { it.copy(trackArtistCredits = it.trackArtistCredits.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "summaryProblems" to { it.copy(summaryProblems = it.summaryProblems.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "coreProblems" to { it.copy(coreProblems = it.coreProblems.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "mediumProblems" to { it.copy(mediumProblems = it.mediumProblems.replaceFirst { row -> row.copy(observationId = "absent") }) },
    "trackProblems" to { it.copy(trackProblems = it.trackProblems.replaceFirst { row -> row.copy(observationId = "absent") }) },
)

private fun ReleaseObservationRows.reversedTables() = ReleaseObservationRows(
    observations.reversed(), groups.reversed(), groupSecondaryTypes.reversed(), artistCredits.reversed(),
    events.reversed(), eventCountries.reversed(), labels.reversed(), media.reversed(), tracks.reversed(),
    trackArtistCredits.reversed(), summaryProblems.reversed(), coreProblems.reversed(), mediumProblems.reversed(), trackProblems.reversed(),
)

private fun ReleaseObservationRows.mutableTables(): Pair<ReleaseObservationRows, () -> Unit> {
    val observations = observations.toMutableList()
    val groups = groups.toMutableList()
    val groupSecondaryTypes = groupSecondaryTypes.toMutableList()
    val artistCredits = artistCredits.toMutableList()
    val events = events.toMutableList()
    val eventCountries = eventCountries.toMutableList()
    val labels = labels.toMutableList()
    val media = media.toMutableList()
    val tracks = tracks.toMutableList()
    val trackArtistCredits = trackArtistCredits.toMutableList()
    val summaryProblems = summaryProblems.toMutableList()
    val coreProblems = coreProblems.toMutableList()
    val mediumProblems = mediumProblems.toMutableList()
    val trackProblems = trackProblems.toMutableList()
    return ReleaseObservationRows(
        observations, groups, groupSecondaryTypes, artistCredits, events, eventCountries, labels,
        media, tracks, trackArtistCredits, summaryProblems, coreProblems, mediumProblems, trackProblems,
    ) to {
        observations.clear(); groups.clear(); groupSecondaryTypes.clear(); artistCredits.clear()
        events.clear(); eventCountries.clear(); labels.clear(); media.clear(); tracks.clear()
        trackArtistCredits.clear(); summaryProblems.clear(); coreProblems.clear(); mediumProblems.clear(); trackProblems.clear()
    }
}

/** Counts exact calls only; deliberately not a Room, FK, ABORT or transaction simulation. */
private class RecordingReleaseDao(initial: ReleaseObservationRows = ReleaseObservationRows()) : AcquisitionDao {
    var rows: ReleaseObservationRows = initial
        private set
    val calls = mutableListOf<String>()
    var beforeInsert: (() -> Unit)? = null
    var failAt: String? = null
    val failure = IllegalStateException("Authored DAO failure")

    private fun <T> read(table: String, value: () -> List<T>): List<T> {
        calls += "read:$table"
        return value()
    }

    private fun inserting(table: String) {
        calls += "insert:$table"
        beforeInsert?.invoke()
        if (failAt == table) throw failure
    }

    private fun unexpected(): Nothing = error("Unexpected acquisition DAO access")

    override suspend fun stageExecutionQueueMemberPositions(ids: List<String>): Unit = unexpected()
    override suspend fun stageListeningHistoryPositions(ids: List<String>): Unit = unexpected()
    override suspend fun stagePlaylistMemberPositions(ids: List<String>): Unit = unexpected()
    override suspend fun readAcquisitionRequestRows(): List<AcquisitionRequestEntity> = unexpected()
    override suspend fun insertAcquisitionRequestRows(rows: List<AcquisitionRequestEntity>): Unit = unexpected()
    override suspend fun readRequestMediaMetadataRows(): List<RequestMediaMetadataEntity> = unexpected()
    override suspend fun insertRequestMediaMetadataRows(rows: List<RequestMediaMetadataEntity>): Unit = unexpected()
    override suspend fun readRequestMediaArtistRows(): List<RequestMediaArtistEntity> = unexpected()
    override suspend fun insertRequestMediaArtistRows(rows: List<RequestMediaArtistEntity>): Unit = unexpected()
    override suspend fun readRequestAlbumMetadataRows(): List<RequestAlbumMetadataEntity> = unexpected()
    override suspend fun insertRequestAlbumMetadataRows(rows: List<RequestAlbumMetadataEntity>): Unit = unexpected()
    override suspend fun readRequestAlbumArtistRows(): List<RequestAlbumArtistEntity> = unexpected()
    override suspend fun insertRequestAlbumArtistRows(rows: List<RequestAlbumArtistEntity>): Unit = unexpected()
    override suspend fun readRequestAlbumMemberRows(): List<RequestAlbumMemberEntity> = unexpected()
    override suspend fun insertRequestAlbumMemberRows(rows: List<RequestAlbumMemberEntity>): Unit = unexpected()
    override suspend fun readRequestAlbumMemberArtistRows(): List<RequestAlbumMemberArtistEntity> = unexpected()
    override suspend fun insertRequestAlbumMemberArtistRows(rows: List<RequestAlbumMemberArtistEntity>): Unit = unexpected()
    override suspend fun readRequestShowMetadataRows(): List<RequestShowMetadataEntity> = unexpected()
    override suspend fun insertRequestShowMetadataRows(rows: List<RequestShowMetadataEntity>): Unit = unexpected()
    override suspend fun readRequestShowAliasRows(): List<RequestShowAliasEntity> = unexpected()
    override suspend fun insertRequestShowAliasRows(rows: List<RequestShowAliasEntity>): Unit = unexpected()
    override suspend fun readRequestShowMemberRows(): List<RequestShowMemberEntity> = unexpected()
    override suspend fun insertRequestShowMemberRows(rows: List<RequestShowMemberEntity>): Unit = unexpected()
    override suspend fun readRequestSourceCollectionMetadataRows(): List<RequestSourceCollectionMetadataEntity> = unexpected()
    override suspend fun insertRequestSourceCollectionMetadataRows(rows: List<RequestSourceCollectionMetadataEntity>): Unit = unexpected()
    override suspend fun readReleaseObservationRows(): List<ReleaseObservationEntity> = read("observations") { rows.observations }
    override suspend fun insertReleaseObservationRows(rows: List<ReleaseObservationEntity>): Unit {
        inserting("observations")
        this.rows = this.rows.copy(observations = this.rows.observations + rows)
    }
    override suspend fun readReleaseObservationGroupRows(): List<ReleaseObservationGroupEntity> = read("groups") { rows.groups }
    override suspend fun insertReleaseObservationGroupRows(rows: List<ReleaseObservationGroupEntity>): Unit {
        inserting("groups")
        this.rows = this.rows.copy(groups = this.rows.groups + rows)
    }
    override suspend fun readReleaseGroupSecondaryTypeRows(): List<ReleaseGroupSecondaryTypeEntity> = read("groupSecondaryTypes") { rows.groupSecondaryTypes }
    override suspend fun insertReleaseGroupSecondaryTypeRows(rows: List<ReleaseGroupSecondaryTypeEntity>): Unit {
        inserting("groupSecondaryTypes")
        this.rows = this.rows.copy(groupSecondaryTypes = this.rows.groupSecondaryTypes + rows)
    }
    override suspend fun readReleaseArtistCreditRows(): List<ReleaseArtistCreditEntity> = read("artistCredits") { rows.artistCredits }
    override suspend fun insertReleaseArtistCreditRows(rows: List<ReleaseArtistCreditEntity>): Unit {
        inserting("artistCredits")
        this.rows = this.rows.copy(artistCredits = this.rows.artistCredits + rows)
    }
    override suspend fun readReleaseEventRows(): List<ReleaseEventEntity> = read("events") { rows.events }
    override suspend fun insertReleaseEventRows(rows: List<ReleaseEventEntity>): Unit {
        inserting("events")
        this.rows = this.rows.copy(events = this.rows.events + rows)
    }
    override suspend fun readReleaseEventCountryRows(): List<ReleaseEventCountryEntity> = read("eventCountries") { rows.eventCountries }
    override suspend fun insertReleaseEventCountryRows(rows: List<ReleaseEventCountryEntity>): Unit {
        inserting("eventCountries")
        this.rows = this.rows.copy(eventCountries = this.rows.eventCountries + rows)
    }
    override suspend fun readReleaseLabelRows(): List<ReleaseLabelEntity> = read("labels") { rows.labels }
    override suspend fun insertReleaseLabelRows(rows: List<ReleaseLabelEntity>): Unit {
        inserting("labels")
        this.rows = this.rows.copy(labels = this.rows.labels + rows)
    }
    override suspend fun readReleaseMediumRows(): List<ReleaseMediumEntity> = read("media") { rows.media }
    override suspend fun insertReleaseMediumRows(rows: List<ReleaseMediumEntity>): Unit {
        inserting("media")
        this.rows = this.rows.copy(media = this.rows.media + rows)
    }
    override suspend fun readReleaseTrackRows(): List<ReleaseTrackEntity> = read("tracks") { rows.tracks }
    override suspend fun insertReleaseTrackRows(rows: List<ReleaseTrackEntity>): Unit {
        inserting("tracks")
        this.rows = this.rows.copy(tracks = this.rows.tracks + rows)
    }
    override suspend fun readReleaseTrackArtistCreditRows(): List<ReleaseTrackArtistCreditEntity> = read("trackArtistCredits") { rows.trackArtistCredits }
    override suspend fun insertReleaseTrackArtistCreditRows(rows: List<ReleaseTrackArtistCreditEntity>): Unit {
        inserting("trackArtistCredits")
        this.rows = this.rows.copy(trackArtistCredits = this.rows.trackArtistCredits + rows)
    }
    override suspend fun readReleaseSummaryProblemRows(): List<ReleaseSummaryProblemEntity> = read("summaryProblems") { rows.summaryProblems }
    override suspend fun insertReleaseSummaryProblemRows(rows: List<ReleaseSummaryProblemEntity>): Unit {
        inserting("summaryProblems")
        this.rows = this.rows.copy(summaryProblems = this.rows.summaryProblems + rows)
    }
    override suspend fun readReleaseCoreProblemRows(): List<ReleaseCoreProblemEntity> = read("coreProblems") { rows.coreProblems }
    override suspend fun insertReleaseCoreProblemRows(rows: List<ReleaseCoreProblemEntity>): Unit {
        inserting("coreProblems")
        this.rows = this.rows.copy(coreProblems = this.rows.coreProblems + rows)
    }
    override suspend fun readReleaseMediumProblemRows(): List<ReleaseMediumProblemEntity> = read("mediumProblems") { rows.mediumProblems }
    override suspend fun insertReleaseMediumProblemRows(rows: List<ReleaseMediumProblemEntity>): Unit {
        inserting("mediumProblems")
        this.rows = this.rows.copy(mediumProblems = this.rows.mediumProblems + rows)
    }
    override suspend fun readReleaseTrackProblemRows(): List<ReleaseTrackProblemEntity> = read("trackProblems") { rows.trackProblems }
    override suspend fun insertReleaseTrackProblemRows(rows: List<ReleaseTrackProblemEntity>): Unit {
        inserting("trackProblems")
        this.rows = this.rows.copy(trackProblems = this.rows.trackProblems + rows)
    }
    override suspend fun readRequestReleaseMetadataRows(): List<RequestReleaseMetadataEntity> = unexpected()
    override suspend fun insertRequestReleaseMetadataRows(rows: List<RequestReleaseMetadataEntity>): Unit = unexpected()
    override suspend fun readRequestMediaReleaseOccurrenceRows(): List<RequestMediaReleaseOccurrenceEntity> = unexpected()
    override suspend fun insertRequestMediaReleaseOccurrenceRows(rows: List<RequestMediaReleaseOccurrenceEntity>): Unit = unexpected()
    override suspend fun readReleaseCatalogEntryRows(): List<ReleaseCatalogEntryEntity> = unexpected()
    override suspend fun upsertReleaseCatalogEntryRows(rows: List<ReleaseCatalogEntryEntity>): Unit = unexpected()
    override suspend fun deleteReleaseCatalogEntryRows(rows: List<ReleaseCatalogEntryEntity>): Unit = unexpected()
    override suspend fun readSavedReleaseRows(): List<SavedReleaseEntity> = unexpected()
    override suspend fun upsertSavedReleaseRows(rows: List<SavedReleaseEntity>): Unit = unexpected()
    override suspend fun deleteSavedReleaseRows(rows: List<SavedReleaseEntity>): Unit = unexpected()
    override suspend fun readFirstResultReceiptRows(): List<FirstResultReceiptEntity> = unexpected()
    override suspend fun insertFirstResultReceiptRows(rows: List<FirstResultReceiptEntity>): Unit = unexpected()
    override suspend fun readSourceRecordingRows(): List<SourceRecordingEntity> = unexpected()
    override suspend fun insertSourceRecordingRows(rows: List<SourceRecordingEntity>): Unit = unexpected()
    override suspend fun readExecutableSubjectRows(): List<ExecutableSubjectEntity> = unexpected()
    override suspend fun insertExecutableSubjectRows(rows: List<ExecutableSubjectEntity>): Unit = unexpected()
    override suspend fun readExecutionBindingRows(): List<ExecutionBindingEntity> = unexpected()
    override suspend fun insertExecutionBindingRows(rows: List<ExecutionBindingEntity>): Unit = unexpected()
    override suspend fun readAcquisitionOutcomeRows(): List<AcquisitionOutcomeEntity> = unexpected()
    override suspend fun insertAcquisitionOutcomeRows(rows: List<AcquisitionOutcomeEntity>): Unit = unexpected()
    override suspend fun readAcquisitionJobRows(): List<AcquisitionJobEntity> = unexpected()
    override suspend fun upsertAcquisitionJobRows(rows: List<AcquisitionJobEntity>): Unit = unexpected()
    override suspend fun deleteAcquisitionJobRows(rows: List<AcquisitionJobEntity>): Unit = unexpected()
    override suspend fun readCollectionRunRows(): List<CollectionRunEntity> = unexpected()
    override suspend fun upsertCollectionRunRows(rows: List<CollectionRunEntity>): Unit = unexpected()
    override suspend fun deleteCollectionRunRows(rows: List<CollectionRunEntity>): Unit = unexpected()
    override suspend fun readCollectionObservationRows(): List<CollectionObservationEntity> = unexpected()
    override suspend fun insertCollectionObservationRows(rows: List<CollectionObservationEntity>): Unit = unexpected()
    override suspend fun readCollectionIdentityEvidenceRows(): List<CollectionIdentityEvidenceEntity> = unexpected()
    override suspend fun insertCollectionIdentityEvidenceRows(rows: List<CollectionIdentityEvidenceEntity>): Unit = unexpected()
    override suspend fun readCollectionPageCommitRows(): List<CollectionPageCommitEntity> = unexpected()
    override suspend fun insertCollectionPageCommitRows(rows: List<CollectionPageCommitEntity>): Unit = unexpected()
    override suspend fun readCollectionPageFailureRows(): List<CollectionPageFailureEntity> = unexpected()
    override suspend fun insertCollectionPageFailureRows(rows: List<CollectionPageFailureEntity>): Unit = unexpected()
    override suspend fun readCollectionMemberRows(): List<CollectionMemberEntity> = unexpected()
    override suspend fun insertCollectionMemberRows(rows: List<CollectionMemberEntity>): Unit = unexpected()
    override suspend fun readCollectionSnapshotRows(): List<CollectionSnapshotEntity> = unexpected()
    override suspend fun insertCollectionSnapshotRows(rows: List<CollectionSnapshotEntity>): Unit = unexpected()
    override suspend fun readCollectionSnapshotMemberRows(): List<CollectionSnapshotMemberEntity> = unexpected()
    override suspend fun insertCollectionSnapshotMemberRows(rows: List<CollectionSnapshotMemberEntity>): Unit = unexpected()
    override suspend fun readRequestCollectionMemberMetadataRows(): List<RequestCollectionMemberMetadataEntity> = unexpected()
    override suspend fun insertRequestCollectionMemberMetadataRows(rows: List<RequestCollectionMemberMetadataEntity>): Unit = unexpected()
    override suspend fun readAdmissionScopeRows(): List<AdmissionScopeEntity> = unexpected()
    override suspend fun insertAdmissionScopeRows(rows: List<AdmissionScopeEntity>): Unit = unexpected()
    override suspend fun readAdmissionScopeMemberRows(): List<AdmissionScopeMemberEntity> = unexpected()
    override suspend fun insertAdmissionScopeMemberRows(rows: List<AdmissionScopeMemberEntity>): Unit = unexpected()
    override suspend fun readExecutionOccurrenceRows(): List<ExecutionOccurrenceEntity> = unexpected()
    override suspend fun upsertExecutionOccurrenceRows(rows: List<ExecutionOccurrenceEntity>): Unit = unexpected()
    override suspend fun deleteExecutionOccurrenceRows(rows: List<ExecutionOccurrenceEntity>): Unit = unexpected()
    override suspend fun readExecutionQueueRevisionRows(): List<ExecutionQueueRevisionEntity> = unexpected()
    override suspend fun upsertExecutionQueueRevisionRows(rows: List<ExecutionQueueRevisionEntity>): Unit = unexpected()
    override suspend fun deleteExecutionQueueRevisionRows(rows: List<ExecutionQueueRevisionEntity>): Unit = unexpected()
    override suspend fun readExecutionQueueMemberRows(): List<ExecutionQueueMemberEntity> = unexpected()
    override suspend fun upsertExecutionQueueMemberRows(rows: List<ExecutionQueueMemberEntity>): Unit = unexpected()
    override suspend fun deleteExecutionQueueMemberRows(rows: List<ExecutionQueueMemberEntity>): Unit = unexpected()
    override suspend fun readExecutionQueueHeadRows(): List<ExecutionQueueHeadEntity> = unexpected()
    override suspend fun upsertExecutionQueueHeadRows(rows: List<ExecutionQueueHeadEntity>): Unit = unexpected()
    override suspend fun deleteExecutionQueueHeadRows(rows: List<ExecutionQueueHeadEntity>): Unit = unexpected()
    override suspend fun readOccurrenceCheckpointRows(): List<OccurrenceCheckpointEntity> = unexpected()
    override suspend fun upsertOccurrenceCheckpointRows(rows: List<OccurrenceCheckpointEntity>): Unit = unexpected()
    override suspend fun deleteOccurrenceCheckpointRows(rows: List<OccurrenceCheckpointEntity>): Unit = unexpected()
    override suspend fun readSourceRecordingResumeRows(): List<SourceRecordingResumeEntity> = unexpected()
    override suspend fun upsertSourceRecordingResumeRows(rows: List<SourceRecordingResumeEntity>): Unit = unexpected()
    override suspend fun deleteSourceRecordingResumeRows(rows: List<SourceRecordingResumeEntity>): Unit = unexpected()
    override suspend fun readCatalogProgressRows(): List<CatalogProgressEntity> = unexpected()
    override suspend fun upsertCatalogProgressRows(rows: List<CatalogProgressEntity>): Unit = unexpected()
    override suspend fun deleteCatalogProgressRows(rows: List<CatalogProgressEntity>): Unit = unexpected()
    override suspend fun readListeningHistoryRows(): List<ListeningHistoryEntity> = unexpected()
    override suspend fun upsertListeningHistoryRows(rows: List<ListeningHistoryEntity>): Unit = unexpected()
    override suspend fun deleteListeningHistoryRows(rows: List<ListeningHistoryEntity>): Unit = unexpected()
    override suspend fun readFavoriteSubjectRows(): List<FavoriteSubjectEntity> = unexpected()
    override suspend fun upsertFavoriteSubjectRows(rows: List<FavoriteSubjectEntity>): Unit = unexpected()
    override suspend fun deleteFavoriteSubjectRows(rows: List<FavoriteSubjectEntity>): Unit = unexpected()
    override suspend fun readPlaylistMemberRows(): List<PlaylistMemberEntity> = unexpected()
    override suspend fun upsertPlaylistMemberRows(rows: List<PlaylistMemberEntity>): Unit = unexpected()
    override suspend fun deletePlaylistMemberRows(rows: List<PlaylistMemberEntity>): Unit = unexpected()
    override suspend fun readDownloadBatchRows(): List<DownloadBatchEntity> = unexpected()
    override suspend fun upsertDownloadBatchRows(rows: List<DownloadBatchEntity>): Unit = unexpected()
    override suspend fun deleteDownloadBatchRows(rows: List<DownloadBatchEntity>): Unit = unexpected()
    override suspend fun readDownloadBatchMemberRows(): List<DownloadBatchMemberEntity> = unexpected()
    override suspend fun upsertDownloadBatchMemberRows(rows: List<DownloadBatchMemberEntity>): Unit = unexpected()
    override suspend fun deleteDownloadBatchMemberRows(rows: List<DownloadBatchMemberEntity>): Unit = unexpected()
    override suspend fun readDownloadClaimRows(): List<DownloadClaimEntity> = unexpected()
    override suspend fun upsertDownloadClaimRows(rows: List<DownloadClaimEntity>): Unit = unexpected()
    override suspend fun deleteDownloadClaimRows(rows: List<DownloadClaimEntity>): Unit = unexpected()
    override suspend fun readPhysicalDownloadAdoptionRows(): List<PhysicalDownloadAdoptionEntity> = unexpected()
    override suspend fun insertPhysicalDownloadAdoptionRows(rows: List<PhysicalDownloadAdoptionEntity>): Unit = unexpected()
    override suspend fun readMaterializationReceiptRows(): List<MaterializationReceiptEntity> = unexpected()
    override suspend fun insertMaterializationReceiptRows(rows: List<MaterializationReceiptEntity>): Unit = unexpected()
    override suspend fun readAssetUseRows(): List<AssetUseEntity> = unexpected()
    override suspend fun insertAssetUseRows(rows: List<AssetUseEntity>): Unit = unexpected()
    override suspend fun request(id: String): AcquisitionRequestEntity? = unexpected()
    override suspend fun receipt(id: String): FirstResultReceiptEntity? = unexpected()
    override suspend fun binding(id: String): ExecutionBindingEntity? = unexpected()
    override suspend fun collectionRun(id: String): CollectionRunEntity? = unexpected()
    override suspend fun completeClaims(downloadId: String): List<DownloadClaimEntity> = unexpected()
}
