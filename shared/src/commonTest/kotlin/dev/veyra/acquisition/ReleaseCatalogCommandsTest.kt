package dev.veyra.acquisition

import dev.veyra.data.ReleaseCatalogCommand
import dev.veyra.data.ReleaseCatalogEntry
import dev.veyra.data.ReleaseCatalogState
import dev.veyra.data.SavedRelease
import dev.veyra.data.room.*
import dev.veyra.model.*
import dev.veyra.source.release.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Authored typed core metadata and pure graph transforms ONLY. No Room commit, database
 * reopen, metadata service, transfer, network-denied process or native execution is tested.
 * Revision changes below are explicit test inputs, never a fake transaction-owner ACK.
 */
class ReleaseCatalogCommandsTest {
    @Test fun emptyCatalogueProjectsTheExistingLibraryRevisionAndNoDefaultDetail() {
        val graph = graph(revision = 19)
        assertEquals(ReleaseCatalogState(19), graph.releaseCatalogState())
        assertNull(graph.releaseCatalogDetail(mbid(1)))
    }

    @Test fun newRegistrationAtomicallyAddsNormalizedCoreAndLatestWithOriginalFetchProvenance() {
        val before = graph()
        val captured = captured()
        val mutation = before.applyReleaseCatalog(ReleaseCatalogCommand.RegisterObservation(captured, before.revision))
        val proposed = mutation.graph
        val state = proposed.releaseCatalogState()

        assertEquals(mapOf(captured.id to captured), state.observations)
        assertEquals(mapOf(mbid(1) to ReleaseCatalogEntry(mbid(1), captured.id, 123_000)), state.entries)
        assertTrue(state.saved.isEmpty())
        assertEquals(captured.id.value, proposed.rows.releases.observations.single().id)
        assertEquals(TITLE, proposed.rows.releases.observations.single().completeTitle)
        assertEquals(captured.value.coreDigest, proposed.rows.releases.observations.single().coreDigest)
        assertEquals("W/\"authored-a\"", proposed.rows.releases.observations.single().etag)
        assertEquals(2, proposed.rows.releases.tracks.size)
        assertEquals(before.library, proposed.library)
        assertEquals(before.revision, proposed.revision) // The outer owner alone advances revisions.
        assertEquals(ReleaseCatalogState(before.revision), before.releaseCatalogState())
    }

    @Test fun refreshChangesLatestButNotSelectedPinOrCapturedRequest() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000, title = "  Refreshed / الإصدار  ")
        val request = request(first)
        val registered = graph().register(first).pin(first, 123_100)
        val before = registered.copy(rows = registered.rows.withCapturedRequest(request))
        val after = before.register(second)
        val state = after.releaseCatalogState()

        assertEquals(second.id, state.entries.getValue(mbid(1)).latestObservationId)
        assertEquals(SavedRelease(mbid(1), first.id, 123_100), state.saved.getValue(mbid(1)))
        assertEquals(first, state.observations[first.id])
        assertEquals(second, state.observations[second.id])
        assertEquals(mapOf(request.id to request), after.rows.capturedRequests(emptyMap()))
        assertEquals(before.rows.acquisitionRequestRows, after.rows.acquisitionRequestRows)
        assertEquals(before.rows.requestReleaseMetadataRows, after.rows.requestReleaseMetadataRows)
        assertEquals(TITLE, (after.rows.capturedRequests(emptyMap()).getValue(request.id).metadata as ReleaseCapture).completeTitle)
    }

    @Test fun exactOldDuplicateRegistrationIsIdempotentAndCannotRewindLatest() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000)
        val graph = graph().register(first).register(second)
        val mutation = graph.applyReleaseCatalog(ReleaseCatalogCommand.RegisterObservation(first.copy(), graph.revision))
        assertSame(graph, mutation.graph)
        assertEquals(second.id, mutation.graph.releaseCatalogState().entries.getValue(mbid(1)).latestObservationId)
        assertEquals(2, mutation.graph.rows.releases.observations.size)
    }

    @Test fun differentObservationIdsWithEqualDigestAndFetchMetadataRemainDistinct() {
        val first = captured()
        val second = first.copy(id = ReleaseObservationId("same-core-different-observation"))
        val after = graph().register(first).register(second)
        val state = after.releaseCatalogState()
        assertEquals(first.value, second.value)
        assertEquals(mapOf(first.id to first, second.id to second), state.observations)
        assertEquals(4, after.rows.releases.tracks.size)
        assertEquals(second.id, state.entries.getValue(mbid(1)).latestObservationId)
    }

    @Test fun registeringANewIdUsesExplicitCasOrderNotTimestampSorting() {
        val first = captured(fetchedAt = 900)
        val laterAdmissionWithEarlierClock = captured("clock-rollback", fetchedAt = 100)
        val after = graph().register(first).register(laterAdmissionWithEarlierClock)
        assertEquals(ReleaseCatalogEntry(mbid(1), laterAdmissionWithEarlierClock.id, 100),
            after.releaseCatalogState().entries.getValue(mbid(1)))
        assertEquals(900L, after.releaseCatalogState().observations.getValue(first.id).value.fetchedAtEpochMs)
    }

    @Test fun conflictingKnownIdCannotRewriteCoreFetchTimeOrEtag() {
        val original = captured()
        val graph = graph().register(original)
        val before = graph.releaseCatalogState()
        val changed = listOf(
            original.editCore { it.copy(release = it.release.copy(completeTitle = "Changed full title")) },
            original.copy(value = original.value.copy(fetchedAtEpochMs = 123_001)),
            original.copy(value = original.value.copy(etag = "\"changed-etag\"")),
            original.copy(value = original.value.copy(etag = null)),
        )
        changed.forEach { other ->
            assertNotEquals(original, other)
            failure("release_catalog_observation_changed") { graph.register(other) }
            assertEquals(before, graph.releaseCatalogState())
        }
    }

    @Test fun setLatestCanExplicitlyChooseAnOlderStoredObservationWithoutChangingItsFetch() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000)
        val before = graph().register(first).register(second).pin(second, 125_000)
        val after = before.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(1), first.id, before.revision, 126_000)).graph
        val state = after.releaseCatalogState()
        assertEquals(ReleaseCatalogEntry(mbid(1), first.id, 126_000), state.entries.getValue(mbid(1)))
        assertEquals(first, state.observations[first.id])
        assertEquals(second.id, state.saved.getValue(mbid(1)).observationId)
        val repeated = after.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(1), first.id, after.revision, 127_000)).graph
        assertSame(after, repeated)
        assertEquals(126_000L, repeated.releaseCatalogState().entries.getValue(mbid(1)).updatedEpochMs)
    }

    @Test fun latestAndPinRefuseMissingOrDifferentEditionIdsInsteadOfSubstitutingCurrent() {
        val captured = captured()
        val graph = graph().register(captured)
        val missing = ReleaseObservationId("missing-observation")
        failure("release_catalog_observation_missing") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(1), missing, graph.revision, 130_000))
        }
        failure("release_catalog_observation_missing") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.Pin(mbid(1), missing, graph.revision, 130_000))
        }
        failure("release_catalog_edition_mismatch") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(2), captured.id, graph.revision, 130_000))
        }
        failure("release_catalog_edition_mismatch") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.Pin(mbid(2), captured.id, graph.revision, 130_000))
        }
        assertTrue(graph.releaseCatalogState().saved.isEmpty())
    }

    @Test fun pinUsesSelectedObservationNotLatestAndRepeatedPinPreservesOriginalTime() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000)
        val before = graph().register(first).register(second)
        val pinned = before.pin(first, 125_000)
        assertEquals(second.id, pinned.releaseCatalogState().entries.getValue(mbid(1)).latestObservationId)
        assertEquals(SavedRelease(mbid(1), first.id, 125_000), pinned.releaseCatalogState().saved.getValue(mbid(1)))
        val repeated = pinned.pin(first, 126_000)
        assertSame(pinned, repeated)
        assertEquals(125_000L, repeated.releaseCatalogState().saved.getValue(mbid(1)).pinnedEpochMs)
        val repinned = repeated.pin(second, 127_000)
        assertEquals(SavedRelease(mbid(1), second.id, 127_000), repinned.releaseCatalogState().saved.getValue(mbid(1)))
        assertEquals(before.releaseCatalogState().observations, repinned.releaseCatalogState().observations)
    }

    @Test fun unpinRequiresExactCurrentPinAndNeverDeletesObservationsRequestsOrLatest() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000)
        val request = request(first)
        val registered = graph().register(first).register(second).pin(second, 125_000)
        val before = registered.copy(rows = registered.rows.withCapturedRequest(request))
        failure("release_catalog_saved_observation_changed") {
            before.applyReleaseCatalog(ReleaseCatalogCommand.Unpin(mbid(1), first.id, before.revision))
        }
        val after = before.applyReleaseCatalog(ReleaseCatalogCommand.Unpin(mbid(1), second.id, before.revision)).graph
        assertTrue(after.releaseCatalogState().saved.isEmpty())
        assertEquals(before.rows.copy(savedReleaseRows = emptyList()), after.rows)
        assertEquals(mapOf(request.id to request), after.rows.capturedRequests(emptyMap()))
        val repeated = after.applyReleaseCatalog(ReleaseCatalogCommand.Unpin(mbid(1), second.id, after.revision)).graph
        assertSame(after, repeated)
    }

    @Test fun absentPinIsAnIdempotentUnpinWithoutInventingOrLookingUpAnObservation() {
        val before = graph()
        val after = before.applyReleaseCatalog(ReleaseCatalogCommand.Unpin(mbid(1), ReleaseObservationId("not-stored"), before.revision)).graph
        assertSame(before, after)
        assertEquals(ReleaseCatalogState(before.revision), after.releaseCatalogState())
    }

    @Test fun allCommandsRejectStaleGlobalRevisionEvenWhenOtherwiseIdempotent() {
        val captured = captured()
        val graph = graph(revision = 7).register(captured).pin(captured, 123_100)
        val before = graph.releaseCatalogState()
        for (stale in listOf(-1L, 6L, 8L)) {
            val commands = listOf(
                ReleaseCatalogCommand.RegisterObservation(captured, stale),
                ReleaseCatalogCommand.SetLatest(mbid(1), captured.id, stale, 123_200),
                ReleaseCatalogCommand.Pin(mbid(1), captured.id, stale, 123_200),
                ReleaseCatalogCommand.Unpin(mbid(2), ReleaseObservationId("absent-pin"), stale),
                ReleaseCatalogCommand.Unpin(mbid(1), captured.id, stale),
            )
            commands.forEach { command ->
                failure("release_catalog_stale_revision") { graph.applyReleaseCatalog(command) }
                assertEquals(before, graph.releaseCatalogState())
            }
        }
    }

    @Test fun staleRevisionIsCheckedBeforeInspectingOrNormalizingRejectedMetadata() {
        val before = graph()
        val invalid = captured().copy(value = captured().value.copy(requestedReleaseMbid = "invalid", fetchedAtEpochMs = -1))
        failure("release_catalog_stale_revision") {
            before.applyReleaseCatalog(ReleaseCatalogCommand.RegisterObservation(invalid, before.revision - 1))
        }
        assertTrue(before.rows.releases.observations.isEmpty())
    }

    @Test fun resultCallbackProjectsSuppliedActualGraphNotProposedRowsOrPrecommitRevision() {
        val before = graph(revision = 40)
        val captured = captured()
        val mutation = before.applyReleaseCatalog(ReleaseCatalogCommand.RegisterObservation(captured, before.revision))
        val supplied = mutation.graph.withRevision(41)
        assertEquals(supplied.releaseCatalogState(), mutation.result(supplied))
        assertEquals(41L, mutation.result(supplied).libraryRevision)
        // This injected graph is not a commit simulation: it proves the callback does not
        // capture the proposal or claim that a transform alone has persisted an observation.
        assertEquals(before.releaseCatalogState(), mutation.result(before))
        assertTrue(mutation.result(before).observations.isEmpty())
    }

    @Test fun detailReturnsSelectedLatestAndSavedFromExactlyOneGraphRevision() {
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000, etag = "\"authored-b\"")
        val graph = graph(revision = 73).register(first).pin(first, 123_100).register(second)
        val latest = assertNotNull(graph.releaseCatalogDetail(mbid(1)))
        assertEquals(73L, latest.libraryRevision)
        assertEquals(second, latest.selected)
        assertEquals(second, latest.latestObservation)
        assertEquals(first, latest.savedObservation)
        assertEquals(ReleaseCatalogEntry(mbid(1), second.id, 124_000), latest.latestEntry)
        assertEquals(SavedRelease(mbid(1), first.id, 123_100), latest.savedEntry)
        val selected = assertNotNull(graph.releaseCatalogDetail(mbid(1), first.id))
        assertEquals(latest.copy(selected = first), selected)
        assertEquals("W/\"authored-a\"", selected.selected.value.etag)
        assertEquals(123_000L, selected.selected.value.fetchedAtEpochMs)
    }

    @Test fun explicitDetailMissOrEditionMismatchCannotFallBackToLatestOrSaved() {
        val captured = captured()
        val graph = graph().register(captured).pin(captured, 123_100)
        failure("release_catalog_observation_missing") { graph.releaseCatalogDetail(mbid(1), ReleaseObservationId("missing")) }
        failure("release_catalog_edition_mismatch") { graph.releaseCatalogDetail(mbid(2), captured.id) }
        failure("release_catalog_invalid_release_id") { graph.releaseCatalogDetail("not-an-edition") }
        assertNull(graph.releaseCatalogDetail(mbid(2)))
    }

    @Test fun savedOnlyDetailRequiresExplicitIdInsteadOfAnImplicitSavedFallback() {
        val captured = captured()
        val withoutLatest = graph(rows = AcquisitionRows().withCapturedRelease(captured)).pin(captured, 123_100)
        assertNull(withoutLatest.releaseCatalogDetail(mbid(1)))
        val explicit = assertNotNull(withoutLatest.releaseCatalogDetail(mbid(1), captured.id))
        assertEquals(captured, explicit.selected)
        assertEquals(captured, explicit.savedObservation)
        assertNull(explicit.latestEntry)
        assertNull(explicit.latestObservation)
        assertTrue(withoutLatest.releaseCatalogState().entries.isEmpty())
    }

    @Test fun offlineOnlySettingDoesNotPreventLocalMetadataProjectionOrChangeItsAge() {
        val captured = captured()
        val graph = graph(offlineOnly = true).register(captured).pin(captured, 123_100)
        val first = assertNotNull(graph.releaseCatalogDetail(mbid(1), captured.id))
        val second = assertNotNull(graph.releaseCatalogDetail(mbid(1), captured.id))
        assertEquals(first, second)
        assertEquals(captured.value, first.selected.value)
        assertTrue(graph.library.toSnapshot().settings.offlineOnly)
        assertTrue(graph.library.media.isEmpty())
        assertTrue(graph.library.downloads.isEmpty())
        assertTrue(graph.rows.acquisitionRequestRows.isEmpty())
    }

    @Test fun historicalReadDoesNotRehashOrSanitizePreviouslyStoredProvenance() {
        val old = captured().let { it.copy(value = it.value.copy(coreDigest = "retained-opaque-digest", etag = "retained-opaque-etag")) }
        val rows = AcquisitionRows().withCapturedRelease(old).copy(
            releaseCatalogEntryRows = listOf(ReleaseCatalogEntryEntity(mbid(1), old.id.value, 123_000)),
        )
        val graph = graph(rows = rows)
        assertEquals(old, graph.releaseCatalogState().observations[old.id])
        assertEquals(old, assertNotNull(graph.releaseCatalogDetail(mbid(1))).selected)
        // A new registration is a different admission path; this test covers restored reads.
        failure("release_catalog_invalid_etag") { graph.register(old) }
    }

    @Test fun newObservationRejectsInvalidOrChangedEditionWithoutSilentlyRewritingIt() {
        val before = graph()
        val captured = captured()
        failure("release_catalog_invalid_release_id") {
            before.register(captured.copy(value = captured.value.copy(requestedReleaseMbid = "invalid")))
        }
        failure("release_edition_identity_invalid") {
            before.register(captured.editCore { it.copy(release = it.release.copy(releaseMbid = "invalid")) })
        }
        failure("release_edition_changed") {
            before.register(captured.editCore { it.copy(release = it.release.copy(releaseMbid = mbid(2))) })
        }
        assertEquals(ReleaseCatalogState(before.revision), before.releaseCatalogState())
    }

    @Test fun newObservationRejectsMissingControlOversizeAndMalformedUnicodeTitles() {
        val before = graph()
        for (title in listOf("", "   ", "Title\nSuffix", "Title\u007f", "\uD800", "\uDC00", "x".repeat(16_385))) {
            val bad = captured().let { it.copy(value = it.value.copy(metadata = it.value.metadata.copy(
                release = it.value.metadata.release.copy(completeTitle = title),
            ))) }
            failure("acquisition_complete_title_required") { before.register(bad) }
        }
        assertTrue(before.rows.releases.observations.isEmpty())
    }

    @Test fun negativeRegistrationAndPointerTimesAreRejectedIncludingSameIdNoops() {
        val captured = captured()
        val graph = graph().register(captured).pin(captured, 123_100)
        failure("release_catalog_invalid_timestamp") {
            graph.register(captured.copy(id = ReleaseObservationId("negative-time"), value = captured.value.copy(fetchedAtEpochMs = -1)))
        }
        failure("release_catalog_invalid_timestamp") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(1), captured.id, graph.revision, -1))
        }
        failure("release_catalog_invalid_timestamp") {
            graph.applyReleaseCatalog(ReleaseCatalogCommand.Pin(mbid(1), captured.id, graph.revision, -1))
        }
        assertEquals(123_100L, graph.releaseCatalogState().saved.getValue(mbid(1)).pinnedEpochMs)
    }

    @Test fun newEtagMustAlreadyMatchTheProducerSafeBoundaryRatherThanBeSanitized() {
        val before = graph()
        for (etag in listOf("", "opaque", "\"bad\nvalue\"", "\"غير-آمن\"", "W/\"unterminated", "\"" + "x".repeat(511) + "\"")) {
            failure("release_catalog_invalid_etag") { before.register(captured(etag = etag)) }
        }
        for (etag in listOf(null, "\"\"", "W/\"\"", "\"authored-strong\"", "W/\"authored-weak\"")) {
            val supplied = captured(etag = etag)
            assertEquals(etag, before.register(supplied).releaseCatalogState().observations.getValue(supplied.id).value.etag)
        }
        assertTrue(before.rows.releases.observations.isEmpty())
    }

    @Test fun newCoreDigestMustEqualTypedWhitelistHashAndCannotBeInventedOrRepaired() {
        val before = graph()
        val captured = captured()
        val mismatching = if (captured.value.coreDigest == "a".repeat(64)) "b".repeat(64) else "a".repeat(64)
        for (digest in listOf("", "unknown", mismatching)) {
            failure("release_catalog_core_digest_mismatch") { before.register(captured.copy(value = captured.value.copy(coreDigest = digest))) }
        }
        val alteredWithoutNewDigest = captured.copy(value = captured.value.copy(metadata = captured.value.metadata.copy(
            release = captured.value.metadata.release.copy(completeTitle = "Changed complete title"),
        )))
        failure("release_catalog_core_digest_mismatch") { before.register(alteredWithoutNewDigest) }
        assertTrue(before.rows.releases.observations.isEmpty())
    }

    @Test fun overLimitMediaAndTrackCountsFailWithoutTruncatingIntoLatest() {
        val original = captured()
        val medium = original.value.metadata.media.single()
        val tooManyMedia = original.copy(value = original.value.copy(metadata = original.value.metadata.copy(
            media = List(MusicBrainzReleaseParser.MAX_MEDIA + 1) { ordinal -> medium.copy(sourceArrayOrdinal = ordinal, tracks = emptyList()) },
        )))
        val tooManyTracks = original.copy(value = original.value.copy(metadata = original.value.metadata.copy(
            media = listOf(medium.copy(tracks = List(MusicBrainzReleaseParser.MAX_TRACKS + 1) { ordinal -> track(ordinal) })),
        )))
        val before = graph()
        for (oversized in listOf(tooManyMedia, tooManyTracks)) {
            // Count admission precedes hashing: neither fixture carries a recomputed digest.
            failure("release_catalog_observation_limit") { before.register(oversized) }
            assertEquals(ReleaseCatalogState(before.revision), before.releaseCatalogState())
        }
    }

    @Test fun incompleteHolesAndRepeatedRecordingOccurrencesStayMetadataNotResolvedMembership() {
        val hole = track(9).copy(completeTrackTitle = null, problems = listOf(
            ReleaseMetadataProblem(ReleaseProblemCode.MISSING_TITLE, ReleaseMetadataField.TRACK_TITLE, 4, 9),
        ))
        val captured = captured().editCore { core -> core.copy(
            media = listOf(core.media.single().copy(tracks = listOf(track(7), hole, track(2)),
                membershipStatus = ReleaseMembershipStatus.INCOMPLETE)),
            membershipStatus = ReleaseMembershipStatus.INCOMPLETE,
            problems = listOf(ReleaseMetadataProblem(ReleaseProblemCode.TRACK_COUNT_MISMATCH, ReleaseMetadataField.TRACKS, 4)),
        ) }
        val graph = graph().register(captured)
        val selected = assertNotNull(graph.releaseCatalogDetail(mbid(1))).selected
        val tracks = selected.value.metadata.media.single().tracks
        assertEquals(captured, selected)
        assertEquals(listOf(7, 9, 2), tracks.map { it.sourceArrayOrdinal })
        assertEquals(1, tracks.map { it.recordingMbid }.distinct().size)
        assertNull(tracks[1].completeTrackTitle)
        assertEquals("Independent recording title, not a repair", tracks[1].recordingTitle)
        assertEquals(ReleaseMembershipStatus.INCOMPLETE, selected.value.metadata.membershipStatus)
        assertEquals(TITLE, selected.value.completeTitle)
        assertTrue(graph.rows.collectionMemberRows.isEmpty())
        assertTrue(graph.rows.sourceRecordingRows.isEmpty())
        assertTrue(graph.rows.executionBindingRows.isEmpty())
        assertTrue(graph.library.media.isEmpty())
    }

    @Test fun registrationDetachesEveryNestedCallerOwnedRelationBeforeReturningItsGraph() {
        val base = captured()
        val secondaryTypes = mutableListOf("Live", "Remix")
        val credits = base.value.metadata.release.artistCredits.toMutableList()
        val countries = mutableListOf("EG", "GB")
        val events = mutableListOf(ReleaseEventMetadata(6, "2026-11", mbid(6), "Authored area", countries))
        val labels = mutableListOf(ReleaseLabelMetadata(3, mbid(8), "Authored label", " CAT-01 "))
        val problems = mutableListOf(ReleaseMetadataProblem(ReleaseProblemCode.MISSING_VALUE, ReleaseMetadataField.FORMAT))
        val tracks = mutableListOf(track(7).copy(artistCredits = credits, problems = problems))
        val media = mutableListOf(base.value.metadata.media.single().copy(tracks = tracks, problems = problems))
        val supplied = base.editCore { core -> core.copy(
            release = core.release.copy(
                releaseGroup = core.release.releaseGroup!!.copy(secondaryTypes = secondaryTypes),
                artistCredits = credits, releaseEvents = events, labels = labels, problems = problems,
            ),
            media = media, problems = problems,
        ) }
        val expected = supplied.copy(value = ReleaseAcquisitionPolicy.freeze(supplied.value))
        val graph = graph().register(supplied)
        secondaryTypes.clear(); credits.clear(); countries.clear(); events.clear(); labels.clear()
        problems.clear(); tracks.clear(); media.clear()

        val restored = graph.releaseCatalogState().observations.getValue(supplied.id)
        assertEquals(expected, restored)
        assertEquals(listOf("Live", "Remix"), restored.value.metadata.release.releaseGroup!!.secondaryTypes)
        assertEquals(listOf("EG", "GB"), restored.value.metadata.release.releaseEvents.single().countryCodes)
        assertEquals(1, restored.value.metadata.media.single().tracks.size)
        assertEquals(1, restored.value.metadata.media.single().tracks.single().problems.size)
        assertEquals(1, restored.value.metadata.media.single().tracks.single().artistCredits.size)
        assertEquals(1, graph.rows.releases.labels.size)
    }

    @Test fun requestedMbidCaseIsPreservedExactlyAndNeverRekeysCapturedProvenance() {
        val lower = mbid(1)
        val upper = lower.uppercase()
        assertNotEquals(lower, upper)
        val supplied = captured().let { it.copy(value = it.value.copy(requestedReleaseMbid = upper)) }
        val graph = graph().register(supplied)
        assertEquals(setOf(upper), graph.releaseCatalogState().entries.keys)
        assertEquals(upper, assertNotNull(graph.releaseCatalogDetail(upper)).selected.value.requestedReleaseMbid)
        assertEquals(lower, assertNotNull(graph.releaseCatalogDetail(upper)).selected.value.observedReleaseMbid)
        assertNull(graph.releaseCatalogDetail(lower))
        failure("release_catalog_edition_mismatch") { graph.releaseCatalogDetail(lower, supplied.id) }
    }

    @Test fun duplicateCatalogueOrPinKeysFailRatherThanLastWriterWinning() {
        val captured = captured()
        val valid = graph().register(captured).pin(captured, 123_100)
        val corrupt = listOf(
            valid.copy(rows = valid.rows.copy(releaseCatalogEntryRows = valid.rows.releaseCatalogEntryRows + valid.rows.releaseCatalogEntryRows)),
            valid.copy(rows = valid.rows.copy(savedReleaseRows = valid.rows.savedReleaseRows + valid.rows.savedReleaseRows)),
        )
        corrupt.forEach { graph -> assertFailsWith<IllegalArgumentException> { graph.releaseCatalogState() } }
    }

    @Test fun missingMismatchedOrNegativeTimePointersFailDuringTheSameGraphProjection() {
        val captured = captured()
        val base = graph().register(captured).pin(captured, 123_100)
        val entry = base.rows.releaseCatalogEntryRows.single()
        val pin = base.rows.savedReleaseRows.single()
        val corrupt = listOf(
            base.rows.copy(releaseCatalogEntryRows = listOf(entry.copy(latestObservationId = "missing"))),
            base.rows.copy(releaseCatalogEntryRows = listOf(entry.copy(requestedReleaseMbid = mbid(2)))),
            base.rows.copy(releaseCatalogEntryRows = listOf(entry.copy(updatedEpochMs = -1))),
            base.rows.copy(savedReleaseRows = listOf(pin.copy(observationId = "missing"))),
            base.rows.copy(savedReleaseRows = listOf(pin.copy(requestedReleaseMbid = mbid(2)))),
            base.rows.copy(savedReleaseRows = listOf(pin.copy(pinnedEpochMs = -1))),
        )
        corrupt.forEach { rows -> failure("release_catalog_pointer_mismatch") { base.copy(rows = rows).releaseCatalogDetail(mbid(1)) } }
    }

    @Test fun allReleaseCommandsPreserveUnrelatedLibraryPhysicalAndCapturedRequestRows() {
        val media = MediaItem("retained-media", "Authored old listing", MediaKind.MUSIC, SourceRef(SourceProvider.YOUTUBE, "authored-id"))
        val library = AppSnapshot(revision = 17, media = mapOf(media.id to media),
            settings = AppSettings(offlineOnly = true), navigation = mapOf("destination" to "library")).toRows()
        val first = captured()
        val second = captured("observation-b", fetchedAt = 124_000)
        val producer = PhysicalDownloadProducer.Schema2(media.id, null, null)
        val initial = AcquisitionGraph(library, AcquisitionRows(executableSubjectRows = listOf(
            ExecutableSubjectEntity(media.id, "CATALOG_MEDIA", media.id, null),
        ))).withJob(PhysicalDownloadJob("retained-job", producer, status = DownloadStatus.PAUSED,
            bytesDownloaded = 0, updatedEpochMs = 120_000, attemptCount = 1, userPaused = true))
            .withAttempt(PhysicalDownloadAttempt("retired-attempt", "retained-job", producer, 1, 0,
                status = DownloadAttemptStatus.FAILED, startedEpochMs = 119_000, finishedEpochMs = 120_000))
            .register(first)
        val request = request(first)
        val before = initial.copy(rows = initial.rows.withCapturedRequest(request))
        val registered = before.register(second)
        val pinned = registered.pin(first, 125_000)
        val repointed = pinned.applyReleaseCatalog(ReleaseCatalogCommand.SetLatest(mbid(1), first.id, pinned.revision, 126_000)).graph
        val after = repointed.applyReleaseCatalog(ReleaseCatalogCommand.Unpin(mbid(1), first.id, repointed.revision)).graph

        assertEquals(before.library, after.library)
        assertEquals(before.rows, after.rows.copy(releases = before.rows.releases,
            releaseCatalogEntryRows = before.rows.releaseCatalogEntryRows, savedReleaseRows = before.rows.savedReleaseRows))
        assertEquals(mapOf(request.id to request), after.rows.capturedRequests(emptyMap()))
        assertEquals(before.library.downloads.single(), after.library.downloads.single())
        assertEquals(before.library.attempts.single(), after.library.attempts.single())
        assertEquals(0L, after.library.downloads.single().ownershipGeneration)
        assertNull(after.library.downloads.single().executionBindingId)
        assertTrue(after.rows.executionBindingRows.isEmpty())
    }

    @Test fun commandAndProjectionDiagnosticsDoNotExposeCoreIdentityOrEtag() {
        val captured = captured()
        val graph = graph().register(captured).pin(captured, 123_100)
        val state = graph.releaseCatalogState()
        val diagnostics = listOf(
            ReleaseCatalogCommand.RegisterObservation(captured, graph.revision).toString(),
            ReleaseCatalogCommand.SetLatest(mbid(1), captured.id, graph.revision, 123_200).toString(),
            ReleaseCatalogCommand.Pin(mbid(1), captured.id, graph.revision, 123_200).toString(),
            ReleaseCatalogCommand.Unpin(mbid(1), captured.id, graph.revision).toString(),
            state.toString(), state.entries.getValue(mbid(1)).toString(), state.saved.getValue(mbid(1)).toString(),
            assertNotNull(graph.releaseCatalogDetail(mbid(1))).toString(),
        )
        diagnostics.forEach { text ->
            for (privateValue in listOf(TITLE, mbid(1), captured.id.value, captured.value.coreDigest, "authored-a")) {
                assertFalse(text.contains(privateValue))
            }
        }
    }

    private fun graph(revision: Long = 7, rows: AcquisitionRows = AcquisitionRows(), offlineOnly: Boolean = false) =
        AcquisitionGraph(AppSnapshot(revision = revision, settings = AppSettings(offlineOnly = offlineOnly)).toRows(), rows)

    private fun AcquisitionGraph.register(captured: CapturedReleaseObservation): AcquisitionGraph =
        applyReleaseCatalog(ReleaseCatalogCommand.RegisterObservation(captured, revision)).graph

    private fun AcquisitionGraph.pin(captured: CapturedReleaseObservation, time: Long): AcquisitionGraph =
        applyReleaseCatalog(ReleaseCatalogCommand.Pin(captured.value.requestedReleaseMbid, captured.id, revision, time)).graph

    private fun failure(detail: String, action: () -> Unit) {
        assertEquals(AppFailure(FailureCode.INVALID_STATE, detail), assertFailsWith<VeyraException>(block = action).failure)
    }

    private fun request(captured: CapturedReleaseObservation) = AcquisitionRequest(
        AcquisitionRequestId("captured-release-request"), captured.value.completeTitle, ReleaseCapture(captured),
        capturedEpochMs = 123_500, catalogRevision = 7,
    )

    private fun captured(
        id: String = "observation-a",
        fetchedAt: Long = 123_000,
        etag: String? = "W/\"authored-a\"",
        title: String = TITLE,
    ): CapturedReleaseObservation {
        val core = ReleaseCoreMetadata(
            release = ReleaseSummaryMetadata(
                releaseMbid = mbid(1), completeTitle = title,
                releaseGroup = ReleaseGroupMetadata(mbid(2), "Different conceptual group", "EP", emptyList()),
                artistCredits = listOf(ReleaseArtistCredit(3, mbid(8), "  الاسم المعتمد  ", " & ", "Canonical artist")),
                status = "Official", date = "2026-11", country = "EG", releaseEvents = emptyList(), labels = emptyList(),
                barcode = null, disambiguation = "Authored edition", textLanguage = "ara", textScript = "Arab",
                packaging = null, packagingMbid = null, problems = emptyList(),
            ),
            media = listOf(ReleaseMediumObservation(4, null, 1, null, "CD", 2, 0,
                listOf(track(7), track(2)), ReleaseMembershipStatus.OBSERVED_COMPLETE, emptyList())),
            membershipStatus = ReleaseMembershipStatus.OBSERVED_COMPLETE, problems = emptyList(),
        )
        return CapturedReleaseObservation(ReleaseObservationId(id),
            ReleaseObservation(mbid(1), core, fetchedAt, MusicBrainzReleaseParser.digest(core), etag))
    }

    private fun CapturedReleaseObservation.editCore(transform: (ReleaseCoreMetadata) -> ReleaseCoreMetadata): CapturedReleaseObservation {
        val core = transform(value.metadata)
        return copy(value = value.copy(metadata = core, coreDigest = MusicBrainzReleaseParser.digest(core)))
    }

    private fun track(ordinal: Int) = ReleaseTrackObservation(
        sourceArrayOrdinal = ordinal, declaredPosition = 1, printedNumber = "A1", trackMbid = mbid(10 + ordinal),
        completeTrackTitle = "  المسار الكامل / Track $ordinal  ", artistCredits = emptyList(), lengthMs = 120_000,
        recordingMbid = mbid(3), recordingTitle = "Independent recording title, not a repair", problems = emptyList(),
    )

    private fun mbid(value: Int) = "abcdefab-cdef-4abc-8def-${value.toString(16).padStart(12, '0')}"

    private companion object { const val TITLE = "  ڤيرا / Quiet Currents (إصدار)  " }
}
