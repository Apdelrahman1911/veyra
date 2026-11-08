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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppFailure
import dev.veyra.model.AppSnapshot
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaItem
import dev.veyra.model.PodcastShow
import dev.veyra.model.SourceProvider
import dev.veyra.playback.PlaybackState
import dev.veyra.source.MediaCollection
import dev.veyra.source.SourcePage
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(
    snapshot: AppSnapshot,
    playback: PlaybackState,
    discovery: CatalogUiState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    val current = playback.currentItem ?: snapshot.queue(snapshot.activeContext).currentEntry?.let { snapshot.media[it.mediaId] }
    val recent = remember(snapshot.history, snapshot.media) {
        snapshot.history.asReversed().mapNotNull { snapshot.media[it.mediaId] }.distinctBy { it.id }.take(6)
    }
    LazyColumn(modifier.fillMaxSize().testTag("listen-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(strings.text(TextKey.Listen), description = strings.text(TextKey.Tagline)) }
        if (current != null) item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.text(TextKey.ContinueListening), style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Artwork(current.artworkUrl, current.title, Modifier.size(72.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = current.localArtworkPath)
                        Column(Modifier.weight(1f)) {
                            MetadataText(current.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            MetadataText(current.creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(onClick = {
                        when {
                            playback.currentItem?.id == current.id && showsPause(playback) -> onAction(MusicUiAction.OpenPlayer)
                            playback.currentItem?.id == current.id -> onAction(MusicUiAction.TogglePlayPause)
                            snapshot.queue(snapshot.activeContext).currentEntry != null -> onAction(MusicUiAction.PlayQueueEntry(snapshot.activeContext, snapshot.queue(snapshot.activeContext).currentEntry!!.id))
                            else -> onAction(MusicUiAction.Play(current))
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(VeyraIcons.Play, null, Modifier.padding(end = 8.dp))
                        Text(strings.text(if (playback.currentItem?.id == current.id && showsPause(playback)) TextKey.OpenPlayer else TextKey.Resume))
                    }
                }
            }
        }
        item {
            FlowRow(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { onAction(MusicUiAction.OpenLibrary(LibrarySection.FAVORITES)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Favorites)) }
                OutlinedButton(onClick = { onAction(MusicUiAction.OpenLibrary(LibrarySection.PLAYLISTS)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Playlists)) }
                OutlinedButton(onClick = { onAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Downloads)) }
                if (options.supportsImport) OutlinedButton(onClick = { onAction(MusicUiAction.ImportAudio) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.ImportAudio)) }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(strings.text(TextKey.RecentlyPlayed)) }
            items(recent, key = { "recent-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
        }
        item {
            SectionTitle(strings.text(TextKey.DiscoverMusic))
            Text(strings.text(TextKey.SourceResolutionDescription), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            SourceChoices(discovery.provider, { onAction(MusicUiAction.Discover(it)) }, Modifier.padding(horizontal = 20.dp))
            if (discovery.provider == SourceProvider.COMMONS) Text(strings.text(TextKey.OpenMusicDescription), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        if (snapshot.settings.offlineOnly) item {
            ErrorState(strings.errorFor(AppFailure(FailureCode.OFFLINE_POLICY)), modifier = Modifier.padding(20.dp))
        } else {
            if (discovery.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("discovery-loading")) }
            discovery.failure?.let { failure -> item { ErrorState(strings.errorFor(failure), onRetry = { onAction(MusicUiAction.Discover(discovery.provider)) }, modifier = Modifier.padding(20.dp)) } }
            discovery.page.notice?.let { notice -> item { ErrorState(strings.errorFor(notice), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
            catalogPage(discovery.page, snapshot, options, onAction, "discovery")
            if (!discovery.loading && discovery.failure == null && discovery.page.isEmpty()) item {
                EmptyState(
                    title = strings.text(if (discovery.loaded) TextKey.NoResults else TextKey.DiscoverMusic),
                    description = strings.text(TextKey.SearchDescription),
                    actionLabel = strings.text(TextKey.SearchMusic),
                    onAction = { onAction(MusicUiAction.OpenSearch()) },
                    modifier = Modifier.padding(20.dp),
                )
            }
            if (discovery.page.nextPageToken != null) item {
                TextButton(onClick = { onAction(MusicUiAction.LoadMoreDiscovery) }, enabled = !discovery.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings.text(TextKey.LoadMore)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    snapshot: AppSnapshot,
    state: SearchUiState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    val local = remember(snapshot, state.query, state.kind, options.downloads) {
        libraryMedia(snapshot, options.downloads).filter { matchesSearchKind(it, state.kind) && matchesMediaQuery(it, state.query) }
    }
    val localPlaylists = remember(snapshot.playlists, state.query, state.kind) {
        if (state.kind == SearchKind.PODCASTS) emptyList() else snapshot.playlists.filter { mediaSearchKey(state.query.trim()) in mediaSearchKey(it.name) }
    }
    fun change(query: String = state.query, scope: SearchScope = state.scope, kind: SearchKind = state.kind, provider: SourceProvider = state.provider) {
        onAction(MusicUiAction.SearchChanged(query, scope, kind, provider))
    }
    LazyColumn(modifier.fillMaxSize().testTag("search-screen"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle(strings.text(TextKey.Search))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { change(query = it) },
                    label = { Text(strings.text(TextKey.Search)) },
                    placeholder = { Text(strings.text(TextKey.SearchHint)) },
                    singleLine = true,
                    leadingIcon = { Icon(VeyraIcons.Search, null) },
                    trailingIcon = { if (state.query.isNotEmpty()) ActionIcon(VeyraIcons.Close, strings.text(TextKey.ClearSearch), { change(query = "") }) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onAction(MusicUiAction.RunSearch) }),
                    modifier = Modifier.fillMaxWidth().testTag("search-query"),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchScope.entries.forEach { scope -> FilterChip(selected = state.scope == scope, onClick = { change(scope = scope) }, label = { Text(strings.text(if (scope == SearchScope.EVERYWHERE) TextKey.Everywhere else TextKey.YourLibrary)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchKind.entries.forEach { kind ->
                        FilterChip(selected = state.kind == kind, onClick = {
                            change(kind = kind, provider = if (kind == SearchKind.PODCASTS) SourceProvider.RSS else if (state.provider == SourceProvider.RSS) SourceProvider.YOUTUBE else state.provider)
                        }, label = { Text(strings.text(when (kind) { SearchKind.ALL -> TextKey.All; SearchKind.MUSIC -> TextKey.Music; SearchKind.PODCASTS -> TextKey.Podcasts })) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                if (state.scope == SearchScope.EVERYWHERE && state.kind != SearchKind.PODCASTS) {
                    Text(strings.text(TextKey.SearchSource), style = MaterialTheme.typography.labelLarge)
                    SourceChoices(state.provider, { change(provider = it) })
                }
                if (state.scope == SearchScope.EVERYWHERE) Button(
                    onClick = { onAction(MusicUiAction.RunSearch) },
                    enabled = state.query.isNotBlank() && !state.loading && !snapshot.settings.offlineOnly,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("run-search"),
                ) { Text(strings.text(TextKey.Search)) }
            }
        }
        if (state.query.isBlank() && state.scope == SearchScope.EVERYWHERE) {
            item { EmptyState(strings.text(when (state.kind) { SearchKind.PODCASTS -> TextKey.FindPodcasts; SearchKind.MUSIC -> TextKey.SearchMusic; SearchKind.ALL -> TextKey.Search }), strings.text(TextKey.SearchDescription), modifier = Modifier.padding(20.dp)) }
        } else {
            if (local.isNotEmpty() || localPlaylists.isNotEmpty()) {
                item { SectionTitle(strings.text(TextKey.LocalLibrary)) }
                items(local, key = { "local-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
                items(localPlaylists, key = { "local-playlist-${it.id}" }) { playlist ->
                    TextButton(onClick = { onAction(MusicUiAction.OpenPlaylist(playlist.id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp)) {
                        Icon(VeyraIcons.Library, null, Modifier.padding(end = 12.dp))
                        Column(Modifier.weight(1f)) {
                            MetadataText(playlist.name, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(strings.format(TextKey.TrackCount, playlist.entries.size.toString()), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
                        }
                    }
                }
            } else if (state.scope == SearchScope.LIBRARY) item {
                EmptyState(strings.text(TextKey.NoResults), strings.text(TextKey.NoResultsDescription), strings.text(TextKey.ClearFilters), { change(query = "", kind = SearchKind.ALL) }, Modifier.padding(20.dp))
            }
            if (state.scope == SearchScope.EVERYWHERE) {
                item {
                    SectionTitle(strings.text(TextKey.SearchResults), description = strings.format(TextKey.OriginalMetadataSource, providerName(state.provider)))
                    Text(strings.text(TextKey.SourceResolutionDescription), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot.settings.offlineOnly) item {
                    ErrorState(strings.errorFor(AppFailure(FailureCode.OFFLINE_POLICY)), modifier = Modifier.padding(20.dp))
                } else {
                    if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("search-loading")) }
                    state.failure?.let { failure -> item { ErrorState(strings.errorFor(failure), onRetry = { onAction(MusicUiAction.RunSearch) }, modifier = Modifier.padding(20.dp)) } }
                    state.page.notice?.let { notice -> item { ErrorState(strings.errorFor(notice), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
                    catalogPage(state.page, snapshot, options, onAction, "search")
                    if (!state.loading && state.searched && state.failure == null && state.page.isEmpty()) item {
                        EmptyState(strings.text(TextKey.NoResults), strings.text(TextKey.NoResultsDescription), modifier = Modifier.padding(20.dp))
                    }
                    if (state.page.nextPageToken != null) item {
                        TextButton(onClick = { onAction(MusicUiAction.LoadMoreSearch) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(strings.text(TextKey.LoadMore)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceChoices(selected: SourceProvider, onSelect: (SourceProvider) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SourceProvider.YOUTUBE, SourceProvider.COMMONS).forEach { provider ->
            FilterChip(selected = selected == provider, onClick = { onSelect(provider) }, label = { Text(providerName(provider)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("source-${provider.name}"))
        }
    }
}

private fun SourcePage.isEmpty(): Boolean = items.isEmpty() && shows.isEmpty() && collections.isEmpty()

private fun LazyListScope.catalogPage(page: SourcePage, snapshot: AppSnapshot, options: MusicUiOptions, onAction: (MusicUiAction) -> Unit, prefix: String) {
    page.attribution?.takeIf { it.isNotBlank() }?.let { attribution ->
        item(key = "$prefix-attribution") { MetadataText(attribution, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, paragraph = true) }
    }
    items(page.items.distinctBy { it.id }, key = { "$prefix-media-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
    items(page.shows.distinctBy { it.id }, key = { "$prefix-show-${it.id}" }) { show -> ShowSearchRow(show, snapshot.settings.offlineOnly) { onAction(MusicUiAction.OpenShow(show)) } }
    items(page.collections.distinctBy { it.id }, key = { "$prefix-collection-${it.id}" }) { collection -> CollectionSearchRow(collection, snapshot.settings.offlineOnly) { onAction(MusicUiAction.OpenCollection(collection)) } }
}

@Composable
private fun ShowSearchRow(show: PodcastShow, offlineOnly: Boolean, onClick: () -> Unit) {
    val strings = LocalStrings.current
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 12.dp)) {
        Artwork(show.artworkUrl, show.title, Modifier.size(48.dp), offlineOnly = offlineOnly, localPath = show.localArtworkPath)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MetadataText(show.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetadataText(show.publisher, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(strings.text(TextKey.Podcasts), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CollectionSearchRow(collection: MediaCollection, offlineOnly: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 12.dp)) {
        Artwork(collection.artworkUrl, collection.title, Modifier.size(48.dp), offlineOnly = offlineOnly)
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MetadataText(collection.title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetadataText(collection.creator, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(providerName(collection.source.provider), style = MaterialTheme.typography.labelSmall)
        }
    }
}
