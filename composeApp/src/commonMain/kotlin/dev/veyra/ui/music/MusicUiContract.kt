package dev.veyra.ui.music

import dev.veyra.model.AppFailure
import dev.veyra.model.MediaItem
import dev.veyra.model.PodcastShow
import dev.veyra.model.QueueContext
import dev.veyra.model.RepeatMode
import dev.veyra.model.SourceProvider
import dev.veyra.source.MediaCollection
import dev.veyra.source.SourcePage

enum class SearchScope { EVERYWHERE, LIBRARY }
enum class SearchKind { ALL, MUSIC, PODCASTS }
enum class LibrarySection { FAVORITES, PLAYLISTS, DOWNLOADS, LOCAL_FILES }

data class CatalogUiState(
    val provider: SourceProvider = SourceProvider.YOUTUBE,
    val page: SourcePage = SourcePage(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failure: AppFailure? = null,
)

data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.EVERYWHERE,
    val kind: SearchKind = SearchKind.MUSIC,
    val provider: SourceProvider = SourceProvider.YOUTUBE,
    val page: SourcePage = SourcePage(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val failure: AppFailure? = null,
)

/** Real source collection metadata/results; the root owns fetching and pagination. */
data class CollectionUiState(
    val collection: MediaCollection,
    val page: SourcePage = SourcePage(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failure: AppFailure? = null,
)

/** Capabilities are supplied by the composition root, not inferred from a successful metadata fetch. */
data class MusicUiOptions(
    val downloadableProviders: Set<SourceProvider> = emptySet(),
    val unavailableItems: Map<String, AppFailure> = emptyMap(),
    val undoableQueueContexts: Set<QueueContext> = emptySet(),
    val canUndoLibrary: Boolean = false,
    val supportsImport: Boolean = true,
    val minSpeed: Float = 0.5f,
    val maxSpeed: Float = 2f,
)

/** Every user intent is handled by the single app controller. Nothing here starts a private engine. */
sealed interface MusicUiAction {
    data class SearchChanged(
        val query: String,
        val scope: SearchScope,
        val kind: SearchKind,
        val provider: SourceProvider,
    ) : MusicUiAction
    data object RunSearch : MusicUiAction
    data object LoadMoreSearch : MusicUiAction
    data class Discover(val provider: SourceProvider) : MusicUiAction
    data object LoadMoreDiscovery : MusicUiAction
    data class OpenSearch(val kind: SearchKind = SearchKind.MUSIC) : MusicUiAction
    data class OpenLibrary(val section: LibrarySection) : MusicUiAction
    data class OpenPlaylist(val playlistId: String) : MusicUiAction
    data class OpenCollection(val collection: MediaCollection) : MusicUiAction
    data class OpenMedia(val item: MediaItem) : MusicUiAction
    data class OpenShow(val show: PodcastShow) : MusicUiAction
    /** Root validates the public URL and offline policy before invoking the platform opener. */
    data class OpenSourcePage(val url: String) : MusicUiAction
    data object NavigateBack : MusicUiAction
    data object RefreshCollection : MusicUiAction
    data object LoadMoreCollection : MusicUiAction
    data object ImportAudio : MusicUiAction

    data class Play(val item: MediaItem) : MusicUiAction
    /** Emitted only after an explicit replace-and-play action when pending work exists. */
    data class PlayCollection(val items: List<MediaItem>, val shuffle: Boolean = false) : MusicUiAction
    data class EnqueueCollection(val items: List<MediaItem>) : MusicUiAction
    /** Explicit item-favorite batch; never claims the source album itself is a saved local entity. */
    data class SetFavorites(val items: List<MediaItem>, val favorite: Boolean) : MusicUiAction
    data class ToggleFavorite(val item: MediaItem) : MusicUiAction
    data class Enqueue(val item: MediaItem, val playNext: Boolean) : MusicUiAction
    data class CreatePlaylist(val name: String, val initialItem: MediaItem? = null) : MusicUiAction
    data class RenamePlaylist(val playlistId: String, val name: String) : MusicUiAction
    data class DeletePlaylist(val playlistId: String) : MusicUiAction
    data class AddToPlaylist(val playlistId: String, val item: MediaItem, val allowDuplicate: Boolean = false) : MusicUiAction
    data class RemovePlaylistEntry(val playlistId: String, val entryId: String) : MusicUiAction
    data class MovePlaylistEntry(val playlistId: String, val entryId: String, val toIndex: Int) : MusicUiAction
    data object UndoLibrary : MusicUiAction

    data class Download(val item: MediaItem) : MusicUiAction
    data class DownloadCollection(val items: List<MediaItem>) : MusicUiAction
    data class PauseDownload(val downloadId: String) : MusicUiAction
    data class ResumeDownload(val downloadId: String) : MusicUiAction
    data class CancelDownload(val downloadId: String) : MusicUiAction
    data class RemoveDownload(val downloadId: String) : MusicUiAction
    data object ManageStorage : MusicUiAction

    data object OpenPlayer : MusicUiAction
    data object ClosePlayer : MusicUiAction
    data class OpenQueue(val context: QueueContext? = null) : MusicUiAction
    data class ViewQueue(val context: QueueContext) : MusicUiAction
    /** Selection activates this exact occurrence; changing the viewed context alone never does. */
    data class PlayQueueEntry(val context: QueueContext, val entryId: String) : MusicUiAction
    data object TogglePlayPause : MusicUiAction
    data object Previous : MusicUiAction
    data object Next : MusicUiAction
    data class Seek(val positionMs: Long, val expectedEntryId: String?, val expectedGeneration: Long? = null) : MusicUiAction
    data class Skip(val milliseconds: Long, val expectedEntryId: String? = null, val expectedGeneration: Long? = null) : MusicUiAction
    data class SetShuffle(val enabled: Boolean) : MusicUiAction
    data class SetRepeat(val mode: RepeatMode) : MusicUiAction
    data class SetSpeed(val rate: Float, val forThisShow: Boolean = false) : MusicUiAction
    data class SetSleepMinutes(val minutes: Int) : MusicUiAction
    data object SleepAtEpisodeEnd : MusicUiAction
    data object ClearSleepTimer : MusicUiAction
    data class MoveQueueEntry(val context: QueueContext, val entryId: String, val toIndex: Int) : MusicUiAction
    data class RemoveQueueEntry(val context: QueueContext, val entryId: String) : MusicUiAction
    data class ClearQueue(val context: QueueContext) : MusicUiAction
    data class UndoQueue(val context: QueueContext) : MusicUiAction
}
