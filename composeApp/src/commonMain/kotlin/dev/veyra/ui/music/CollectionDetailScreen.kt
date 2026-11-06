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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.QueueContext
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.QuantityKey
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons

/** Albums/releases/playlists are shown only when returned as actual source collections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionDetailScreen(
    snapshot: AppSnapshot,
    state: CollectionUiState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    val collection = state.collection
    val tracks = state.page.items.filter { it.kind == MediaKind.MUSIC }
    val complete = state.loaded && !state.loading && state.page.nextPageToken == null
    val allFavorite = tracks.isNotEmpty() && tracks.all { it.id in snapshot.favoriteIds }
    val downloadable = tracks.distinctBy { it.id }.filter { item ->
        item.source.provider in options.downloadableProviders && snapshot.downloadFor(item.id)?.let { record ->
            record.status in setOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED) || record.status == DownloadStatus.COMPLETE && !record.isReady()
        } != false
    }
    var query by rememberSaveable(collection.id) { mutableStateOf("") }
    // Capture the exact requested occurrences while the replacement confirmation is open.
    var replacement by remember(collection.id) { mutableStateOf<Pair<List<MediaItem>, Boolean>?>(null) }
    fun play(shuffle: Boolean) {
        val queue = snapshot.queue(QueueContext.MUSIC)
        if (pendingOccurrenceIds(queue.entries.map { it.id }, queue.currentEntryId).isNotEmpty()) replacement = tracks to shuffle
        else onAction(MusicUiAction.PlayCollection(tracks, shuffle))
    }
    val matching = tracks.withIndex().filter { matchesMediaQuery(it.value, query) }
    LazyColumn(modifier.fillMaxSize().testTag("collection-detail"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(VeyraIcons.Back, strings.text(TextKey.Back), { onAction(MusicUiAction.NavigateBack) })
                Text(providerName(collection.source.provider), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                ActionIcon(VeyraIcons.Refresh, strings.text(TextKey.Refresh), { onAction(MusicUiAction.RefreshCollection) }, enabled = !state.loading && !snapshot.settings.offlineOnly)
            }
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(collection.artworkUrl, collection.title, Modifier.size(88.dp), offlineOnly = snapshot.settings.offlineOnly)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataText(collection.title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    if (collection.creator.isNotBlank()) MetadataText(collection.creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.loaded || tracks.isNotEmpty()) Text(strings.quantity(QuantityKey.Tracks, tracks.size), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            Text(strings.text(TextKey.SourceResolutionDescription), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button({ play(false) }, enabled = complete && tracks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("collection-play")) { Text(strings.text(TextKey.PlayAll)) }
                OutlinedButton({ play(true) }, enabled = complete && tracks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("collection-shuffle")) { Text(strings.text(TextKey.Shuffle)) }
                OutlinedButton({ onAction(MusicUiAction.EnqueueCollection(tracks)) }, enabled = complete && tracks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("collection-enqueue")) { Text(strings.text(TextKey.AddAllToQueue)) }
                TextButton({ onAction(MusicUiAction.SetFavorites(tracks.distinctBy { it.id }, !allFavorite)) }, enabled = complete && tracks.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("collection-favorite")) { Text(strings.text(if (allFavorite) TextKey.UnfavoriteAllTracks else TextKey.FavoriteAllTracks)) }
                if (tracks.any { it.source.provider in options.downloadableProviders }) TextButton({ onAction(MusicUiAction.DownloadCollection(downloadable)) }, enabled = complete && downloadable.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("collection-download")) { Text(strings.text(TextKey.DownloadAll)) }
                if (QueueContext.MUSIC in options.undoableQueueContexts) TextButton({ onAction(MusicUiAction.UndoQueue(QueueContext.MUSIC)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.UndoQueueEdit)) }
            }
            if (state.page.nextPageToken != null) Text(strings.text(TextKey.CollectionLoadingRemaining), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            state.page.attribution?.takeIf { it.isNotBlank() }?.let { MetadataText(it, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, paragraph = true) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("collection-loading"))
            state.failure?.let { ErrorState(strings.errorFor(it), onRetry = { onAction(MusicUiAction.RefreshCollection) }, modifier = Modifier.padding(20.dp)) }
            state.page.notice?.let { ErrorState(strings.errorFor(it), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            if (tracks.isNotEmpty()) OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.Search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).testTag("collection-query"))
        }
        if (matching.isEmpty() && state.loaded && !state.loading && state.failure == null) item {
            EmptyState(strings.text(if (query.isNotBlank()) TextKey.NoResults else TextKey.CollectionEmpty), strings.text(if (query.isNotBlank()) TextKey.NoResultsDescription else TextKey.NoPlayableItems), modifier = Modifier.padding(20.dp))
        }
        // Collections can contain duplicate recordings. Index here preserves returned occurrences.
        itemsIndexed(matching, key = { _, indexed -> "${collection.id}-${indexed.index}-${indexed.value.id}" }) { _, indexed ->
            MusicMediaRow(indexed.value, snapshot, onAction, options = options)
        }
        if (state.page.nextPageToken != null) item {
            Button({ onAction(MusicUiAction.LoadMoreCollection) }, enabled = !state.loading && !snapshot.settings.offlineOnly, modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp).testTag("collection-load-more")) { Text(strings.text(TextKey.LoadMore)) }
        }
    }
    replacement?.let { (items, shuffle) ->
        ConfirmActionDialog(strings.text(TextKey.ReplaceQueueAndPlay), strings.text(TextKey.ConfirmReplaceQueue), strings.text(TextKey.ReplaceQueueAndPlay), { replacement = null }) {
            onAction(MusicUiAction.PlayCollection(items, shuffle)); replacement = null
        }
    }
}
