package dev.veyra.ui.podcasts

import dev.veyra.data.AcquisitionRepositoryState
import dev.veyra.data.LibraryAcquisitionView
import dev.veyra.data.PlaybackAcquisitionView
import dev.veyra.model.AcquisitionOrigin
import dev.veyra.model.AppSnapshot
import dev.veyra.model.AppSettings
import dev.veyra.model.ExecutableSubject
import dev.veyra.model.ExecutableSubjectId
import dev.veyra.model.ExecutionBinding
import dev.veyra.model.ExecutionBindingId
import dev.veyra.model.ExecutionBindingOrigin
import dev.veyra.model.ExecutionOccurrence
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.ExecutionQueue
import dev.veyra.model.FirstResultReceiptId
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.MediaProgress
import dev.veyra.model.OccurrenceSelectionState
import dev.veyra.model.FirstResultKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRecording
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.SourceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure canonical podcast-progress projection; no UI, Room, source or native work. */
class PodcastProgressProjectionTest {
    private val show = PodcastShow("show", "Authored show", "https://example.org/feed",
        episodeIds = listOf("episode-a", "episode-b", "episode-c"))
    private val a = episode("episode-a", "Episode A")
    private val b = episode("episode-b", "Episode B")
    private val c = episode("episode-c", "Episode C")
    private val snapshot = AppSnapshot(revision = 1, media = listOf(a, b, c).associateBy { it.id },
        shows = mapOf(show.id to show))

    private fun episode(id: String, title: String) = MediaItem(id, title, MediaKind.EPISODE,
        SourceRef(SourceProvider.RSS, id), showId = show.id, durationMs = 180_000L)

    private fun recording(id: String, video: String) = SourceRecording(SourceRecordingId(id), video,
        "https://www.youtube.com/watch?v=$video")

    private fun binding(id: String, subject: ExecutableSubject, receipt: String, recording: SourceRecording) =
        ExecutionBinding(ExecutionBindingId(id), subject.id, FirstResultReceiptId(receipt), recording.id,
            ExecutionBindingOrigin.TYPED)

    private fun progress(mediaId: String, binding: ExecutionBinding, positionMs: Long = 45_000L,
        played: Boolean = false) = MediaProgress(positionMs = positionMs, durationMs = 180_000L,
        played = played, updatedEpochMs = 100, executionBindingId = binding.id.value)

    private data class Fixture(val view: PlaybackAcquisitionView, val bindingA: ExecutionBinding,
        val bindingB: ExecutionBinding, val bindingA2: ExecutionBinding)

    private fun fixture(currentBinding: String = "binding-a",
        playedA: Boolean = false, canonicalA: Boolean = true, canonicalB: Boolean = true,
        legacyA: Boolean = false): Fixture {
        val subjectA = ExecutableSubject.CatalogMedia(ExecutableSubjectId(a.id), a.id)
        val subjectB = ExecutableSubject.CatalogMedia(ExecutableSubjectId(b.id), b.id)
        val recA = recording("rec-a", "aaaaaaaaaaa")
        val recB = recording("rec-b", "bbbbbbbbbbb")
        val bindingA = binding("binding-a", subjectA, "receipt-a", recA)
        val bindingA2 = binding("binding-a2", subjectA, "receipt-a2", recB)
        val bindingB = binding("binding-b", subjectB, "receipt-b", recB)
        val occurrenceA = occurrence("occurrence-a", subjectA, bindingA, QueueContext.PODCAST)
        val occurrenceA2 = occurrence("occurrence-a2", subjectA, bindingA2, QueueContext.PODCAST)
        val occurrenceB = occurrence("occurrence-b", subjectB, bindingB, QueueContext.PODCAST)
        val currentOccurrence = when (currentBinding) {
            "binding-a2" -> occurrenceA2
            "binding-b" -> occurrenceB
            else -> occurrenceA
        }
        val queue = ExecutionQueue(QueueContext.PODCAST, 1, listOf(currentOccurrence.id), listOf(currentOccurrence.id),
            currentOccurrence.id, 0, 1f, false, RepeatMode.OFF)
        val state = AcquisitionRepositoryState(libraryRevision = 1,
            subjects = mapOf(subjectA.id to subjectA, subjectB.id to subjectB),
            bindings = mapOf(bindingA.id to bindingA, bindingA2.id to bindingA2, bindingB.id to bindingB),
            recordings = mapOf(recA.id to recA, recB.id to recB),
            occurrences = mapOf(occurrenceA.id to occurrenceA, occurrenceA2.id to occurrenceA2, occurrenceB.id to occurrenceB),
            queues = mapOf(QueueContext.PODCAST to queue))
        val catalog = buildMap {
            if (canonicalA) put(a.id, progress(a.id, bindingA, played = playedA))
            if (canonicalB) put(b.id, progress(b.id, bindingB))
        }
        val legacy = if (legacyA) mapOf(a.id to MediaProgress(45_000L, 180_000L, false, 100,
            executionBindingId = bindingA.id.value)) else emptyMap()
        val legacyBindings = if (legacyA) mapOf(bindingA.id.value to PlaybackBinding(bindingA.id.value, a.id,
            "Authored query", FirstResultKind.VIDEO, "aaaaaaaaaaa", "Authored recording",
            selectedVideoId = "aaaaaaaaaaa", selectionOrdinal = 0)) else emptyMap()
        val baseSnapshot = snapshot.copy(progress = legacy, playbackBindings = legacyBindings)
        return Fixture(PlaybackAcquisitionView(LibraryAcquisitionView(baseSnapshot.copy(revision = 1), state),
            emptyMap(), emptyMap(), emptyList(), catalog), bindingA, bindingB, bindingA2)
    }

    private fun occurrence(id: String, subject: ExecutableSubject, binding: ExecutionBinding,
        context: QueueContext) = ExecutionOccurrence(ExecutionOccurrenceId(id), AcquisitionOrigin.TYPED,
        requestId = dev.veyra.model.AcquisitionRequestId("request-$id"), snapshotMember = null, scopeMember = null,
        subjectId = subject.id, bindingId = binding.id, controlsMode = PlaybackControlsMode.PODCAST,
        selectionState = OccurrenceSelectionState.BOUND, admittedEpochMs = 1)

    @Test
    fun matchedCanonicalProgressProvidesResumeAndListMembership() {
        val fixture = fixture()
        val view = podcastProgressView(fixture.view)!!
        val entry = view.forEpisode(a.id)!!
        assertTrue(entry.bindingValid)
        assertEquals("aaaaaaaaaaa", entry.recordingId)
        assertEquals(PodcastProgressRecording.MATCHED, view.recordingState(a.id))
        assertEquals(PodcastProgressRecording.MATCHED, view.recordingState(a.id))

        val row = fixture.view.snapshot.podcastEpisodeUiState(a, progressView = view)
        assertFalse(row.played)
        assertTrue(row.inProgress)
        assertTrue(row.canResume)
        assertEquals(45_000L, row.historicalPositionMs)
        assertEquals(45_000L, row.positionMs)
        assertEquals(180_000L, row.progressDurationMs)

        val podcasts = PodcastsUiState(snapshot = fixture.view.snapshot, progressView = view)
        assertEquals(listOf(a.id, b.id), podcasts.continueEpisodes().map { it.id })
        assertTrue(podcasts.newEpisodes().none { it.id == a.id })
        val showState = PodcastShowUiState(show.id, fixture.view.snapshot, progressView = view,
            options = PodcastListOptions(filter = PodcastEpisodeFilter.IN_PROGRESS))
        assertEquals(listOf(a.id, b.id), showState.visibleEpisodes().map { it.id })
        assertEquals(emptyList(), showState.copy(options = PodcastListOptions(filter = PodcastEpisodeFilter.PLAYED))
            .visibleEpisodes())
    }

    @Test
    fun aDifferentCurrentRecordingIsExplicitlyChangedAndNeverResumesTheOtherTimeline() {
        val fixture = fixture(currentBinding = "binding-a2")
        val view = podcastProgressView(fixture.view)!!
        assertEquals(PodcastProgressRecording.CHANGED, view.recordingState(a.id))
        val row = fixture.view.snapshot.podcastEpisodeUiState(a, progressView = view)
        assertTrue(row.inProgress)
        assertFalse(row.canResume)
        assertEquals(45_000L, row.historicalPositionMs)
        assertEquals(0L, row.positionMs)
        assertNull(row.progressDurationMs)
    }

    @Test
    fun playedIsIndependentOfResumeAndMissingCanonicalBindingIsStale() {
        val fixture = fixture(playedA = true, canonicalA = true)
        val view = podcastProgressView(fixture.view)!!
        val row = fixture.view.snapshot.podcastEpisodeUiState(a, progressView = view)
        assertTrue(row.played)
        assertFalse(row.inProgress)
        assertFalse(row.canResume)
        assertEquals(PodcastProgressRecording.MATCHED, row.progressRecording)

        val missingBinding = fixture.view.copy(catalogProgress = mapOf(a.id to MediaProgress(45_000L,
            180_000L, played = false, updatedEpochMs = 100, executionBindingId = "missing-binding")))
        val staleView = podcastProgressView(missingBinding)!!
        val stale = missingBinding.snapshot.podcastEpisodeUiState(a, progressView = staleView)
        assertFalse(stale.played)
        assertEquals(PodcastProgressRecording.UNVERIFIED, stale.progressRecording)
        assertFalse(stale.canResume)
        assertNull(stale.progressDurationMs)
    }

    @Test
    fun legacySnapshotProgressRemainsACompatibleFallbackWhenNoCanonicalRowExists() {
        val fixture = fixture(canonicalA = false, legacyA = true)
        val view = podcastProgressView(fixture.view)!!
        assertEquals(PodcastProgressSource.LEGACY, view.forEpisode(a.id)?.source)
        assertEquals("aaaaaaaaaaa", view.forEpisode(a.id)?.recordingId)
        val legacyRow = fixture.view.snapshot.podcastEpisodeUiState(a)
        assertEquals(45_000L, legacyRow.historicalPositionMs)
        assertTrue(podcastProgressView(null) == null)
    }

    @Test
    fun playedAndInProgressFiltersStayDistinctOnTheExistingShowState() {
        val fixture = fixture()
        val view = podcastProgressView(fixture.view)!!
        val all = PodcastShowUiState(show.id, fixture.view.snapshot, progressView = view)
        assertEquals(setOf(a.id, b.id, c.id), all.visibleEpisodes().map { it.id }.toSet())
        assertEquals(listOf(a.id, b.id, c.id), all.copy(options = PodcastListOptions(filter = PodcastEpisodeFilter.UNPLAYED))
            .visibleEpisodes().map { it.id })
        assertEquals(listOf(a.id, b.id), all.copy(options = PodcastListOptions(filter = PodcastEpisodeFilter.IN_PROGRESS))
            .visibleEpisodes().map { it.id })
        val playedCanonical = fixture.view.copy(catalogProgress = fixture.view.catalogProgress +
            (a.id to fixture.view.catalogProgress.getValue(a.id).copy(played = true)))
        val playedView = podcastProgressView(playedCanonical)!!
        val playedState = all.copy(progressView = playedView, options = PodcastListOptions(filter = PodcastEpisodeFilter.PLAYED))
        assertEquals(listOf(a.id), playedState.visibleEpisodes().map { it.id })
        val newState = PodcastsUiState(snapshot = fixture.view.snapshot, progressView = playedView)
        assertFalse(newState.newEpisodes().any { it.id == a.id })
    }
}
