package dev.veyra.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import dev.veyra.model.ExecutionOccurrence
import dev.veyra.model.ExecutionOccurrenceId
import dev.veyra.model.ExecutionQueue
import dev.veyra.model.FirstResultObservation
import dev.veyra.model.FirstResultReceipt
import dev.veyra.model.FirstResultReceiptId
import dev.veyra.model.MediaCapture
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.OccurrenceSelectionState
import dev.veyra.model.PlaybackControlsMode
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceEntry
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRecordingId
import dev.veyra.model.SourceRef
import dev.veyra.playback.CrossfadePhase
import dev.veyra.playback.CrossfadeState
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackSelection
import dev.veyra.playback.PlaybackSelectionDisplay
import dev.veyra.playback.PlaybackState
import dev.veyra.playback.PlaybackTarget
import dev.veyra.model.LanguagePreference
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import kotlin.test.Test
import org.junit.Rule

/**
 * Authored Compose-surface check: both the current audible occurrence and the silent incoming
 * deck render their own immutable typed receipts. Not native/audio/live-source evidence.
 */
@OptIn(ExperimentalTestApi::class)
class TypedPlaybackSurfacesTest {
    @get:Rule val ui = createComposeRule()
    private val strings = AppStrings(LanguagePreference.ENGLISH)
    private val currentMedia = MediaItem("current-media", "Current listing A", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "authored-current"), creator = "Current creator")
    private val incomingMedia = MediaItem("incoming-media", "Incoming listing B", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "authored-incoming"), creator = "Incoming creator")
    private val decoy = MediaItem("incoming-media", "Legacy decoy item — must not be read", MediaKind.MUSIC,
        SourceRef(SourceProvider.COMMONS, "authored-decoy"))

    @Test fun currentAndIncomingRenderTheirOwnExactTypedReceipts() {
        val current = selection("now", currentMedia, "Current executed C")
        val incoming = selection("next", incomingMedia, "Incoming executed C2")
        val queue = ExecutionQueue(QueueContext.MUSIC, 4L,
            listOf(current.occurrenceId, incoming.occurrenceId),
            listOf(current.occurrenceId, incoming.occurrenceId),
            current.occurrenceId, 0L, 1f, false, RepeatMode.OFF)
        val playback = PlaybackState(
            initialized = true,
            activeContext = QueueContext.MUSIC,
            viewedContext = QueueContext.MUSIC,
            currentItem = currentMedia,
            currentEntryId = "now",
            phase = PlaybackPhase.PLAYING,
            desiredPlaying = true,
            isPlaying = true,
            durationMs = 300_000L,
            seekable = true,
            generation = 9L,
            executionQueues = mapOf(QueueContext.MUSIC to queue),
            queueSelections = mapOf(current.occurrenceId to current, incoming.occurrenceId to incoming),
            currentSelection = current,
            crossfade = CrossfadeState(CrossfadePhase.PREPARING, decoy, "legacy-entry",
                incomingSelection = incoming),
        )
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                VeyraTheme(false, LanguagePreference.ENGLISH) {
                    Box(Modifier.size(1_000.dp, 2_000.dp)) {
                        FullPlayer(AppSnapshot(media = mapOf(currentMedia.id to currentMedia,
                            incomingMedia.id to incomingMedia)), playback, {})
                    }
                }
            }
        }
        ui.onNodeWithTag("player-title").assertTextEquals(currentMedia.title)
        ui.onNode(hasText("Current executed C") and hasAnyAncestor(hasTestTag("playback-provenance")),
            useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithTag("player-crossfade").performScrollTo().assertIsDisplayed()
        // The incoming disclosure comes from incomingSelection's executed receipt, never the
        // legacy incomingItem/decoy row.
        ui.onNodeWithText(strings.format(TextKey.ResolvedTitleValue, "Incoming executed C2"))
            .assertIsDisplayed()
        ui.onNode(hasText("Incoming executed C2") and hasAnyAncestor(hasTestTag("playback-provenance")),
            useUnmergedTree = true).assertIsDisplayed()
        ui.onNodeWithText(decoy.title).assertDoesNotExist()
    }

    @Test fun canonicalEmptyQueueDoesNotRenderStaleLegacyRows() {
        val stale = QueueSnapshot(
            listOf(QueueEntry("legacy-now", currentMedia.id), QueueEntry("legacy-next", incomingMedia.id)),
            currentEntryId = "legacy-now",
        )
        val snapshot = AppSnapshot(media = mapOf(currentMedia.id to currentMedia, incomingMedia.id to incomingMedia),
            queues = mapOf(QueueContext.MUSIC to stale))
        val empty = ExecutionQueue(QueueContext.MUSIC, 9L, emptyList(), emptyList(), null,
            0L, 1f, false, RepeatMode.OFF)
        val playback = PlaybackState(initialized = true, activeContext = QueueContext.MUSIC,
            viewedContext = QueueContext.MUSIC, queues = mapOf(QueueContext.MUSIC to stale),
            currentItem = currentMedia, currentEntryId = "legacy-now",
            executionQueues = mapOf(QueueContext.MUSIC to empty))
        ui.setContent {
            VeyraTheme(false, LanguagePreference.ENGLISH) {
                QueuePanel(snapshot, playback, QueueContext.MUSIC, {}, Modifier.size(900.dp, 700.dp))
            }
        }
        ui.onNodeWithTag("queue-entry-legacy-now").assertDoesNotExist()
        ui.onNodeWithTag("queue-play-legacy-now").assertDoesNotExist()
        ui.onNodeWithText(strings.text(TextKey.QueueEmpty)).assertIsDisplayed()
    }

    private fun selection(id: String, media: MediaItem, executedTitle: String): PlaybackSelection {
        val occurrenceId = ExecutionOccurrenceId(id)
        val requestId = AcquisitionRequestId("request-$id")
        val receiptId = FirstResultReceiptId("receipt-$id")
        val subjectId = ExecutableSubjectId(media.id)
        val bindingId = ExecutionBindingId("binding-$id")
        val recordingId = SourceRecordingId("recording-$id")
        val request = AcquisitionRequest(requestId, media.title, MediaCapture(media), 0)
        val receipt = FirstResultReceipt.Observed(receiptId,
            FirstResultObservation(requestId, media.title,
                SourceEntry(AcquisitionEntryKind.VIDEO, "video-$id", executedTitle,
                    "https://example.org/video-$id"), "https://example.org/video-$id"), 1_000)
        return PlaybackSelection(
            libraryRevision = 5,
            context = QueueContext.MUSIC,
            occurrence = ExecutionOccurrence(occurrenceId, AcquisitionOrigin.TYPED, requestId,
                null, null, subjectId, bindingId, PlaybackControlsMode.MUSIC,
                OccurrenceSelectionState.BOUND, 1_000),
            request = request,
            receipt = receipt,
            binding = ExecutionBinding(bindingId, subjectId, receiptId, recordingId,
                ExecutionBindingOrigin.TYPED),
            target = PlaybackTarget.Catalog(ExecutableSubject.CatalogMedia(subjectId, media.id), media),
            requested = PlaybackSelectionDisplay(media.title),
            observedMember = null,
            executed = PlaybackSelectionDisplay(executedTitle, publicUrl = "https://example.org/video-$id"),
        )
    }
}
