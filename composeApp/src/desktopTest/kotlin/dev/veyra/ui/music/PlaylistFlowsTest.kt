package dev.veyra.ui.music

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.LanguagePreference
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.model.PlaylistEntry
import dev.veyra.model.QueueContext
import dev.veyra.model.QueueEntry
import dev.veyra.model.QueueSnapshot
import dev.veyra.model.SourceProvider
import dev.veyra.model.SourceRef
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI-only fixtures: assertions concern explicit actions, not persistence or source execution. */
class PlaylistFlowsTest {
    @get:Rule val ui = createComposeRule()
    private val strings = AppStrings(LanguagePreference.ENGLISH)
    private val track = MediaItem("test-track", "Authored playlist track", MediaKind.MUSIC, SourceRef(SourceProvider.YOUTUBE, "test-track"))
    private val playlist = Playlist("playlist", "Evening — ليلة", listOf(PlaylistEntry("copy-a", track.id), PlaylistEntry("copy-b", track.id)))

    @Test fun playlistCreationRejectsBlankNamesAndPreservesArabic() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { LibraryScreen(AppSnapshot(), LibrarySection.PLAYLISTS, { actions += it }, Modifier.size(900.dp, 680.dp)) } }
        ui.onNodeWithTag("new-playlist").performClick()
        ui.onNodeWithTag("playlist-save").assertIsNotEnabled()
        ui.onNodeWithTag("playlist-name").performTextReplacement("  ليلة هادئة  ")
        ui.onNodeWithTag("playlist-save").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.CreatePlaylist("ليلة هادئة")), actions)
    }

    @Test fun addingAnExistingTrackRequiresAnExplicitDuplicateDecision() {
        val snapshot = AppSnapshot(media = mapOf(track.id to track), playlists = listOf(playlist))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { MusicMediaRow(track, snapshot, { actions += it }, Modifier.size(800.dp, 200.dp)) } }
        ui.onNodeWithContentDescription(strings.format(TextKey.MoreActionsFor, track.title)).performClick()
        ui.onNodeWithText(strings.text(TextKey.AddToPlaylist)).performClick()
        ui.onNodeWithText(playlist.name).performClick()
        ui.onNodeWithText(strings.text(TextKey.DuplicateTrack)).assertIsDisplayed()
        assertTrue(actions.isEmpty())
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.AddToPlaylist(playlist.id, track, allowDuplicate = true)), actions)
    }

    @Test fun removingAPlaylistOccurrenceDoesNotRemoveItsDuplicateOrAudioFile() {
        val snapshot = AppSnapshot(media = mapOf(track.id to track), playlists = listOf(playlist))
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { PlaylistDetailScreen(snapshot, playlist.id, { actions += it }, Modifier.size(900.dp, 680.dp)) } }
        ui.onNodeWithTag("playlist-detail").performScrollToNode(hasTestTag("playlist-entry-copy-b"))
        ui.onNode(hasContentDescription(strings.text(TextKey.RemoveFromPlaylist)) and hasAnyAncestor(hasTestTag("playlist-entry-copy-b"))).performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.RemovePlaylistEntry(playlist.id, "copy-b")), actions)
        assertEquals(2, snapshot.playlists.single().entries.size)
    }

    @Test fun playlistReplacementRequiresConfirmationAndUndoTargetsMusicRatherThanTheViewedQueue() {
        val queue = QueueSnapshot(listOf(QueueEntry("now", track.id), QueueEntry("pending", track.id)), "now")
        val snapshot = AppSnapshot(media = mapOf(track.id to track), playlists = listOf(playlist), queues = mapOf(QueueContext.MUSIC to queue), activeContext = QueueContext.PODCAST)
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { PlaylistDetailScreen(snapshot, playlist.id, { actions += it }, Modifier.size(900.dp, 680.dp), MusicUiOptions(undoableQueueContexts = setOf(QueueContext.MUSIC))) } }
        ui.onNodeWithText(strings.text(TextKey.Play)).performClick()
        assertTrue(actions.isEmpty())
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(MusicUiAction.PlayCollection(listOf(track, track)), actions.single())
        ui.onNodeWithText(strings.text(TextKey.UndoQueueEdit)).performClick()
        assertEquals(MusicUiAction.UndoQueue(QueueContext.MUSIC), actions.last())
    }

    @Test fun deletingAPlaylistIsAnExplicitReferenceOnlyAction() {
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { LibraryScreen(AppSnapshot(playlists = listOf(playlist)), LibrarySection.PLAYLISTS, { actions += it }, Modifier.size(900.dp, 680.dp)) } }
        ui.onNodeWithContentDescription(strings.text(TextKey.DeletePlaylist)).performClick()
        ui.onNodeWithText(strings.format(TextKey.ConfirmDeletePlaylist, playlist.name)).assertIsDisplayed()
        assertTrue(actions.isEmpty())
        ui.onNodeWithTag("confirm-action").performClick()
        assertEquals(listOf<MusicUiAction>(MusicUiAction.DeletePlaylist(playlist.id)), actions)
    }

    @Test fun pointerUsersCanRunAnExplicitSearchWithoutAnImeOrKeyboardShortcut() {
        var state by mutableStateOf(SearchUiState())
        val actions = mutableListOf<MusicUiAction>()
        ui.setContent { VeyraTheme(false, LanguagePreference.ENGLISH) { SearchScreen(AppSnapshot(), state, {
            actions += it
            if (it is MusicUiAction.SearchChanged) state = state.copy(query = it.query, scope = it.scope, kind = it.kind, provider = it.provider)
        }, Modifier.size(900.dp, 680.dp)) } }
        ui.onNodeWithTag("run-search").assertIsNotEnabled()
        ui.onNodeWithTag("search-query").performTextReplacement("كامل العنوان — complete title")
        ui.onNodeWithTag("run-search").performClick()
        assertEquals("كامل العنوان — complete title", state.query)
        assertEquals(MusicUiAction.RunSearch, actions.last())
    }
}
