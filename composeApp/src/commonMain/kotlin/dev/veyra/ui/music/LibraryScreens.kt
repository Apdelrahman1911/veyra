package dev.veyra.ui.music

import dev.veyra.ui.design.SegmentedTabs
import androidx.compose.ui.semantics.Role
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable
import dev.veyra.ui.design.ActionButton
import dev.veyra.ui.design.NativeMenu
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.model.QueueContext
import dev.veyra.model.SourceProvider
import dev.veyra.playback.PlaybackState
import dev.veyra.ui.design.CollectionLink
import dev.veyra.ui.design.ScreenIntro
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.DownloadSurfaceOrigin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    snapshot: AppSnapshot,
    selectedSection: LibrarySection,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    var create by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortByCreator by rememberSaveable { mutableStateOf(false) }
    val media = remember(snapshot, selectedSection, query, sortByCreator) {
        val all = when (selectedSection) {
            LibrarySection.FAVORITES -> snapshot.favoriteIds.mapNotNull { snapshot.media[it] }
            LibrarySection.LOCAL_FILES -> snapshot.media.values.filter { it.source.provider == SourceProvider.LOCAL }
            else -> emptyList()
        }
        val matching = all.filter { matchesMediaQuery(it, query) }
        if (sortByCreator) matching.sortedWith(compareBy({ mediaSearchKey(it.creator) }, { mediaSearchKey(it.title) }))
        else matching.sortedBy { mediaSearchKey(it.title) }
    }
    Column(modifier.fillMaxSize().testTag("library-screen")) {
        if (selectedSection == LibrarySection.OVERVIEW) {
            val playlists = remember(snapshot.playlists) { snapshot.playlists.sortedByDescending { it.updatedEpochMs } }
            LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ScreenIntro(strings.text(TextKey.Library), strings.text(TextKey.LibrarySubtitle)) }
                item {
                    ActionButton(strings.text(TextKey.NewPlaylist), "plus", true, onClick = { create = true }, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("new-playlist")) {
                        Icon(VeyraIcons.Add, null, Modifier.padding(end = 8.dp)); Text(strings.text(TextKey.NewPlaylist))
                    }
                }
                item {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
                        LibraryShortcut(strings.text(TextKey.Favorites), strings.format(TextKey.TrackCount, strings.number(snapshot.favoriteIds.size.toLong())),
                            VeyraIcons.Favorite, { onAction(MusicUiAction.OpenLibrary(LibrarySection.FAVORITES)) }, Modifier.weight(1f).testTag("library-FAVORITES"))
                        LibraryShortcut(strings.text(TextKey.Downloads), strings.text(TextKey.ReadyOffline), VeyraIcons.Download,
                            { onAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) }, Modifier.weight(1f).testTag("library-DOWNLOADS"))
                        if (options.supportsImport || snapshot.media.values.any { it.source.provider == SourceProvider.LOCAL }) {
                            LibraryShortcut(strings.text(TextKey.OnThisDevice), if (options.supportsImport) strings.text(TextKey.ImportAudio) else null, VeyraIcons.Music,
                                { onAction(MusicUiAction.OpenLibrary(LibrarySection.LOCAL_FILES)) }, Modifier.weight(1f).testTag("library-LOCAL_FILES"))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.text(TextKey.YourPlaylists), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        TextButton({ onAction(MusicUiAction.OpenLibrary(LibrarySection.PLAYLISTS)) }, Modifier.heightIn(min = 48.dp).testTag("library-PLAYLISTS")) {
                            Text(strings.text(TextKey.SeeAll))
                        }
                    }
                }
                if (playlists.isEmpty()) item {
                    EmptyState(strings.text(TextKey.CreateFirstPlaylist), strings.text(TextKey.NoPlaylistsDescription),
                        strings.text(TextKey.NewPlaylist), { create = true }, Modifier.padding(horizontal = 20.dp))
                } else items(playlists, key = { it.id }) { PlaylistLibraryRow(it, snapshot, onAction) }
                // Every saved song in one searchable list, without opening another section.
                item {
                    Text(strings.text(TextKey.LibrarySongs), Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                }
                item {
                    OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.SearchLocal)) }, singleLine = true,
                        leadingIcon = { Icon(VeyraIcons.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("library-query"))
                }
                val songs = playlistCandidates(snapshot).filter { matchesMediaQuery(it, query) }
                if (songs.isEmpty()) item {
                    EmptyState(strings.text(if (query.isBlank()) TextKey.EmptyLibrary else TextKey.NoResults),
                        strings.text(if (query.isBlank()) TextKey.EmptyFavoritesDescription else TextKey.NoResultsDescription),
                        strings.text(TextKey.SearchMusic), { onAction(MusicUiAction.OpenSearch()) }, Modifier.padding(horizontal = 20.dp))
                } else items(songs, key = { "song-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
            }
        } else if (selectedSection == LibrarySection.DOWNLOADS) {
            DownloadsContent(snapshot, onAction, Modifier.weight(1f), options) {
                LibraryHeader(selectedSection, onAction)
            }
        } else {
            LazyColumn(Modifier.weight(1f).testTag("library-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { LibraryHeader(selectedSection, onAction) }
                item {
                    OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.SearchLocal)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("library-query"))
                }
                // The query is its own scroll target even when options wrap into several rows.
                item {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedSection == LibrarySection.PLAYLISTS) {
                            ActionButton(strings.text(TextKey.NewPlaylist), "plus", true, onClick = { create = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("new-playlist")) { Text(strings.text(TextKey.NewPlaylist)) }
                        } else {
                            SegmentedTabs(listOf(strings.text(TextKey.SortByName), strings.text(TextKey.SortByCreator)),
                                if (sortByCreator) 1 else 0, { sortByCreator = it == 1 }, Modifier.fillMaxWidth())
                        }
                        if (options.supportsImport) ActionButton(strings.text(TextKey.ImportAudio), "square.and.arrow.down", false, onClick = { onAction(MusicUiAction.ImportAudio) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ImportAudio)) }
                        if (options.canUndoLibrary) TextButton(onClick = { onAction(MusicUiAction.UndoLibrary) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoLibraryEdit)) }
                    }
                }
                if (selectedSection == LibrarySection.PLAYLISTS) {
                    val matching = snapshot.playlists.filter { mediaSearchKey(query.trim()) in mediaSearchKey(it.name) }
                    if (matching.isEmpty()) item {
                        EmptyState(strings.text(if (query.isBlank()) TextKey.NoPlaylists else TextKey.NoResults), strings.text(if (query.isBlank()) TextKey.NoPlaylistsDescription else TextKey.NoResultsDescription), modifier = Modifier.padding(20.dp))
                    }
                    items(matching, key = { it.id }) { PlaylistLibraryRow(it, snapshot, onAction) }
                } else {
                    if (media.isEmpty()) item {
                        EmptyState(
                            strings.text(if (query.isNotBlank()) TextKey.NoResults else if (selectedSection == LibrarySection.FAVORITES) TextKey.EmptyFavorites else TextKey.EmptyLibrary),
                            strings.text(if (query.isNotBlank()) TextKey.NoResultsDescription else if (selectedSection == LibrarySection.FAVORITES) TextKey.EmptyFavoritesDescription else TextKey.ImportDescription),
                            strings.text(TextKey.SearchMusic), { onAction(MusicUiAction.OpenSearch()) }, Modifier.padding(20.dp),
                        )
                    }
                    items(media, key = { it.id }) { MusicMediaRow(it, snapshot, onAction, options = options) }
                }
            }
        }
    }
    if (create) PlaylistNameDialog(onDismiss = { create = false },
        suggestedName = defaultPlaylistName(snapshot.playlists) { strings.text(TextKey.DefaultPlaylistName).replace("{0}", it) },
        onSave = { onAction(MusicUiAction.CreatePlaylist(it, openAfter = true)); create = false })
}

/** The header scrolls with the list: large labels must not consume a short window's whole viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(selectedSection: LibrarySection, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    ScreenIntro(strings.text(selectedSection.label()))
    TextButton({ onAction(MusicUiAction.OpenLibrary(LibrarySection.OVERVIEW)) }, Modifier.padding(horizontal = 20.dp).heightIn(min = 48.dp)) {
        Icon(VeyraIcons.Library, null)
        Text(strings.text(TextKey.BrowseLibrary), Modifier.padding(start = 8.dp))
    }
}

private fun LibrarySection.label(): TextKey = when (this) {
    LibrarySection.OVERVIEW -> TextKey.AllLibrary
    LibrarySection.FAVORITES -> TextKey.Favorites
    LibrarySection.PLAYLISTS -> TextKey.Playlists
    LibrarySection.DOWNLOADS -> TextKey.Downloads
    LibrarySection.LOCAL_FILES -> TextKey.OnThisDevice
}

@Composable
private fun LibraryShortcut(title: String, subtitle: String?, icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(modifier = modifier.heightIn(min = 96.dp)
        .adaptiveClickable(role = Role.Button, shape = RoundedCornerShape(20.dp), onClick = onClick), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun PlaylistLibraryRow(playlist: Playlist, snapshot: AppSnapshot, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    var rename by remember(playlist.id) { mutableStateOf(false) }
    var delete by remember(playlist.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .adaptiveClickable(role = Role.Button, shape = RoundedCornerShape(20.dp)) { onAction(MusicUiAction.OpenPlaylist(playlist.id)) }
            .testTag("playlist-row-${playlist.id}"),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            PlaylistCover(playlist, snapshot, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                MetadataText(playlist.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(strings.format(TextKey.TrackCount, strings.number(playlist.entries.size.toLong())), Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
            }
            Box {
                ActionIcon(VeyraIcons.MoreOptions, strings.text(TextKey.PlaylistOptions), { menu = true })
                NativeMenu(menu, { menu = false }) {
                    item(strings.text(TextKey.RenamePlaylist), symbol = "pencil") { menu = false; rename = true }
                    item(strings.text(TextKey.DeletePlaylist), destructive = true, symbol = "trash") { menu = false; delete = true }
                }
            }
        }
    }
    if (rename) PlaylistNameDialog(playlist.name, { rename = false }) { onAction(MusicUiAction.RenamePlaylist(playlist.id, it)); rename = false }
    if (delete) ConfirmActionDialog(strings.text(TextKey.DeletePlaylist), strings.format(TextKey.ConfirmDeletePlaylist, playlist.name), strings.text(TextKey.Delete), { delete = false }) {
        onAction(MusicUiAction.DeletePlaylist(playlist.id)); delete = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistDetailScreen(
    snapshot: AppSnapshot,
    playlistId: String,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
    playback: PlaybackState? = null,
) {
    val strings = LocalStrings.current
    val playlist = snapshot.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        EmptyState(strings.text(TextKey.PlaylistMissing), strings.text(TextKey.Library), strings.text(TextKey.Back), { onAction(MusicUiAction.OpenLibrary(LibrarySection.PLAYLISTS)) }, modifier.fillMaxSize())
        return
    }
    var query by rememberSaveable(playlistId) { mutableStateOf("") }
    var rename by remember(playlistId) { mutableStateOf(false) }
    var delete by remember(playlistId) { mutableStateOf(false) }
    var replaceShuffle by remember(playlistId) { mutableStateOf<Boolean?>(null) }
    var adding by remember(playlistId) { mutableStateOf(false) }
    var editing by rememberSaveable(playlistId) { mutableStateOf(false) }
    var menu by remember(playlistId) { mutableStateOf(false) }
    val availableItems = playlist.entries.mapNotNull { snapshot.media[it.mediaId] }.filter { it.kind == MediaKind.MUSIC }
    val shown = playlist.entries.withIndex().filter { (_, entry) -> snapshot.media[entry.mediaId]?.let { matchesMediaQuery(it, query) } ?: query.isBlank() }
    fun play(shuffle: Boolean) {
        // The canonical upcoming occurrences are the authority whenever the owner published them.
        // The legacy snapshot queue is a compatibility fallback for standalone callers only.
        val hasUpcoming = playback?.hasUpcomingOccurrence(QueueContext.MUSIC, snapshot)
            ?: pendingOccurrenceIds(snapshot.queue(QueueContext.MUSIC).entries.map { it.id },
                snapshot.queue(QueueContext.MUSIC).currentEntryId).isNotEmpty()
        if (hasUpcoming) replaceShuffle = shuffle
        else onAction(MusicUiAction.PlayCollection(availableItems, shuffle))
    }
    LazyColumn(modifier.fillMaxSize().testTag("playlist-detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlaylistCover(playlist, snapshot, Modifier.size(112.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetadataText(playlist.name, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(strings.format(TextKey.TrackCount, strings.number(playlist.entries.size.toLong())), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    ActionIcon(VeyraIcons.MoreOptions, strings.text(TextKey.PlaylistOptions), { menu = true }, Modifier.testTag("playlist-menu"))
                    NativeMenu(menu, { menu = false }) {
                        item(strings.text(TextKey.RenamePlaylist), symbol = "pencil") { menu = false; rename = true }
                        item(strings.text(TextKey.DeletePlaylist), destructive = true, symbol = "trash") { menu = false; delete = true }
                    }
                }
            }
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(strings.text(TextKey.Play), "play.fill", true, { play(false) }, enabled = availableItems.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(VeyraIcons.Play, null, Modifier.padding(end = 6.dp)); Text(strings.text(TextKey.Play))
                }
                ActionButton(strings.text(TextKey.Shuffle), "shuffle", false, { play(true) }, enabled = availableItems.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(VeyraIcons.Shuffle, null, Modifier.padding(end = 6.dp)); Text(strings.text(TextKey.Shuffle))
                }
                ActionButton(strings.text(TextKey.AddSongs), "plus", false, { adding = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist-add-songs")) {
                    Icon(VeyraIcons.Add, null, Modifier.padding(end = 6.dp)); Text(strings.text(TextKey.AddSongs))
                }
                if (playlist.entries.isNotEmpty()) TextButton({ editing = !editing }, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist-edit")) {
                    Text(strings.text(if (editing) TextKey.DoneEditing else TextKey.EditPlaylist))
                }
                if (options.canUndoLibrary) TextButton({ onAction(MusicUiAction.UndoLibrary) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoLibraryEdit)) }
                if (QueueContext.MUSIC in options.undoableQueueContexts) TextButton({ onAction(MusicUiAction.UndoQueue(QueueContext.MUSIC)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoQueueEdit)) }
            }
            if (playlist.entries.size > 8) OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.Search)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (shown.isEmpty()) item {
            if (query.isBlank()) EmptyState(strings.text(TextKey.EmptyPlaylist), strings.text(TextKey.EmptyPlaylistDescription),
                strings.text(TextKey.AddSongs), { adding = true }, Modifier.padding(20.dp))
            else EmptyState(strings.text(TextKey.NoResults), strings.text(TextKey.NoResultsDescription), modifier = Modifier.padding(20.dp))
        }
        itemsIndexed(shown, key = { _, indexed -> indexed.value.id }) { visibleIndex, indexed ->
            val entry = indexed.value
            val item = snapshot.media[entry.mediaId]
            Column(Modifier.fillMaxWidth().testTag("playlist-entry-${entry.id}")) {
                if (item != null) MusicMediaRow(item, snapshot, onAction, options = options)
                else Text(strings.text(TextKey.FileUnavailable), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                // Reorder/remove only while editing: the everyday list stays clean.
                if (editing) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    val title = item?.title.orEmpty()
                    ActionIcon(VeyraIcons.ArrowUp, strings.format(TextKey.MoveEntryUp, title), { onAction(MusicUiAction.MovePlaylistEntry(playlist.id, entry.id, shown[visibleIndex - 1].index)) }, enabled = visibleIndex > 0)
                    ActionIcon(VeyraIcons.ArrowDown, strings.format(TextKey.MoveEntryDown, title), { onAction(MusicUiAction.MovePlaylistEntry(playlist.id, entry.id, shown[visibleIndex + 1].index)) }, enabled = visibleIndex < shown.lastIndex)
                    ActionIcon(VeyraIcons.Delete, strings.text(TextKey.RemoveFromPlaylist), { onAction(MusicUiAction.RemovePlaylistEntry(playlist.id, entry.id)) })
                }
                HorizontalDivider()
            }
        }
    }
    if (rename) PlaylistNameDialog(playlist.name, { rename = false }) { onAction(MusicUiAction.RenamePlaylist(playlist.id, it)); rename = false }
    if (delete) ConfirmActionDialog(strings.text(TextKey.DeletePlaylist), strings.format(TextKey.ConfirmDeletePlaylist, playlist.name), strings.text(TextKey.Delete), { delete = false }) { onAction(MusicUiAction.DeletePlaylist(playlist.id)); delete = false }
    if (adding) AddSongsDialog(snapshot, playlist, onAction, onFindMusic = { onAction(MusicUiAction.OpenSearch()) }, onDismiss = { adding = false })
    replaceShuffle?.let { shuffle -> ConfirmActionDialog(strings.text(TextKey.ReplaceQueueAndPlay), strings.text(TextKey.ConfirmReplaceQueue), strings.text(TextKey.ReplaceQueueAndPlay), { replaceShuffle = null }) { onAction(MusicUiAction.PlayCollection(availableItems, shuffle)); replaceShuffle = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsContent(snapshot: AppSnapshot, onAction: (MusicUiAction) -> Unit, modifier: Modifier,
    options: MusicUiOptions, header: @Composable () -> Unit) {
    val strings = LocalStrings.current
    var filter by rememberSaveable { mutableStateOf(0) }
    val surface = options.downloadSurface(snapshot)
    val downloads = surface.rows().filter {
        when (filter) {
            1 -> it.isActive
            2 -> it.ready
            3 -> it.needsAttention
            else -> true
        }
    }
    val bytes = surface.storageBytes()
    LazyColumn(modifier.fillMaxSize().testTag("downloads-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { header() }
        item {
            Text(strings.format(TextKey.StorageUsed, strings.byteSize(bytes)), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedTabs(listOf(TextKey.All, TextKey.InProgress, TextKey.Completed, TextKey.NeedsAttention).map { strings.text(it) },
                filter, { filter = it }, Modifier.padding(horizontal = 20.dp).testTag("downloads-filter"))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onAction(MusicUiAction.ManageStorage) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ManageStorage)) }
            }
        }
        if (downloads.isEmpty()) item {
            EmptyState(strings.text(TextKey.EmptyDownloads), strings.text(TextKey.EmptyDownloadsDescription), strings.text(TextKey.SearchMusic), { onAction(MusicUiAction.OpenSearch()) }, Modifier.padding(20.dp))
        }
        items(downloads, key = { it.id }) { entry -> DownloadRow(entry, snapshot, onAction, options) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(entry: DownloadSurfaceEntry, snapshot: AppSnapshot, onAction: (MusicUiAction) -> Unit, options: MusicUiOptions) {
    val strings = LocalStrings.current
    val item = entry.mediaId?.let(snapshot.media::get)
    val savedBinding = entry.bindingId?.takeIf {
        entry.ready && entry.origin == DownloadSurfaceOrigin.TYPED_CATALOGUE && it.isNotBlank()
    }
    var remove by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)).padding(16.dp).testTag("download-${entry.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item != null) MetadataText(item.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        else if (entry.origin == DownloadSurfaceOrigin.TYPED_SOURCE) {
            MetadataText(entry.displayTitle ?: strings.text(TextKey.InformationUnavailable), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(strings.text(TextKey.SourceRecording), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(strings.text(TextKey.InformationUnavailable), style = MaterialTheme.typography.titleMedium)
        Text(entry.statusText(strings), style = MaterialTheme.typography.bodyMedium)
        if (item != null && entry.ready && entry.origin == DownloadSurfaceOrigin.LEGACY) {
            entry.bindingId?.let(snapshot.playbackBindings::get)?.let { binding ->
                PlaybackProvenanceCard(item, binding, compact = true, context = PlaybackProvenanceContext.DOWNLOADED)
            }
        }
        val total = entry.totalBytes
        if (entry.status != DownloadStatus.COMPLETE && entry.status != DownloadStatus.CANCELLED) {
            if (total != null && total > 0) {
                LinearProgressIndicator(progress = { (entry.bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(strings.format(TextKey.DownloadProgress, strings.byteSize(entry.bytesDownloaded), strings.byteSize(total)), style = MaterialTheme.typography.bodySmall)
            } else {
                if (entry.status in setOf(DownloadStatus.RUNNING, DownloadStatus.RESOLVING, DownloadStatus.VERIFYING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(strings.format(TextKey.DownloadProgressUnknown, strings.byteSize(entry.bytesDownloaded)), style = MaterialTheme.typography.bodySmall)
            }
        }
        entry.failure?.let { Text(strings.errorFor(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entry.ready || entry.checking) {
                if (item != null && entry.ready) ActionButton(strings.text(TextKey.Play), "play.fill", true,
                    {
                        onAction(if (savedBinding != null) MusicUiAction.PlaySaved(item, savedBinding)
                        else MusicUiAction.Play(item))
                    },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Play)) }
                ActionButton(strings.text(TextKey.RemoveDownload), "trash", false, { remove = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.RemoveDownload)) }
            } else {
                val retainedFailure = entry.status == DownloadStatus.FAILED && entry.hasOwnedBytes &&
                    !entry.cleanupPending && entry.origin != DownloadSurfaceOrigin.LEGACY
                when (entry.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING -> TextButton({ onAction(MusicUiAction.PauseDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.PauseDownload)) }
                    DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND -> TextButton({ onAction(MusicUiAction.ResumeDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ResumeDownload)) }
                    DownloadStatus.FAILED -> if (retainedFailure) {
                        val target = entry.retainedValidation
                        val choices = entry.retainedValidationChoices
                        if (target != null) TextButton(
                            { onAction(MusicUiAction.ValidateRetainedDownload(target.jobId,
                                target.bindingId, target.batchId, target.memberPosition,
                                target.expectedIntentRevision)) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("validate-${entry.id}"),
                        ) { Text(strings.text(TextKey.ValidateDownload)) }
                        else if (choices.isNotEmpty()) {
                            var memberMenu by remember(entry.id) { mutableStateOf(false) }
                            Box {
                                TextButton({ memberMenu = true },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("validate-${entry.id}")) {
                                    Text(strings.text(TextKey.ValidateDownload))
                                }
                                NativeMenu(expanded = memberMenu, onDismiss = { memberMenu = false }) {
                                    choices.forEachIndexed { index, choice ->
                                        item(strings.format(TextKey.ValidateDownloadMember, strings.number((index + 1).toLong())),
                                            tag = "validate-member-${entry.id}-${choice.memberPosition}", symbol = "checkmark.shield") {
                                            memberMenu = false
                                            onAction(MusicUiAction.ValidateRetainedDownload(choice.jobId,
                                                choice.bindingId, choice.batchId, choice.memberPosition,
                                                choice.expectedIntentRevision))
                                        }
                                    }
                                }
                            }
                        } else Text(
                            strings.text(TextKey.RetainedValidationUnavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("validate-unavailable-${entry.id}"),
                        )
                    } else TextButton({ onAction(MusicUiAction.ResumeDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("retry-${entry.id}")) { Text(strings.text(TextKey.Retry)) }
                    // Only a real catalogue item can start a new catalogue acquisition. A neutral
                    // source-only row retains its own pause/resume/cancel/remove actions.
                    DownloadStatus.CANCELLED, DownloadStatus.COMPLETE -> if (item != null && item.source.provider in options.downloadableProviders) TextButton({ onAction(MusicUiAction.Download(item)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Retry)) }
                    DownloadStatus.VERIFYING -> Unit
                }
                if (retainedFailure) {
                    ActionButton(strings.text(TextKey.RemoveDownload), "trash", false, { remove = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("remove-retained-${entry.id}")) { Text(strings.text(TextKey.RemoveDownload)) }
                } else if (entry.status !in setOf(DownloadStatus.CANCELLED, DownloadStatus.COMPLETE)) {
                    TextButton({ onAction(MusicUiAction.CancelDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.CancelDownload)) }
                }
            }
        }
        HorizontalDivider()
    }
    if (remove) ConfirmActionDialog(strings.text(TextKey.RemoveDownload), strings.format(TextKey.ConfirmRemoveDownload, item?.title ?: entry.displayTitle.orEmpty()), strings.text(TextKey.RemoveDownload), { remove = false }) {
        onAction(MusicUiAction.RemoveDownload(entry.id)); remove = false
    }
}
