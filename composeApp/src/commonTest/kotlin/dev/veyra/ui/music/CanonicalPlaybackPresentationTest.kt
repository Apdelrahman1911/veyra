package dev.veyra.ui.music

import dev.veyra.model.AcquisitionEntryKind
import dev.veyra.model.AcquisitionOrigin
import dev.veyra.model.AcquisitionRequest
import dev.veyra.model.AcquisitionRequestId
import dev.veyra.model.AppSnapshot
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.ExecutionBinding
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutionBindingOrigin
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.ExecutionQueue
import dev.veyra.model.FirstResultObservation
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.FirstResultReceiptId
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaCapture
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OccurrenceSelectionState
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.RepeatMode
import dev.veyra.model.ShowCapture
import dev.veyra.model.SourceEntry
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRecording
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.SourceRef
import dev.veyra.playback.CrossfadePhase
import dev.veyra.playback.CrossfadeState
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.PlaybackSelectionDisplay
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.PlaybackTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure projection checks for the canonical queue/player read model. They describe presentation
 * only; the connected Room/controller journey is a separate authored desktop test.
 */
class CanonicalPlaybackPresentationTest {
    private val music = MediaItem("catalog-1", "Authored requested listing", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "authored"), creator = "Listing creator", durationMs = 300_000L)
    private val second = MediaItem("catalog-2", "Second listing", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "authored-2"), creator = "Second creator")
    private val snapshot = AppSnapshot(media = mapOf(music.id to music, second.id to second))

    @Test fun canonicalEmptyNeverFallsBackToStaleLegacyRows() {
        val stale = QueueSnapshot(listOf(QueueEntry("legacy-now", music.id), QueueEntry("legacy-next", second.id)),
            currentEntryId = "legacy-now")
        val state = PlaybackState(
            initialized = true,
            queues = mapOf(QueueContext.MUSIC to stale),
            executionQueues = mapOf(QueueContext.MUSIC to executionQueue(QueueContext.MUSIC)),
        )
        val surface = state.queueSurface(QueueContext.MUSIC, snapshot)
        assertTrue(surface.canonical)
        assertNull(surface.current)
        assertTrue(surface.upcoming.isEmpty(), "Canonical empty is empty even when a legacy queue has rows")
        assertTrue(surface.displayedIds.isEmpty())
        assertFalse(state.hasUpcomingOccurrence(QueueContext.MUSIC, snapshot))
    }

    @Test fun canonicalOrderKeepsDuplicateOccurrencesExactContextsAndActionIdentity() {
        val now = catalogSelection("music-now", music)
        val next = catalogSelection("music-next", second)
        val copy = catalogSelection("music-copy", music)
        val queue = executionQueue(QueueContext.MUSIC,
            displayed = listOf("music-now", "music-next", "music-copy"), current = "music-now")
        val state = PlaybackState(
            initialized = true,
            activeContext = QueueContext.PODCAST,
            viewedContext = QueueContext.MUSIC,
            executionQueues = mapOf(QueueContext.MUSIC to queue,
                QueueContext.PODCAST to executionQueue(QueueContext.PODCAST)),
            queueSelections = listOf(now, next, copy).associateBy { it.occurrenceId },
            currentSelection = now,
        )
        val surface = state.queueSurface(QueueContext.MUSIC, snapshot)
        assertEquals("music-now", surface.current?.entryId)
        assertEquals(listOf("music-next", "music-copy"), surface.upcoming.map { it.entryId },
            "Duplicate recordings remain distinct occurrences in exact displayed order")
        assertEquals(listOf("music-now", "music-next", "music-copy"), surface.displayedIds)
        assertEquals(listOf("catalog-2", "catalog-1"), surface.upcoming.map { it.mediaId })
        assertTrue(surface.upcoming.all { it.occurrenceId != null && it.status == QueueRowStatus.READY })
        assertTrue(state.hasUpcomingOccurrence(QueueContext.MUSIC, snapshot))
        assertFalse(state.hasNextOccurrence(snapshot),
            "Transport availability describes the audible context, not the inspected queue")
        assertTrue(state.copy(activeContext = QueueContext.MUSIC).hasNextOccurrence(snapshot))
        // Inspecting another context never changes the owner's audible selection.
        assertEquals("music-now", state.currentSelection?.occurrenceId?.value)
        assertEquals(QueueContext.PODCAST, state.activeContext)
    }

    @Test fun currentSurfaceKeepsOwnerReceiptAfterLaterCatalogueMetadataChanges() {
        val selection = catalogSelection("music-now", music)
        val newer = music.copy(title = "A later catalogue replacement title")
        val laterSnapshot = AppSnapshot(media = mapOf(newer.id to newer, second.id to second))
        val state = PlaybackState(
            initialized = true,
            executionQueues = mapOf(QueueContext.MUSIC to executionQueue(QueueContext.MUSIC,
                displayed = listOf("music-now"), current = "music-now")),
            queueSelections = mapOf(selection.occurrenceId to selection),
            currentSelection = selection,
            currentItem = newer,
            currentEntryId = "music-now",
        )
        val identity = state.surfaceIdentity(laterSnapshot)!!
        assertEquals("Authored requested listing", identity.title, "Never adopt a newer catalogue title")
        assertEquals("Authored resolved video", identity.resolvedTitle)
        assertEquals("Authored requested listing", identity.requestedTitle)
        assertEquals("receipt-music-now", identity.provenance?.key)
        assertEquals(FirstResultKind.VIDEO, identity.provenance?.resultKind)
    }

    @Test fun incomingCrossfadeUsesItsExactSelectionNotTheLegacyItemOrLatestRow() {
        val incoming = catalogSelection("music-next", second)
        val decoy = second.copy(title = "Queued future or legacy item")
        val crossfade = CrossfadeState(
            phase = CrossfadePhase.OVERLAPPING,
            incomingItem = decoy,
            incomingEntryId = "legacy-entry",
            incomingSelection = incoming,
            incomingExecutionBinding = null,
        )
        val view = crossfade.incomingView()!!
        assertEquals("Authored resolved video", view.title)
        assertEquals(IncomingIdentity.EXECUTED_RESULT, view.identity)
        assertEquals("receipt-music-next", view.provenance?.key)
    }

    @Test fun requestedAndCapturedIncomingIdentitiesAreNeverRelabelledAsExecuted() {
        val pending = pendingSelection("member-b", second, observed = PlaybackSelectionDisplay(
            "Captured member B", subtitle = "B subtitle"))
        val observedView = CrossfadeState(CrossfadePhase.PREPARING, null, "member-b",
            incomingSelection = pending).incomingView()!!
        assertEquals("Captured member B", observedView.title)
        assertEquals(IncomingIdentity.OBSERVED_MEMBER, observedView.identity)
        assertNull(observedView.provenance)

        val requestedOnly = pendingSelection("requested-a", second)
        val requestedView = CrossfadeState(CrossfadePhase.PREPARING, null, "requested-a",
            incomingSelection = requestedOnly).incomingView()!!
        assertEquals("Second listing", requestedView.title)
        assertEquals(IncomingIdentity.REQUESTED, requestedView.identity)
        assertNull(requestedView.provenance)
    }

    @Test fun neutralAndPendingRowsPreserveCapturedIdentityWithoutFabricatingMedia() {
        val neutral = neutralSelection("neutral-now")
        val pending = pendingSelection("pending-now", music, context = QueueContext.PODCAST)
        val queue = executionQueue(QueueContext.PODCAST,
            displayed = listOf("neutral-now", "pending-now"), current = "neutral-now")
        val state = PlaybackState(
            initialized = true,
            activeContext = QueueContext.PODCAST,
            executionQueues = mapOf(QueueContext.PODCAST to queue),
            queueSelections = mapOf(neutral.occurrenceId to neutral, pending.occurrenceId to pending),
            currentSelection = neutral,
        )
        val surface = state.queueSurface(QueueContext.PODCAST, snapshot)
        val current = surface.current!!
        assertTrue(current.neutral)
        assertNull(current.mediaId)
        assertEquals("Authored neutral source show", current.title)
        assertEquals(PlaybackControlsMode.PODCAST, state.surfaceIdentity(snapshot)?.controlsMode)
        assertNull(state.surfaceIdentity(snapshot)?.catalogItem, "A neutral recording never becomes a MediaItem")
        assertEquals("Authored neutral source show", state.surfaceIdentity(snapshot)?.title)
        assertEquals("Authored resolved video", state.surfaceIdentity(snapshot)?.resolvedTitle)

        val pendingRow = surface.upcoming.single()
        assertEquals(QueueRowStatus.PENDING_RESOLUTION, pendingRow.status)
        assertTrue(pendingRow.playable, "A captured pending request is explicitly resolvable by PlayEntry")
        assertEquals("Authored requested listing", pendingRow.title)
        assertEquals(0, state.surfaceIdentity(snapshot)?.provenance?.selectionOrdinal)
    }

    @Test fun unplayableAndMissingSelectionsAreNotOfferedAsPlayableActions() {
        val unplayable = catalogSelection("unplayable", music).copy(
            occurrence = catalogSelection("unplayable", music).occurrence.copy(
                selectionState = OccurrenceSelectionState.UNPLAYABLE, bindingId = null),
            receipt = null, binding = null, target = null)
        val queue = executionQueue(QueueContext.MUSIC,
            displayed = listOf("unplayable", "missing"), current = "unplayable")
        val state = PlaybackState(
            initialized = true,
            executionQueues = mapOf(QueueContext.MUSIC to queue),
            queueSelections = mapOf(unplayable.occurrenceId to unplayable),
        )
        val surface = state.queueSurface(QueueContext.MUSIC, snapshot)
        assertEquals(QueueRowStatus.UNAVAILABLE, surface.current?.status)
        assertFalse(surface.current?.playable ?: true)
        assertEquals(QueueRowStatus.MISSING_SELECTION, surface.upcoming.single().status)
        assertFalse(surface.upcoming.single().playable, "A missing selection cannot borrow an unrelated receipt")
    }

    private fun executionQueue(
        context: QueueContext,
        displayed: List<String> = emptyList(),
        current: String? = null,
    ) = ExecutionQueue(
        context = context,
        editRevision = 7,
        displayedOrder = displayed.map(::ExecutionOccurrenceId),
        originalOrder = displayed.map(::ExecutionOccurrenceId),
        currentOccurrenceId = current?.let(::ExecutionOccurrenceId),
        positionMs = 0,
        speed = 1f,
        shuffle = false,
        repeat = RepeatMode.OFF,
    )

    private fun catalogSelection(id: String, media: MediaItem): PlaybackSelection {
        val occurrenceId = ExecutionOccurrenceId(id)
        val requestId = AcquisitionRequestId("request-$id")
        val receiptId = FirstResultReceiptId("receipt-$id")
        // CatalogMedia requires its subject id to be the genuine catalogue media id.
        val subjectId = ExecutableSubjectId(media.id)
        val bindingId = ExecutionBindingId("binding-$id")
        val recordingId = SourceRecordingId("recording-$id")
        val requested = media.title
        val executed = "Authored resolved video"
        val request = AcquisitionRequest(requestId, requested, MediaCapture(media), 0)
        val receipt = FirstResultReceipt.Observed(receiptId,
            FirstResultObservation(requestId, requested,
                SourceEntry(AcquisitionEntryKind.VIDEO, "video-$id", executed, "https://example.org/video-$id"),
                "https://example.org/video-$id"), 1_000)
        return PlaybackSelection(
            libraryRevision = 3,
            context = QueueContext.MUSIC,
            occurrence = dev.veyra.model.ExecutionOccurrence(occurrenceId, AcquisitionOrigin.TYPED, requestId,
                null, null, subjectId, bindingId, PlaybackControlsMode.MUSIC,
                OccurrenceSelectionState.BOUND, 1_000),
            request = request,
            receipt = receipt,
            binding = ExecutionBinding(bindingId, subjectId, receiptId, recordingId,
                ExecutionBindingOrigin.TYPED),
            target = PlaybackTarget.Catalog(ExecutableSubject.CatalogMedia(subjectId, media.id), media),
            requested = PlaybackSelectionDisplay(requested, media.creator),
            observedMember = null,
            executed = PlaybackSelectionDisplay(executed, publicUrl = "https://example.org/video-$id"),
        )
    }

    private fun pendingSelection(id: String, media: MediaItem,
        observed: PlaybackSelectionDisplay? = null,
        context: QueueContext = QueueContext.MUSIC): PlaybackSelection {
        val mode = if (context == QueueContext.PODCAST) PlaybackControlsMode.PODCAST else PlaybackControlsMode.MUSIC
        val occurrenceId = ExecutionOccurrenceId(id)
        val requestId = AcquisitionRequestId("request-$id")
        val request = AcquisitionRequest(requestId, media.title, MediaCapture(media), 0)
        return PlaybackSelection(
            libraryRevision = 3,
            context = context,
            occurrence = dev.veyra.model.ExecutionOccurrence(occurrenceId, AcquisitionOrigin.TYPED, requestId,
                null, null, null, null, mode,
                OccurrenceSelectionState.PENDING, 1_000),
            request = request,
            receipt = null,
            binding = null,
            target = null,
            requested = PlaybackSelectionDisplay(media.title, media.creator),
            observedMember = observed,
            executed = null,
        )
    }

    private fun neutralSelection(id: String): PlaybackSelection {
        val occurrenceId = ExecutionOccurrenceId(id)
        val requestId = AcquisitionRequestId("request-$id")
        val receiptId = FirstResultReceiptId("receipt-$id")
        val subjectId = ExecutableSubjectId("subject-$id")
        val bindingId = ExecutionBindingId("binding-$id")
        val recordingId = SourceRecordingId("recording-$id")
        val show = PodcastShow("show-$id", "Authored neutral source show", "https://example.org/feed")
        val request = AcquisitionRequest(requestId, show.title, ShowCapture(show), 0)
        val resolved = "Authored resolved video"
        val receipt = FirstResultReceipt.Observed(receiptId,
            FirstResultObservation(requestId, show.title,
                SourceEntry(AcquisitionEntryKind.VIDEO, "video-$id", resolved, "https://example.org/video-$id"),
                "https://example.org/video-$id"), 1_000)
        return PlaybackSelection(
            libraryRevision = 3,
            context = QueueContext.PODCAST,
            occurrence = dev.veyra.model.ExecutionOccurrence(occurrenceId, AcquisitionOrigin.TYPED, requestId,
                null, null, subjectId, bindingId, PlaybackControlsMode.PODCAST,
                OccurrenceSelectionState.BOUND, 1_000),
            request = request,
            receipt = receipt,
            binding = ExecutionBinding(bindingId, subjectId, receiptId, recordingId,
                ExecutionBindingOrigin.TYPED),
            target = PlaybackTarget.NeutralRecording(
                ExecutableSubject.SourceRecordingSubject(subjectId, recordingId),
                SourceRecording(recordingId, "video-$id", "https://example.org/video-$id")),
            requested = PlaybackSelectionDisplay(show.title),
            observedMember = null,
            executed = PlaybackSelectionDisplay(resolved, publicUrl = "https://example.org/video-$id"),
        )
    }
}
