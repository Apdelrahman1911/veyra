package dev.veyra.data.room

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/** Pure normalized graph/command regressions; not Room, source, audio or durability evidence. */
class AcquisitionCommandTest {
    @Test fun captureFreezesFullMetadataAndConflictingChildRelationCannotExtendAnExistingRequest() {
        val episodes = mutableListOf("episode-a")
        val request = authoredRequest(metadata = ShowCapture(PodcastShow("show-a", "  عنوان كامل / Complete title  ",
            "https://example.invalid/authored.xml", episodeIds = episodes)))
        val fixture = AcquisitionCommandFixture()
        val captured = fixture.project { it.applyCapture(CaptureAcquisition(request)) }
        episodes += "episode-b"
        assertEquals(listOf("episode-a"), (captured.request.metadata as ShowCapture).show.episodeIds)
        assertEquals(request.completeTitle, fixture.graph.rows.acquisitionRequestRows.single().completeTitle)
        val before = fixture.graph
        val failure = assertFailsWith<VeyraException> { fixture.project { it.applyCapture(CaptureAcquisition(request)) }; Unit }
        assertEquals("acquisition_capture_identity_changed", failure.failure.detail)
        assertSame(before, fixture.graph)
        val replay = fixture.project { it.applyCapture(CaptureAcquisition(captured.request)) }
        assertEquals(captured, replay)
        assertSame(before, fixture.graph)
    }

    @Test fun captureCasChecksTheActualGraphBeforeAddingAnyRows() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest().copy(catalogRevision = 1)
        assertFailsWith<VeyraException> { fixture.project { it.applyCapture(CaptureAcquisition(request, 1)) }; Unit }
        assertTrue(fixture.graph.state().requests.isEmpty())
        assertEquals(0L, fixture.graph.revision)
    }

    @Test fun unexpiredExactUnsentClaimMayReleaseButExpiredClaimNeverBecomesQueryable() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest()
        val first = fixture.claim(request)
        assertFalse(fixture.project { it.applyReleaseUnsent(first.copy(owner = "foreign"), 123_011) })
        assertTrue(fixture.project { it.applyReleaseUnsent(first, 123_011) })
        val second = fixture.project { it.applyClaim(ClaimAcquisition(request.id, AcquisitionAttemptToken("second"),
            "authored-owner", 123_012, 1_000)) } as AcquisitionClaimResult.Claimed
        assertFalse(fixture.project { it.applyReleaseUnsent(second.lease, second.lease.expiresEpochMs) })
        val expired = fixture.project { it.applyClaim(ClaimAcquisition(request.id, AcquisitionAttemptToken("not-a-retry"),
            "other-owner", second.lease.expiresEpochMs, 1_000)) }
        assertIs<AcquisitionClaimResult.OutcomeUnknown>(expired)
        assertEquals(second.lease, fixture.graph.state().requests.getValue(request.id).lease)
        assertIs<AcquisitionClaimResult.OutcomeUnknown>(fixture.project { it.applyClaim(ClaimAcquisition(request.id,
            AcquisitionAttemptToken("still-not-a-retry"), "other-owner", 125_000, 1_000)) })
    }

    @Test fun lateExactLeaseRetainsOnlyHistoricalReceiptAndCannotCreateAnExecutionBinding() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest()
        val lease = fixture.claim(request)
        val result = fixture.project { it.applyCommit(commandResult(request, lease, now = lease.expiresEpochMs)) }
        assertTrue(assertIs<AcquisitionCommitResult.Stale>(result).historicalReceiptRetained)
        val state = fixture.graph.state()
        assertEquals(AcquisitionJobState.OUTCOME_UNKNOWN, state.requests.getValue(request.id).state)
        assertNull(state.requests.getValue(request.id).outcome)
        assertEquals(1, state.receipts.size)
        assertTrue(state.bindings.isEmpty())
        assertTrue(state.recordings.isEmpty())
    }

    @Test fun foreignOrReleasedLeaseCannotAppendHistoricalEvidence() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest()
        val lease = fixture.claim(request)
        val before = fixture.graph
        val foreign = fixture.project { it.applyCommit(commandResult(request, lease.copy(owner = "foreign"))) }
        assertFalse(assertIs<AcquisitionCommitResult.Stale>(foreign).historicalReceiptRetained)
        assertSame(before, fixture.graph)
        assertTrue(fixture.project { it.applyReleaseUnsent(lease, 123_011) })
        assertFalse(assertIs<AcquisitionCommitResult.Stale>(fixture.project { it.applyCommit(commandResult(request, lease)) }).historicalReceiptRetained)
        assertTrue(fixture.graph.state().receipts.isEmpty())
    }

    @Test fun aggregateSinglesReuseOnlyCanonicalCAndKeepDistinctRequestedReceiptsWithoutCatalogMasquerade() {
        val fixture = AcquisitionCommandFixture()
        val album = authoredRequest("album-request", AlbumCapture(Album("catalog-album", "Complete album A")))
        val show = authoredRequest("show-request", ShowCapture(PodcastShow("catalog-show", "Complete show B", "https://example.invalid/show.xml")))
        fixture.accept(album)
        fixture.accept(show)
        val state = fixture.graph.state()
        assertEquals(2, state.requests.size)
        assertEquals(2, state.receipts.size)
        assertEquals(2, state.bindings.size)
        assertEquals(1, state.recordings.size)
        assertEquals(1, state.subjects.size)
        val subject = assertIs<ExecutableSubject.SourceRecordingSubject>(state.subjects.values.single())
        assertTrue(state.bindings.values.all { it.subjectId == subject.id && it.recordingId == subject.recordingId })
        assertTrue(fixture.graph.snapshot().media.isEmpty())
        assertTrue(fixture.graph.snapshot().shows.isEmpty())
        assertTrue(fixture.graph.snapshot().albums.isEmpty())
        assertEquals(listOf(album.completeTitle, show.completeTitle).toSet(), state.receipts.values
            .filterIsInstance<FirstResultReceipt.Observed>().map { it.observation.queryTitle }.toSet())
    }

    @Test fun mediaSingleRetainsGenuineCatalogSubjectEvenWhenCMatchesANeutralAggregate() {
        val fixture = AcquisitionCommandFixture()
        fixture.accept(authoredRequest("aggregate", AlbumCapture(Album("album", "Album requested"))))
        val media = authoredRequest("catalog-track")
        val outcome = fixture.accept(media).outcome as AcquisitionOutcome.Single
        val state = fixture.graph.state()
        val binding = state.bindings.getValue(outcome.bindingId)
        assertEquals(ExecutableSubject.CatalogMedia(ExecutableSubjectId("authored-media"), "authored-media"), state.subjects[binding.subjectId])
        assertEquals(1, state.recordings.size)
        assertEquals(2, state.subjects.size)
        assertEquals((media.metadata as MediaCapture).media, fixture.graph.snapshot().media.getValue("authored-media"))
        assertTrue(fixture.graph.snapshot().playbackBindings.isEmpty()) // No fabricated schema2 selection.
    }

    @Test fun nonzeroOrdinalOrChangedCompleteTitleCannotBecomeAnAcceptedSingle() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest()
        val lease = fixture.claim(request)
        val single = authoredSingle(request)
        for (bad in listOf(single.copy(receipt = single.receipt.copy(entry = single.receipt.entry.copy(ordinal = 1))),
            single.copy(receipt = single.receipt.copy(queryTitle = request.completeTitle.trim())))) {
            assertFailsWith<IllegalArgumentException> { fixture.project { it.applyCommit(commandResult(request, lease, bad)) }; Unit }
        }
        assertEquals(AcquisitionJobState.CLAIMED, fixture.graph.state().requests.getValue(request.id).state)
        assertTrue(fixture.graph.state().receipts.isEmpty())
    }

    @Test fun aRemovedQueueSlotRejectsBindingAttachmentWhileRetainingAcceptedRequestEvidence() {
        val fixture = AcquisitionCommandFixture()
        val first = authoredRequest("first", AlbumCapture(Album("album-a", "Original request A")))
        val firstOccurrence = pendingOccurrence(first)
        val admitted = fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(QueueContext.MUSIC, 0, null,
            QueueAdmissionOperation.REPLACE, listOf(first), null, listOf(firstOccurrence), null)) }
        val lease = fixture.claim(first)
        val second = authoredRequest("second", AlbumCapture(Album("album-b", "New selection B")))
        fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(QueueContext.MUSIC, 1, null,
            QueueAdmissionOperation.REPLACE, listOf(second), null, listOf(pendingOccurrence(second)), null)) }
        val expected = OccurrenceAttachmentExpectation(firstOccurrence.id, first.id, QueueContext.MUSIC,
            admitted.queues.getValue(QueueContext.MUSIC).editRevision)
        val result = fixture.project { it.applyCommit(commandResult(first, lease, attachments = listOf(expected))) }
        assertEquals(setOf(firstOccurrence.id), assertIs<AcquisitionCommitResult.Committed>(result).rejectedAttachments)
        assertEquals(OccurrenceSelectionState.PENDING, fixture.graph.state().occurrences.getValue(firstOccurrence.id).selectionState)
        assertIs<AcquisitionOutcome.Single>(fixture.graph.state().requests.getValue(first.id).outcome)
        assertEquals(listOf(pendingOccurrence(second).id), fixture.graph.state().queues.getValue(QueueContext.MUSIC).displayedOrder)
        assertTrue(fixture.graph.snapshot().queues.getValue(QueueContext.MUSIC).entries.isEmpty())
    }

    @Test fun neutralPodcastCheckpointDoesNotSetRssCatalogProgressOrPlayedFlags() {
        val fixture = AcquisitionCommandFixture()
        val request = authoredRequest("longform", ShowCapture(PodcastShow("show", "Complete show", "https://example.invalid/feed.xml")))
        val occurrence = pendingOccurrence(request, mode = PlaybackControlsMode.PODCAST)
        fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(QueueContext.PODCAST, 0, null,
            QueueAdmissionOperation.REPLACE, listOf(request), null, listOf(occurrence), null)) }
        val record = fixture.accept(request, attachments = listOf(OccurrenceAttachmentExpectation(occurrence.id, request.id, QueueContext.PODCAST, 1)))
        val binding = (record.outcome as AcquisitionOutcome.Single).bindingId
        val checkpoint = OccurrenceCheckpoint(occurrence.id, binding, 17_000, 18_000, 123_040, "native-session", 1, 1, true)
        fixture.project { AcquisitionMutation(it.withCheckpoint(checkpoint)) { actual -> actual.state() } }
        assertEquals(1, fixture.graph.rows.occurrenceCheckpointRows.size)
        assertEquals(1, fixture.graph.rows.sourceRecordingResumeRows.size)
        assertTrue(fixture.graph.rows.catalogProgressRows.isEmpty())
        assertTrue(fixture.graph.snapshot().progress.isEmpty())
        assertTrue(fixture.graph.snapshot().followedShowIds.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            fixture.project { AcquisitionMutation(it.withCheckpoint(checkpoint.copy(sessionId = "unadmitted-new-session", sequence = 2))) { actual -> actual.state() } }; Unit
        }
    }
}
