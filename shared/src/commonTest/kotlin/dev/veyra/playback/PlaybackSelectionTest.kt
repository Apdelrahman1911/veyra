package dev.veyra.playback

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.model.*
import dev.veyra.source.execution.AcquisitionExecutionContext
import dev.veyra.source.youtube.AcquisitionExecutionFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure authored coherent-view joins, not Room admission, source execution or native evidence. */
class PlaybackSelectionTest {
    @Test fun allSixRequestKindsRetainCompleteCapturesAndOnlyMediaHasCatalogIdentity() {
        for (kind in AcquisitionKind.entries) {
            val fixture = PlaybackSelectionFixtures.single(kind)
            val selected = fixture.selection()
            assertEquals(fixture.execution.request, selected.request)
            assertEquals(AcquisitionExecutionFixtures.TITLE, selected.request?.completeTitle)
            assertEquals(fixture.execution, selected.executionContext)
            assertEquals(fixture.execution.receipt, selected.receipt)
            assertEquals(fixture.execution.binding, selected.binding)
            assertEquals(PlaybackControlsMode.MUSIC, selected.controlsMode)
            assertEquals(QueueContext.MUSIC, selected.context)
            assertEquals(PlaybackSelectionDisplay(AcquisitionExecutionFixtures.RETURNED_TITLE,
                publicUrl = AcquisitionExecutionFixtures.WATCH), selected.executed)
            assertEquals(selected.executed, selected.primaryDisplay)
            if (kind == AcquisitionKind.MEDIA) {
                assertIs<PlaybackTarget.Catalog>(selected.target)
                assertEquals((fixture.execution.request.metadata as MediaCapture).media, selected.catalogMedia)
            } else {
                assertIs<PlaybackTarget.NeutralRecording>(selected.target)
                assertNull(selected.catalogMedia)
                assertNull(selected.item)
            }
        }
    }

    @Test fun catalogTargetAndRequestedDisplayUseCapturedMetadataNotLaterCatalogRows() {
        val original = PlaybackSelectionFixtures.request(AcquisitionKind.MEDIA)
        val media = (original.metadata as MediaCapture).media.copy(creator = "Captured artist A",
            artworkUrl = "https://art.example/requested-a.png", durationMs = 900_000,
            localArtworkPath = "/authored/requested-a.png")
        val request = original.copy(metadata = MediaCapture(media))
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.MEDIA, request = request)
        val later = media.copy(title = "A later unrelated title", creator = "Later artist", durationMs = 1,
            artworkUrl = "https://art.example/later.png", localArtworkPath = "/authored/later.png")
        val view = fixture.view.copy(snapshot = fixture.view.snapshot.copy(media = mapOf(later.id to later)))
        val selected = PlaybackSelection.project(view, fixture.occurrenceId)
        assertEquals(media, selected.catalogMedia)
        assertEquals(PlaybackSelectionDisplay(media.title, media.creator, media.source.canonicalUrl,
            media.artworkUrl, media.localArtworkPath), selected.requested)
        assertEquals(AcquisitionExecutionFixtures.RETURNED_TITLE, selected.primaryDisplay?.title)
        assertNull(selected.primaryDisplay?.subtitle)
        assertNull(selected.primaryDisplay?.artworkUrl)
        assertNull(selected.primaryDisplay?.localArtworkPath)
    }

    @Test fun aShowResolvedToOneRecordingKeepsExplicitPodcastControlsWithoutRssIdentity() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.SHOW, PlaybackControlsMode.PODCAST)
        val selected = fixture.selection()
        assertEquals(QueueContext.PODCAST, selected.context)
        assertEquals(PlaybackControlsMode.PODCAST, selected.controlsMode)
        assertIs<ShowCapture>(selected.request?.metadata)
        assertIs<PlaybackTarget.NeutralRecording>(selected.target)
        assertNull(selected.item)
        assertNull(selected.catalogMedia)
        assertEquals(listOf("unrelated-rss-guid"), (selected.request!!.metadata as ShowCapture).show.episodeIds)
        assertEquals(AcquisitionExecutionFixtures.WATCH, selected.primaryDisplay?.publicUrl)
        assertNotEquals((selected.request!!.metadata as ShowCapture).show.feedUrl, selected.primaryDisplay?.publicUrl)
    }

    @Test fun collectionSelectionSeparatesParentAExactDuplicateBAndExecutedC() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER, PlaybackControlsMode.PODCAST)
        val selected = fixture.selection()
        val lineage = requireNotNull(selected.memberLineage)
        val scope = requireNotNull(selected.scopeLineage)
        assertEquals("Requested collection A", selected.requested?.title)
        assertEquals("Requested parent artist", selected.requested?.subtitle)
        assertEquals(AcquisitionExecutionFixtures.TITLE, selected.observedMember?.title)
        assertEquals(AcquisitionExecutionFixtures.VIDEO_B, lineage.member.entry.id)
        assertEquals(2, lineage.member.reference.ordinal)
        assertEquals(2, lineage.member.entry.ordinal)
        assertEquals(2, scope.member.position, "Admission retains excluded duplicate/hole positions")
        assertEquals(0, fixture.view.acquisition.queues.getValue(selected.context).displayedOrder.indexOf(selected.occurrenceId),
            "Queue position is not the selected source/scope position")
        assertEquals(CollectionSnapshotMemberRef(lineage.snapshotId, 2), scope.member.snapshotMember)
        assertEquals(fixture.execution.request.id, scope.member.requestId)
        assertEquals(AdmissionScopeKind.SELECTED_SUBSET, scope.kind)
        assertTrue(scope.acknowledgesUnobservedSuffix)
        assertEquals(CollectionTraversal.INCOMPLETE, lineage.extent.traversal)
        assertEquals(3, lineage.extent.observedMemberCount)
        assertEquals(1, lineage.extent.nonPlayableMemberCount)
        assertEquals(AcquisitionExecutionFixtures.RETURNED_TITLE, selected.executed?.title)
        assertEquals(AcquisitionExecutionFixtures.WATCH, selected.executed?.publicUrl)
        val members = fixture.view.acquisition.snapshots.getValue(lineage.snapshotId).members
        assertEquals(members[0].entry.id, members[2].entry.id)
        assertFalse(members[1].isVideoCandidate)
        assertEquals(members[2], lineage.member)
        assertNotEquals(members[0].reference, lineage.member.reference)
    }

    @Test fun anUnboundMemberDoesNotPromoteTheRequestsAlreadyCommittedSingle() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER)
        val pending = fixture.occurrence.copy(subjectId = null, bindingId = null,
            selectionState = OccurrenceSelectionState.PENDING)
        val selected = fixture.withOccurrence(pending).selection()
        assertIs<FirstResultReceipt.Observed>(selected.receipt)
        assertNull(selected.binding)
        assertNull(selected.target)
        assertNull(selected.executionContext)
        assertNull(selected.executed)
        assertEquals(selected.observedMember, selected.primaryDisplay)
        assertEquals("Requested collection A", selected.requested?.title)
        assertEquals(2, selected.memberLineage?.member?.reference?.ordinal)
    }

    @Test fun pendingAndOutcomeUnknownRequestsKeepUsefulCaptureWithoutInventingAReceipt() {
        for ((selectionState, jobState) in listOf(
            OccurrenceSelectionState.PENDING to AcquisitionJobState.READY,
            OccurrenceSelectionState.OUTCOME_UNKNOWN to AcquisitionJobState.OUTCOME_UNKNOWN,
        )) {
            val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.RELEASE)
            val request = fixture.execution.request
            val record = AcquisitionRecord(request, jobState, updatedEpochMs = 123_001)
            val state = fixture.view.acquisition.copy(requests = mapOf(request.id to record), receipts = emptyMap(),
                bindings = emptyMap(), subjects = emptyMap(), recordings = emptyMap(),
                occurrences = mapOf(fixture.occurrenceId to fixture.occurrence.copy(subjectId = null,
                    bindingId = null, selectionState = selectionState)))
            val selected = fixture.withState(state).selection()
            assertEquals(request.completeTitle, selected.primaryDisplay?.title)
            assertEquals(selectionState, selected.selectionState)
            assertNull(selected.receipt)
            assertNull(selected.binding)
            assertNull(selected.executionContext)
            assertNull(selected.target)
            assertNull(selected.executed)
        }
    }

    @Test fun unplayableAndCollectionFirstResultsRemainNonExecutable() {
        for (collection in listOf(false, true)) {
            val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.SOURCE_COLLECTION)
            val request = fixture.execution.request
            val identity = CollectionIdentity(AcquisitionEntryKind.PLAYLIST, AcquisitionExecutionFixtures.PLAYLIST,
                "${AcquisitionExecutionFixtures.ORIGIN}/playlist?list=${AcquisitionExecutionFixtures.PLAYLIST}")
            val receipt = fixture.execution.receipt.copy(observation = FirstResultObservation(request.id,
                request.completeTitle, if (collection) SourceEntry(identity.kind, identity.id, "Returned playlist", identity.canonicalUrl)
                else SourceEntry(null, null, null, null, SourceEntryIssue.UNAVAILABLE),
                if (collection) identity.canonicalUrl else null))
            val outcome: AcquisitionOutcome = if (collection) AcquisitionOutcome.Collection(request.id, receipt.id,
                identity, CollectionScope.PLAYLIST_MEMBERS) else AcquisitionOutcome.Unplayable(request.id, receipt.id,
                AcquisitionFailureStage.SELECTION, AcquisitionProblem(AcquisitionProblemCode.FIRST_RESULT_UNAVAILABLE))
            val occurrence = fixture.occurrence.copy(subjectId = null, bindingId = null,
                selectionState = if (collection) OccurrenceSelectionState.NEEDS_COLLECTION_ACTION else OccurrenceSelectionState.UNPLAYABLE)
            val state = fixture.view.acquisition.copy(requests = mapOf(request.id to AcquisitionRecord(request,
                AcquisitionJobState.COMMITTED, outcome = outcome, updatedEpochMs = 123_001)),
                receipts = mapOf(receipt.id to receipt), occurrences = mapOf(occurrence.id to occurrence),
                bindings = emptyMap(), subjects = emptyMap(), recordings = emptyMap())
            val selected = fixture.withState(state).selection()
            assertEquals(receipt, selected.receipt)
            assertEquals(request.completeTitle, selected.primaryDisplay?.title)
            assertNull(selected.executionContext)
            assertNull(selected.target)
            assertNull(selected.executed)
        }
    }

    @Test fun removedHistoricalOccurrencesRemainProjectableWithoutCurrentQueueAdmission() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER)
        val selected = fixture.withState(fixture.view.acquisition.copy(queues = emptyMap())).selection()
        assertEquals(fixture.execution, selected.executionContext)
        assertEquals(fixture.occurrenceId, selected.occurrenceId)
        assertEquals(2, selected.memberLineage?.member?.reference?.ordinal)
        // Deliberately not an assertion that this retained occurrence may play now.
    }

    @Test fun equalRecordingsInDifferentQueueOccurrencesDoNotCollapseIdentity() {
        val fixture = PlaybackSelectionFixtures.single()
        val duplicate = fixture.occurrence.copy(id = ExecutionOccurrenceId("occurrence:duplicate"))
        val oldQueue = fixture.view.acquisition.queues.getValue(QueueContext.MUSIC)
        val state = fixture.view.acquisition.copy(occurrences = mapOf(fixture.occurrenceId to fixture.occurrence,
            duplicate.id to duplicate), queues = mapOf(QueueContext.MUSIC to oldQueue.copy(
            displayedOrder = listOf(duplicate.id, fixture.occurrenceId), originalOrder = listOf(fixture.occurrenceId, duplicate.id),
            currentOccurrenceId = duplicate.id, shuffle = true)))
        val view = LibraryAcquisitionView(fixture.view.snapshot, state)
        val first = PlaybackSelection.project(view, fixture.occurrenceId)
        val second = PlaybackSelection.project(view, duplicate.id)
        assertNotEquals(first.occurrenceId, second.occurrenceId)
        assertEquals(first.binding, second.binding)
        assertEquals(first.executed, second.executed)
        assertEquals(fixture.occurrenceId, first.occurrenceId, "Current duplicate cannot replace an exact lookup")
    }

    @Test fun schema2BoundKeepsHistoricalNullUrlAndUnboundDoesNotBorrowLatestReceipt() {
        for (bound in listOf(true, false)) {
            val (view, id) = PlaybackSelectionFixtures.schema2(bound)
            val selected = PlaybackSelection.project(view, id)
            assertIs<PlaybackTarget.Schema2>(selected.target)
            assertNull(selected.request)
            assertNull(selected.requestId)
            assertNull(selected.executionContext)
            assertNull(selected.memberLineage)
            if (bound) {
                assertIs<FirstResultReceipt.Schema2>(selected.receipt)
                assertEquals("Known old query, not current title", selected.requested?.title)
                assertEquals("Known old first title", selected.executed?.title)
                assertNull(selected.executed?.publicUrl, "Unknown old URL must not be filled from the recording row")
            } else {
                assertNull(selected.receipt)
                assertNull(selected.binding)
                assertNull(selected.requested)
                assertNull(selected.executed)
            }
        }
    }

    @Test fun mutableCapturedMediaAlbumAndShowListsAreDetachedFromTheProjection() {
        val artistIds = mutableListOf("artist:one")
        val credits = mutableListOf(CapturedArtistCredit("artist:one", Artist("artist:one", "Captured name")))
        val mediaBase = PlaybackSelectionFixtures.request(AcquisitionKind.MEDIA)
        val media = (mediaBase.metadata as MediaCapture).media.copy(artistIds = artistIds)
        val mediaSelected = PlaybackSelectionFixtures.single(AcquisitionKind.MEDIA,
            request = mediaBase.copy(metadata = MediaCapture(media, credits))).selection()
        artistIds.clear(); credits.clear()
        val capturedMedia = mediaSelected.request!!.metadata as MediaCapture
        assertEquals(listOf("artist:one"), capturedMedia.media.artistIds)
        assertEquals("Captured name", capturedMedia.artists.single().metadata?.name)
        assertNotSame(media, capturedMedia.media)
        assertEquals(capturedMedia, mediaSelected.executionContext!!.request.metadata)

        val trackIds = mutableListOf("catalog:track")
        val members = mutableListOf(AlbumMemberCapture(0, "catalog:track", null))
        val albumBase = PlaybackSelectionFixtures.request(AcquisitionKind.ALBUM)
        val album = (albumBase.metadata as AlbumCapture).album.copy(trackIds = trackIds)
        val albumSelected = PlaybackSelectionFixtures.single(AcquisitionKind.ALBUM,
            request = albumBase.copy(metadata = AlbumCapture(album, members = members))).selection()
        trackIds.clear(); members.clear()
        val capturedAlbum = albumSelected.request!!.metadata as AlbumCapture
        assertEquals(listOf("catalog:track"), capturedAlbum.album.trackIds)
        assertEquals("catalog:track", capturedAlbum.members.single().mediaId)

        val episodeIds = mutableListOf("rss:old-guid")
        val aliases = mutableSetOf("https://podcast.example/previous.xml")
        val showBase = PlaybackSelectionFixtures.request(AcquisitionKind.SHOW)
        val show = (showBase.metadata as ShowCapture).show.copy(episodeIds = episodeIds, feedAliases = aliases)
        val showSelected = PlaybackSelectionFixtures.single(AcquisitionKind.SHOW,
            request = showBase.copy(metadata = ShowCapture(show))).selection()
        episodeIds.clear(); aliases.clear()
        val capturedShow = (showSelected.request!!.metadata as ShowCapture).show
        assertEquals(listOf("rss:old-guid"), capturedShow.episodeIds)
        assertEquals(setOf("https://podcast.example/previous.xml"), capturedShow.feedAliases)
    }

    @Test fun malformedExactJoinsFailRatherThanUseAnotherQueueReceiptOrScope() {
        val fixture = PlaybackSelectionFixtures.single(AcquisitionKind.COLLECTION_MEMBER)
        val state = fixture.view.acquisition
        val scope = state.scopes.values.single()
        val queue = state.queues.getValue(QueueContext.MUSIC)
        val receipt = fixture.execution.receipt
        val parent = state.snapshots.values.single().parentReceiptId
        val invalidStates = listOf(
            state.copy(receipts = state.receipts - receipt.id),
            state.copy(receipts = state.receipts - parent),
            state.copy(scopes = mapOf(scope.id to scope.copy(kind = AdmissionScopeKind.CATALOG_LEAVES))),
            state.copy(scopes = mapOf(scope.id to scope.copy(members = scope.members.filter { it.position != 2 }))),
            state.copy(queues = mapOf(QueueContext.MUSIC to queue.copy(displayedOrder = listOf(fixture.occurrenceId, fixture.occurrenceId)))),
            state.copy(queues = mapOf(QueueContext.PODCAST to queue.copy(context = QueueContext.PODCAST))),
        )
        for (invalid in invalidStates) {
            val error = assertFailsWith<VeyraException> { fixture.withState(invalid).selection() }
            assertEquals(FailureCode.INVALID_STATE, error.failure.code)
            assertEquals("playback_selection_mismatch", error.failure.detail)
        }
        assertFailsWith<IllegalArgumentException> {
            LibraryAcquisitionView(fixture.view.snapshot.copy(revision = fixture.view.snapshot.revision + 1), state)
        }
    }
}

/** Shared only by authored playback tests; no fake repository or alternate production projection. */
internal object PlaybackSelectionFixtures {
    data class Fixture(val view: LibraryAcquisitionView, val occurrenceId: ExecutionOccurrenceId,
        val execution: AcquisitionExecutionContext) {
        val occurrence get() = view.acquisition.occurrences.getValue(occurrenceId)
        fun selection() = PlaybackSelection.project(view, occurrenceId)
        fun withState(state: AcquisitionRepositoryState) = copy(view = LibraryAcquisitionView(
            view.snapshot.copy(revision = state.libraryRevision), state))
        fun withOccurrence(value: ExecutionOccurrence) = withState(view.acquisition.copy(
            occurrences = view.acquisition.occurrences + (occurrenceId to value)))
    }

    fun request(kind: AcquisitionKind): AcquisitionRequest {
        val original = AcquisitionExecutionFixtures.request(kind)
        // The source-gateway fixture intentionally has unrelated catalogue tracks; projector
        // fixtures must additionally capture each track position, including missing metadata.
        val album = original.metadata as? AlbumCapture ?: return original
        return original.copy(metadata = album.copy(members = album.album.trackIds.mapIndexed { position, id ->
            AlbumMemberCapture(position, id, null)
        }))
    }

    fun single(kind: AcquisitionKind = AcquisitionKind.RELEASE, mode: PlaybackControlsMode = PlaybackControlsMode.MUSIC,
        request: AcquisitionRequest = PlaybackSelectionFixtures.request(kind)): Fixture {
        val execution = AcquisitionExecutionFixtures.context(request)
        val context = if (mode == PlaybackControlsMode.MUSIC) QueueContext.MUSIC else QueueContext.PODCAST
        val capturedMember = request.metadata as? CollectionMemberCapture
        val scopeId = capturedMember?.let { AdmissionScopeId("scope:selected-member") }
        val occurrence = ExecutionOccurrence(ExecutionOccurrenceId("occurrence:one"), AcquisitionOrigin.TYPED,
            request.id, capturedMember?.let { CollectionSnapshotMemberRef(it.snapshotId, it.member.reference.ordinal) },
            scopeId?.let { AdmissionScopeMemberRef(it, requireNotNull(capturedMember).member.reference.ordinal) },
            execution.subject.id, execution.binding.id, mode,
            OccurrenceSelectionState.BOUND, 125_000)
        var state = AcquisitionRepositoryState(41,
            requests = mapOf(request.id to AcquisitionRecord(request, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Single(request.id, execution.receipt.id, execution.binding.id), updatedEpochMs = 124_000)),
            receipts = mapOf(execution.receipt.id to execution.receipt), recordings = mapOf(execution.recording.id to execution.recording),
            subjects = mapOf(execution.subject.id to execution.subject), bindings = mapOf(execution.binding.id to execution.binding),
            occurrences = mapOf(occurrence.id to occurrence), queues = mapOf(context to ExecutionQueue(context, 8,
                listOf(occurrence.id), listOf(occurrence.id), occurrence.id, 0, 1f, false, RepeatMode.OFF)))
        if (capturedMember != null) {
            val member = capturedMember.member
            val reference = member.reference
            val parentMetadata = SourceCollectionCapture(SourceCollectionMetadata(SourceProvider.YOUTUBE, "requested-list",
                "Requested collection A", "https://www.youtube.com/playlist?list=requested-list", AcquisitionEntryKind.PLAYLIST,
                CollectionScope.PLAYLIST_MEMBERS, "Requested parent artist", "https://art.example/requested-a.png"))
            val parent = AcquisitionRequest(reference.parentRequestId, parentMetadata.completeTitle, parentMetadata, 120_000)
            val parentReceipt = FirstResultReceipt.Observed(FirstResultReceiptId("receipt:parent"),
                FirstResultObservation(parent.id, parent.completeTitle, SourceEntry(reference.identity.kind, reference.identity.id,
                    "Observed collection, not requested A", reference.identity.canonicalUrl), reference.identity.canonicalUrl), 121_000)
            val observation = CollectionObservation(reference.observationId, parent.id, reference.identity, reference.scope,
                "Observed collection, not requested A", CollectionTitleOrigin.PLAYLIST_METADATA,
                setOf(CollectionIdentityEvidence.PLAYLIST_METADATA), reference.identity.canonicalUrl)
            val firstDuplicate = member.copy(reference = reference.copy(ordinal = 0), entry = member.entry.copy(ordinal = 0))
            val hole = CollectionMember(reference.copy(ordinal = 1),
                SourceEntry(AcquisitionEntryKind.VIDEO, null, null, null, SourceEntryIssue.UNAVAILABLE, 1), null)
            val snapshot = CollectionSnapshot(capturedMember.snapshotId, observation.id, parent.id, parentReceipt.id,
                observation, 0, listOf(firstDuplicate, hole, member),
                CollectionExtent(CollectionTraversal.INCOMPLETE, 3, 1, CollectionProblem(CollectionProblemCode.MEMBER_LIMIT)), 122_000)
            val scope = AdmissionScope(requireNotNull(scopeId), AdmissionScopeKind.SELECTED_SUBSET, parent.id, snapshot.id, mode,
                listOf(
                    AdmissionScopeMember(0, CollectionSnapshotMemberRef(snapshot.id, 0), null,
                        AdmissionDisposition.EXCLUDED, AdmissionExclusion.UNSELECTED),
                    AdmissionScopeMember(1, CollectionSnapshotMemberRef(snapshot.id, 1), null,
                        AdmissionDisposition.EXCLUDED, AdmissionExclusion.KNOWN_HOLE),
                    AdmissionScopeMember(2, CollectionSnapshotMemberRef(snapshot.id, reference.ordinal), request.id,
                        AdmissionDisposition.INCLUDED, null),
                ), true, 125_000)
            state = state.copy(requests = state.requests + (parent.id to AcquisitionRecord(parent, AcquisitionJobState.COMMITTED,
                outcome = AcquisitionOutcome.Collection(parent.id, parentReceipt.id, reference.identity, reference.scope), updatedEpochMs = 121_000)),
                receipts = state.receipts + (parentReceipt.id to parentReceipt), snapshots = mapOf(snapshot.id to snapshot),
                scopes = mapOf(scope.id to scope))
        }
        val catalog = (request.metadata as? MediaCapture)?.media?.let { mapOf(it.id to it) } ?: emptyMap()
        return Fixture(LibraryAcquisitionView(AppSnapshot(revision = state.libraryRevision, media = catalog), state), occurrence.id, execution)
    }

    fun schema2(bound: Boolean): Pair<LibraryAcquisitionView, ExecutionOccurrenceId> {
        val media = (request(AcquisitionKind.MEDIA).metadata as MediaCapture).media.copy(title = "Current catalogue title")
        val subject = ExecutableSubject.CatalogMedia(ExecutableSubjectId(media.id), media.id)
        val recording = SourceRecording(SourceRecordingId("legacy:C"), AcquisitionExecutionFixtures.VIDEO_C, AcquisitionExecutionFixtures.WATCH)
        val receipt = FirstResultReceipt.Schema2(FirstResultReceiptId("schema2:old"), media.id,
            "Known old query, not current title", FirstResultKind.VIDEO, recording.canonicalId, "Known old first title",
            null, recording.canonicalId, 100_000, 0)
        val latest = receipt.copy(id = FirstResultReceiptId("schema2:latest"), queryTitle = "Latest query must not leak",
            firstResultTitle = "Latest first title", canonicalUrl = recording.canonicalUrl)
        val binding = ExecutionBinding(ExecutionBindingId("schema2:binding"), subject.id, receipt.id, recording.id,
            ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
        val occurrence = ExecutionOccurrence(ExecutionOccurrenceId("schema2:occurrence"), AcquisitionOrigin.SCHEMA2,
            null, null, null, subject.id, binding.id.takeIf { bound }, PlaybackControlsMode.MUSIC,
            if (bound) OccurrenceSelectionState.BOUND else OccurrenceSelectionState.LEGACY_UNBOUND, null)
        val state = AcquisitionRepositoryState(9, receipts = mapOf(receipt.id to receipt, latest.id to latest),
            recordings = mapOf(recording.id to recording), subjects = mapOf(subject.id to subject),
            bindings = mapOf(binding.id to binding), occurrences = mapOf(occurrence.id to occurrence))
        return LibraryAcquisitionView(AppSnapshot(revision = state.libraryRevision, media = mapOf(media.id to media)), state) to occurrence.id
    }
}
