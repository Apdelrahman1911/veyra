package dev.veyra.data.room

import dev.veyra.acquisition.*
import dev.veyra.data.*
import dev.veyra.model.*
import kotlin.test.*

/** Actual pure normalized commands/transition validation. No SQLite, decoder or disk evidence. */
class PlaybackAcquisitionCommandTest {
    private class Rig(
        val requests: List<AcquisitionRequest> = (0..2).map {
            authoredRequest("play-$it", AlbumCapture(Album("album-$it", "Complete parent $it — العنوان الكامل")))
        },
        val context: QueueContext = QueueContext.MUSIC,
    ) {
        val fixture = AcquisitionCommandFixture()
        val occurrences = requests.map { pendingOccurrence(it, mode = PlaybackControlsMode.valueOf(context.name)) }
        val graph get() = fixture.graph
        val view get() = graph.playbackView()
        val queue get() = view.acquisition.queues.getValue(context)
        fun expected() = ExecutionQueueExpectation(context, queue.editRevision, queue.currentOccurrenceId)
        init {
            fixture.project { it.applyAdmitQueue(QueueAcquisitionAdmission(context, 0, null, QueueAdmissionOperation.REPLACE,
                requests, null, occurrences, null)) }
            requests.zip(occurrences).forEach { (request, occurrence) ->
                fixture.accept(request, attachments = listOf(OccurrenceAttachmentExpectation(occurrence.id, request.id,
                    context, queue.editRevision)))
            }
            apply(AcquisitionPlaybackCommand.Activate(expected(), occurrences.first().id, binding(0), QueueContext.MUSIC,
                0, 1f, false, 123_100, null))
        }
        fun binding(index: Int) = requireNotNull(view.acquisition.occurrences.getValue(occurrences[index].id).bindingId)
        fun apply(command: AcquisitionPlaybackCommand) = fixture.project { it.applyPlayback(command) }
        fun admission(index: Int = 0, generation: Long = 1, session: String = "native-session") = ExecutionLoadAdmission(
            binding(index), view.checkpoints[occurrences[index].id], session, generation, 0, null, 123_100)
        fun load(generation: Long = 1, session: String = "native-session") =
            apply(AcquisitionPlaybackCommand.AdmitLoad(expected(), admission(generation = generation, session = session)))
        fun sample(index: Int = 0, sequence: Long = 1, position: Long = 12_000, generation: Long = 1,
            session: String = "native-session", ended: Boolean = false, history: String? = "history-$index") =
            ExecutionCheckpointWrite(context, OccurrenceCheckpoint(occurrences[index].id, binding(index), position,
                100_000, 123_100 + sequence, session, generation, sequence, ended), history, history?.let { 123_100 })
    }

    @Test fun loadAdmissionReservesOnlyAnExactEpochWithoutInventingProgressResumeOrHistory() {
        val rig = Rig(); val before = rig.queue
        rig.load()
        val admitted = rig.view.checkpoints.getValue(rig.occurrences[0].id)
        assertEquals(0L, admitted.sequence)
        assertFalse(admitted.endedNaturally)
        assertEquals(before, rig.queue)
        assertTrue(rig.view.history.isEmpty())
        assertTrue(rig.view.neutralResume.isEmpty())
        assertTrue(rig.view.catalogProgress.isEmpty())
        assertTrue(rig.view.snapshot.media.isEmpty(), "Album Single must remain a neutral recording")
    }

    @Test fun nativeSamplesMustMatchAdmittedBindingSessionGenerationAndMonotoneSequenceAndClock() {
        val rig = Rig(); rig.load()
        val real = rig.sample()
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(real))
        val before = rig.graph
        val invalid = listOf(
            real.copy(checkpoint = real.checkpoint.copy(bindingId = rig.binding(1))),
            real.copy(checkpoint = real.checkpoint.copy(sessionId = "foreign-session")),
            real.copy(checkpoint = real.checkpoint.copy(generation = 2)),
            real.copy(checkpoint = real.checkpoint.copy(sequence = 0)),
            real.copy(checkpoint = real.checkpoint.copy(positionMs = 99_000)),
            real.copy(checkpoint = real.checkpoint.copy(sequence = 2, observedEpochMs = 123_000)),
        )
        invalid.forEach { write ->
            assertFails { rig.apply(AcquisitionPlaybackCommand.Checkpoint(write)) }
            assertSame(before, rig.graph)
        }
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(real))
        assertSame(before, rig.graph, "Identical acknowledged sample is idempotent")
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample(sequence = 2, position = 3_000)))
        assertEquals(3_000L, rig.queue.positionMs, "Confirmed backwards seek is not rejected as nonmonotone time")
        assertEquals(1, rig.view.history.size)
    }

    @Test fun staleQueueOrCheckpointCannotReserveANewEpoch() {
        val rig = Rig(); rig.load()
        val before = rig.graph
        val commands = listOf(
            AcquisitionPlaybackCommand.AdmitLoad(rig.expected().copy(editRevision = rig.queue.editRevision - 1), rig.admission(generation = 2)),
            AcquisitionPlaybackCommand.AdmitLoad(rig.expected().copy(currentOccurrenceId = rig.occurrences[1].id), rig.admission(generation = 2)),
            AcquisitionPlaybackCommand.AdmitLoad(rig.expected(), rig.admission(generation = 2).copy(expectedCheckpoint = null)),
            AcquisitionPlaybackCommand.AdmitLoad(rig.expected(), rig.admission()),
            AcquisitionPlaybackCommand.AdmitLoad(rig.expected(), rig.admission(generation = 2).copy(bindingId = rig.binding(1))),
        )
        commands.forEach { command -> assertFails { rig.apply(command) }; assertSame(before, rig.graph) }
    }

    @Test fun neutralLongFormRealProgressHasNoCatalogRssOrFollowSideEffects() {
        val request = authoredRequest("show-single", ShowCapture(PodcastShow("genuine-show", "Complete show A", "https://example.invalid/feed")))
        val rig = Rig(listOf(request), QueueContext.PODCAST); rig.load()
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample(position = 100_000, ended = true)))
        val occurrence = rig.view.acquisition.occurrences.getValue(rig.occurrences.single().id)
        assertEquals(occurrence.id, rig.view.neutralResume[requireNotNull(occurrence.subjectId) to PlaybackControlsMode.PODCAST])
        assertTrue(rig.view.catalogProgress.isEmpty())
        assertTrue(rig.view.snapshot.media.isEmpty())
        assertTrue(rig.view.snapshot.shows.isEmpty())
        assertTrue(rig.view.history.single().endedNaturally)
        val before = rig.graph
        assertFails { rig.apply(AcquisitionPlaybackCommand.CatalogProgress("genuine-show", true, false, 123_200, null)) }
        assertSame(before, rig.graph)
    }

    @Test fun stageAdmissionKeepsCurrentCursorAndAllOutgoingSamplesUntouched() {
        val rig = Rig(); rig.load()
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample()))
        val before = rig.view
        rig.apply(AcquisitionPlaybackCommand.AdmitStage(rig.expected(), rig.binding(0), "native-session", 1,
            rig.occurrences[1].id, rig.admission(1, 2)))
        assertEquals(before.acquisition.queues, rig.view.acquisition.queues)
        assertEquals(before.checkpoints[rig.occurrences[0].id], rig.view.checkpoints[rig.occurrences[0].id])
        assertEquals(before.history, rig.view.history)
        assertEquals(before.neutralResume, rig.view.neutralResume)
        assertEquals(0L, rig.view.checkpoints.getValue(rig.occurrences[1].id).sequence)
    }

    @Test fun handoverCommitsOutgoingIncomingAndCursorInOneValidatedMutation() {
        val rig = Rig(); rig.load()
        rig.apply(AcquisitionPlaybackCommand.AdmitStage(rig.expected(), rig.binding(0), "native-session", 1,
            rig.occurrences[1].id, rig.admission(1, 2)))
        val before = rig.graph
        val outgoing = rig.sample(position = 98_000)
        val incoming = rig.sample(index = 1, position = 2_000, generation = 2)
        rig.apply(AcquisitionPlaybackCommand.Handover(rig.expected(), outgoing, incoming))
        assertEquals(before.revision + 1, rig.graph.revision)
        assertEquals(rig.occurrences[1].id, rig.queue.currentOccurrenceId)
        assertEquals(2_000L, rig.queue.positionMs)
        assertEquals(outgoing.checkpoint, rig.view.checkpoints[rig.occurrences[0].id])
        assertEquals(incoming.checkpoint, rig.view.checkpoints[rig.occurrences[1].id])
        assertEquals(setOf("history-0", "history-1"), rig.view.history.map { it.id }.toSet())
        assertTrue(rig.view.history.all { it.origin == AcquisitionOrigin.TYPED })
    }

    @Test fun nonNeighbourOrUnadmittedIncomingCannotPartiallyCommitOutgoingHistory() {
        val rig = Rig(); rig.load(); val before = rig.graph
        assertFails { rig.apply(AcquisitionPlaybackCommand.AdmitStage(rig.expected(), rig.binding(0), "native-session", 1,
            rig.occurrences[2].id, rig.admission(2, 2))) }
        assertSame(before, rig.graph)
        assertFails { rig.apply(AcquisitionPlaybackCommand.Handover(rig.expected(), rig.sample(), rig.sample(1, generation = 2))) }
        assertSame(before, rig.graph)
        assertTrue(rig.view.history.isEmpty())
        assertEquals(rig.occurrences[0].id, rig.queue.currentOccurrenceId)
    }

    @Test fun selectingAnotherOccurrenceRejectsLateRealSamplesFromTheOldOccurrence() {
        val rig = Rig(); rig.load()
        rig.apply(AcquisitionPlaybackCommand.Activate(rig.expected(), rig.occurrences[1].id, rig.binding(1), rig.context,
            0, 1f, false, 123_200, rig.sample()))
        val before = rig.graph
        assertFails { rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample(sequence = 2))) }
        assertSame(before, rig.graph)
        assertEquals(rig.occurrences[1].id, rig.queue.currentOccurrenceId)
        assertEquals(12_000L, rig.view.history.single().lastPositionMs)
    }

    @Test fun newSessionAdmissionResetsOnlyItsNativeEpochAndCannotAcceptThePreviousSessionsCallbacks() {
        val rig = Rig(); rig.load()
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample()))
        val history = rig.view.history
        rig.load(generation = 0, session = "reopened-session")
        assertEquals(history, rig.view.history)
        assertEquals(0L, rig.view.checkpoints.getValue(rig.occurrences[0].id).sequence)
        val before = rig.graph
        assertFails { rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample(sequence = 2))) }
        assertSame(before, rig.graph)
        rig.apply(AcquisitionPlaybackCommand.Checkpoint(rig.sample(generation = 0, session = "reopened-session", history = "new-history")))
        assertEquals(2, rig.view.history.size)
    }

    @Test fun podcastRestartPreservesOutgoingRealHistoryAndResetsOnlyTheSelectedCatalogEpisode() {
        val media = (0..2).map { authoredMedia("episode-$it", kind = MediaKind.EPISODE) }
        val rig = Rig(media.mapIndexed { index, item -> authoredRequest("episode-request-$index", MediaCapture(item)) }, QueueContext.PODCAST)
        rig.load()
        rig.apply(AcquisitionPlaybackCommand.CatalogProgress(media[1].id, true, false, 123_100, null))
        rig.apply(AcquisitionPlaybackCommand.CatalogProgress(media[2].id, true, false, 123_100, null))
        val untouched = rig.view.catalogProgress.getValue(media[2].id)
        rig.apply(AcquisitionPlaybackCommand.Activate(rig.expected(), rig.occurrences[1].id, rig.binding(1), QueueContext.PODCAST,
            90_000, 1.25f, true, 123_200, rig.sample(position = 31_000)))
        assertEquals(31_000L, rig.view.catalogProgress.getValue(media[0].id).positionMs)
        assertEquals(31_000L, rig.view.history.single().lastPositionMs)
        assertEquals(0L, rig.view.catalogProgress.getValue(media[1].id).positionMs)
        assertFalse(rig.view.catalogProgress.getValue(media[1].id).played)
        assertEquals(untouched, rig.view.catalogProgress.getValue(media[2].id))
        assertEquals(0L, rig.queue.positionMs)
        assertNull(rig.view.checkpoints[rig.occurrences[1].id], "Restart intent is not native admission or seek acknowledgement")
    }

    @Test fun musicBrainzRequestedMetadataSurvivesRowsWithoutBecomingTheExecutionProvider() {
        val item = authoredMedia().copy(source = SourceRef(SourceProvider.MUSICBRAINZ,
            "edition:medium-1:track-occurrence", "https://musicbrainz.org/release/authored"))
        val request = authoredRequest(metadata = MediaCapture(item))
        val rig = Rig(listOf(request)); rig.load()
        val restored = rig.view.acquisition.requests.getValue(request.id).request
        assertEquals(item.source, assertIs<MediaCapture>(restored.metadata).media.source)
        assertEquals(SourceProvider.MUSICBRAINZ, rig.view.snapshot.media.getValue(item.id).source.provider)
        assertEquals(SourceProvider.YOUTUBE, rig.view.acquisition.recordings.values.single().provider)
        assertEquals(request.completeTitle, assertIs<FirstResultReceipt.Observed>(rig.view.acquisition.receipts.values.single()).observation.queryTitle)
    }

    @Test fun catalogMusicWithPodcastControlsCanRestartWithoutManufacturingEpisodeProgress() {
        val item = authoredMedia("long-form-music", kind = MediaKind.MUSIC)
        val rig = Rig(listOf(authoredRequest("long-form-music-request", MediaCapture(item))), QueueContext.PODCAST)
        rig.load()
        val before = rig.view
        rig.apply(AcquisitionPlaybackCommand.Activate(rig.expected(), rig.occurrences.single().id, rig.binding(0),
            QueueContext.PODCAST, 90_000, 1.25f, true, 123_200, null))
        assertEquals(0L, rig.queue.positionMs)
        assertEquals(1.25f, rig.queue.speed)
        assertEquals(PlaybackControlsMode.PODCAST, rig.view.acquisition.occurrences.getValue(rig.occurrences.single().id).controlsMode)
        assertEquals(MediaKind.MUSIC, rig.view.snapshot.media.getValue(item.id).kind)
        assertTrue(rig.view.catalogProgress.isEmpty())
        assertEquals(before.checkpoints, rig.view.checkpoints, "Restart intent alone is not a native sample or a new admission")
        assertEquals(before.history, rig.view.history)
        assertEquals(before.snapshot.shows, rig.view.snapshot.shows)
    }

    @Test fun newHistoryForAnExplicitBoundSchema2OccurrenceRetainsSchema2OriginAndOldUnknownLineage() {
        val item = authoredMedia("legacy-media")
        val oldBinding = PlaybackBinding("legacy-binding", item.id, "Historical query, not current title", FirstResultKind.VIDEO,
            "ABCDEFGHIJK", "Historical result", "https://www.youtube.com/watch?v=ABCDEFGHIJK", "ABCDEFGHIJK", 100)
        val oldHistory = PlaybackHistoryEntry("old-history", item.id, 100, 900, executionBindingId = oldBinding.id)
        val occurrence = ExecutionOccurrence(ExecutionOccurrenceId("legacy-occurrence"), AcquisitionOrigin.SCHEMA2, null, null, null,
            ExecutableSubjectId(item.id), ExecutionBindingId(oldBinding.id), PlaybackControlsMode.MUSIC, OccurrenceSelectionState.BOUND, null)
        val original = commandGraph(AppSnapshot(media = mapOf(item.id to item), playbackBindings = mapOf(oldBinding.id to oldBinding),
            history = listOf(oldHistory), queues = AppSnapshot().queues + (QueueContext.MUSIC to QueueSnapshot(
                listOf(QueueEntry(occurrence.id.value, item.id)), occurrence.id.value, originalOrder = listOf(occurrence.id.value)))))
        val receipt = FirstResultReceiptEntity(oldBinding.id, AcquisitionOrigin.SCHEMA2, null, item.id, oldBinding.queryTitle,
            null, null, null, null, null, null, oldBinding.firstResultUrl, null, oldBinding.firstResultKind, oldBinding.firstResultId,
            oldBinding.firstResultTitle, oldBinding.selectedVideoId, oldBinding.resolvedEpochMs, oldBinding.selectionOrdinal)
        val recording = SourceRecording(SourceRecordingId("legacy-recording"), "ABCDEFGHIJK", "https://www.youtube.com/watch?v=ABCDEFGHIJK")
        val binding = ExecutionBinding(requireNotNull(occurrence.bindingId), requireNotNull(occurrence.subjectId), FirstResultReceiptId(oldBinding.id),
            recording.id, ExecutionBindingOrigin.SCHEMA2_EXECUTABLE)
        val graph = original.copy(rows = original.rows.copy(firstResultReceiptRows = listOf(receipt),
            sourceRecordingRows = listOf(recording.toEntity()), executionBindingRows = listOf(binding.toEntity()),
            executionOccurrenceRows = listOf(occurrence.toEntity()), listeningHistoryRows = listOf(ListeningHistoryEntity(
                oldHistory.id, item.id, AcquisitionOrigin.SCHEMA2, null, oldBinding.id, 100, 900, false, 0))))
            .withExecutionQueue(ExecutionQueue(QueueContext.MUSIC, 0, listOf(occurrence.id), listOf(occurrence.id), occurrence.id, 0, 1f, false, RepeatMode.OFF))
        val fixture = AcquisitionCommandFixture(graph)
        val expected = ExecutionQueueExpectation(QueueContext.MUSIC, 0, occurrence.id)
        fixture.project { it.applyPlayback(AcquisitionPlaybackCommand.AdmitLoad(expected, ExecutionLoadAdmission(binding.id,
            null, "current-session", 1, 900, null, 123_000))) }
        val checkpoint = OccurrenceCheckpoint(occurrence.id, binding.id, 1_200, 2_000, 123_010, "current-session", 1, 1, false)
        fixture.project { it.applyPlayback(AcquisitionPlaybackCommand.Checkpoint(ExecutionCheckpointWrite(QueueContext.MUSIC,
            checkpoint, "current-history", 123_000))) }
        val view = fixture.graph.playbackView()
        assertEquals(graph.playbackView().history.single(), view.history.first())
        assertEquals(AcquisitionOrigin.SCHEMA2, view.history.last().origin)
        assertEquals(occurrence.id, view.history.last().occurrenceId)
        assertTrue(view.acquisition.requests.isEmpty())
        assertEquals(receipt, fixture.graph.rows.firstResultReceiptRows.single())
        val forged = fixture.graph.copy(rows = fixture.graph.rows.copy(listeningHistoryRows = fixture.graph.rows.listeningHistoryRows.map {
            if (it.id == "current-history") it.copy(origin = AcquisitionOrigin.TYPED) else it
        }))
        assertFails { forged.state() }
    }
}
