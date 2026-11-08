package dev.veyra.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
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
        if (selectedSection == LibrarySection.DOWNLOADS) {
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
                            Button(onClick = { create = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("new-playlist")) { Text(strings.text(TextKey.NewPlaylist)) }
                        } else {
                            FilterChip(!sortByCreator, { sortByCreator = false }, { Text(strings.text(TextKey.SortByName)) }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(sortByCreator, { sortByCreator = true }, { Text(strings.text(TextKey.SortByCreator)) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        if (options.supportsImport) OutlinedButton(onClick = { onAction(MusicUiAction.ImportAudio) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ImportAudio)) }
                        if (options.canUndoLibrary) TextButton(onClick = { onAction(MusicUiAction.UndoLibrary) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoLibraryEdit)) }
                    }
                }
                if (selectedSection == LibrarySection.PLAYLISTS) {
                    val matching = snapshot.playlists.filter { mediaSearchKey(query.trim()) in mediaSearchKey(it.name) }
                    if (matching.isEmpty()) item {
                        EmptyState(strings.text(if (query.isBlank()) TextKey.NoPlaylists else TextKey.NoResults), strings.text(if (query.isBlank()) TextKey.NoPlaylistsDescription else TextKey.NoResultsDescription), modifier = Modifier.padding(20.dp))
                    }
                    items(matching, key = { it.id }) { PlaylistLibraryRow(it, onAction) }
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
    if (create) PlaylistNameDialog(onDismiss = { create = false }, onSave = { onAction(MusicUiAction.CreatePlaylist(it)); create = false })
}

/** The header scrolls with the list: large labels must not consume a short window's whole viewport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(selectedSection: LibrarySection, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    SectionTitle(strings.text(TextKey.Library))
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibrarySection.entries.forEach { section ->
            FilterChip(
                selected = selectedSection == section,
                onClick = { onAction(MusicUiAction.OpenLibrary(section)) },
                label = { Text(strings.text(section.label())) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("library-${section.name}"),
            )
        }
    }
}

private fun LibrarySection.label(): TextKey = when (this) {
    LibrarySection.FAVORITES -> TextKey.Favorites
    LibrarySection.PLAYLISTS -> TextKey.Playlists
    LibrarySection.DOWNLOADS -> TextKey.Downloads
    LibrarySection.LOCAL_FILES -> TextKey.OnThisDevice
}

@Composable
private fun PlaylistLibraryRow(playlist: Playlist, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    var rename by remember(playlist.id) { mutableStateOf(false) }
    var delete by remember(playlist.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAction(MusicUiAction.OpenPlaylist(playlist.id)) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
            Icon(VeyraIcons.Library, null, Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                MetadataText(playlist.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(strings.format(TextKey.TrackCount, playlist.entries.size.toString()), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
            }
        }
        ActionIcon(VeyraIcons.Edit, strings.text(TextKey.RenamePlaylist), { rename = true })
        ActionIcon(VeyraIcons.Delete, strings.text(TextKey.DeletePlaylist), { delete = true })
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
    val availableItems = playlist.entries.mapNotNull { snapshot.media[it.mediaId] }.filter { it.kind == MediaKind.MUSIC }
    val shown = playlist.entries.withIndex().filter { (_, entry) -> snapshot.media[entry.mediaId]?.let { matchesMediaQuery(it, query) } ?: query.isBlank() }
    fun play(shuffle: Boolean) {
        val queue = snapshot.queue(QueueContext.MUSIC)
        if (pendingOccurrenceIds(queue.entries.map { it.id }, queue.currentEntryId).isNotEmpty()) replaceShuffle = shuffle
        else onAction(MusicUiAction.PlayCollection(availableItems, shuffle))
    }
    LazyColumn(modifier.fillMaxSize().testTag("playlist-detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(VeyraIcons.Back, strings.text(TextKey.Back), { onAction(MusicUiAction.OpenLibrary(LibrarySection.PLAYLISTS)) })
                Column(Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetadataText(playlist.name, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(strings.format(TextKey.TrackCount, playlist.entries.size.toString()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ play(false) }, enabled = availableItems.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Play)) }
                OutlinedButton({ play(true) }, enabled = availableItems.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Shuffle)) }
                TextButton({ rename = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Rename)) }
                TextButton({ delete = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.DeletePlaylist)) }
                if (options.canUndoLibrary) TextButton({ onAction(MusicUiAction.UndoLibrary) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoLibraryEdit)) }
                if (QueueContext.MUSIC in options.undoableQueueContexts) TextButton({ onAction(MusicUiAction.UndoQueue(QueueContext.MUSIC)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoQueueEdit)) }
            }
            OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.Search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
        }
        if (shown.isEmpty()) item {
            EmptyState(strings.text(if (query.isBlank()) TextKey.EmptyPlaylist else TextKey.NoResults), strings.text(if (query.isBlank()) TextKey.EmptyPlaylistDescription else TextKey.NoResultsDescription), strings.text(TextKey.AddMusic), { onAction(MusicUiAction.OpenSearch()) }, Modifier.padding(20.dp))
        }
        itemsIndexed(shown, key = { _, indexed -> indexed.value.id }) { visibleIndex, indexed ->
            val entry = indexed.value
            val item = snapshot.media[entry.mediaId]
            Column(Modifier.fillMaxWidth().testTag("playlist-entry-${entry.id}")) {
                if (item != null) MusicMediaRow(item, snapshot, onAction, options = options)
                else Text(strings.text(TextKey.FileUnavailable), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
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
            SectionTitle(strings.text(TextKey.Downloads), description = strings.format(TextKey.StorageUsed, strings.byteSize(bytes)))
            FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TextKey.All, TextKey.InProgress, TextKey.Completed, TextKey.NeedsAttention).forEachIndexed { index, label ->
                    FilterChip(filter == index, { filter = index }, { Text(strings.text(label)) }, modifier = Modifier.heightIn(min = 48.dp))
                }
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
    var remove by remember(entry.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("download-${entry.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (item != null && entry.ready) Button({ onAction(MusicUiAction.Play(item)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Play)) }
                OutlinedButton({ remove = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.RemoveDownload)) }
            } else {
                when (entry.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.RESOLVING -> TextButton({ onAction(MusicUiAction.PauseDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.PauseDownload)) }
                    DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED -> TextButton({ onAction(MusicUiAction.ResumeDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(if (entry.status == DownloadStatus.FAILED) TextKey.Retry else TextKey.ResumeDownload)) }
                    // Only a real catalogue item can start a new catalogue acquisition. A neutral
                    // source-only row retains its own pause/resume/cancel/remove actions.
                    DownloadStatus.CANCELLED, DownloadStatus.COMPLETE -> if (item != null && item.source.provider in options.downloadableProviders) TextButton({ onAction(MusicUiAction.Download(item)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Retry)) }
                    DownloadStatus.VERIFYING -> Unit
                }
                if (entry.status !in setOf(DownloadStatus.CANCELLED, DownloadStatus.COMPLETE)) TextButton({ onAction(MusicUiAction.CancelDownload(entry.id)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.CancelDownload)) }
            }
        }
        HorizontalDivider()
    }
    if (remove) ConfirmActionDialog(strings.text(TextKey.RemoveDownload), strings.format(TextKey.ConfirmRemoveDownload, item?.title ?: entry.displayTitle.orEmpty()), strings.text(TextKey.RemoveDownload), { remove = false }) {
        onAction(MusicUiAction.RemoveDownload(entry.id)); remove = false
    }
}
