package dev.veyra.ui.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.*
import dev.veyra.playback.PlaybackState
import dev.veyra.source.MediaCollection
import dev.veyra.source.SourcePage
import dev.veyra.ui.design.*
import kotlinx.coroutines.delay

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
    LaunchedEffect(discovery.loaded, snapshot.settings.offlineOnly) {
        if (!snapshot.settings.offlineOnly && !discovery.loaded && !discovery.loading) onAction(MusicUiAction.Discover(discovery.provider))
    }
    val current = playback.surfaceIdentity(snapshot)
    val recent = remember(snapshot.history, snapshot.media) {
        snapshot.history.asReversed().mapNotNull { snapshot.media[it.mediaId] }.distinctBy { it.id }.take(8)
    }
    val favorites = remember(snapshot.favoriteIds, snapshot.media) { snapshot.favoriteIds.mapNotNull { snapshot.media[it] }.take(8) }
    LazyColumn(modifier.fillMaxSize().testTag("listen-screen"), contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(strings.text(TextKey.HomeSubtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(strings.text(TextKey.HomeHeadline), style = MaterialTheme.typography.displayLarge)
                SearchShortcut({ onAction(MusicUiAction.OpenSearch()) }, Modifier.testTag("home-search"))
            }
        }
        if (current != null) item {
            Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth().testTag("home-open-player")
                .clickable(onClickLabel = strings.text(TextKey.OpenPlayer)) { onAction(MusicUiAction.OpenPlayer) },
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LocalVeyraColors.current.outlineSubtle)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Artwork(current.artworkUrl, current.title.orEmpty(), Modifier.size(64.dp), snapshot.settings.offlineOnly, current.localArtworkPath)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings.text(TextKey.ContinueListening), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        MetadataText(current.title ?: strings.text(TextKey.InformationUnavailable), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!current.creator.isNullOrBlank()) MetadataText(current.creator!!, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ActionIcon(if (showsPause(playback)) VeyraIcons.Pause else VeyraIcons.Play,
                        strings.text(if (showsPause(playback)) TextKey.Pause else TextKey.Play),
                        { onAction(MusicUiAction.TogglePlayPause) },
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape).testTag("continue-listening"))
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ onAction(MusicUiAction.OpenLibrary(LibrarySection.FAVORITES)) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(VeyraIcons.FavoriteBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(strings.text(TextKey.Favorites))
                }
                OutlinedButton({ onAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(VeyraIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(strings.text(TextKey.Downloads))
                }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionTitle(strings.text(TextKey.JumpBackIn)) }
            item { ArtworkShelf(recent, snapshot, options, onAction) }
        }
        if (favorites.isNotEmpty()) {
            item { SectionTitle(strings.text(TextKey.Favorites)) }
            item { ArtworkShelf(favorites, snapshot, options, onAction) }
        }
        item { SectionTitle(strings.text(TextKey.DiscoverMusic), description = strings.text(TextKey.MadeForYourLibrary)) }
        if (snapshot.settings.offlineOnly) {
            item { CollectionLink(strings.text(TextKey.Downloads), strings.text(TextKey.OfflineModeDescription), VeyraIcons.Download,
                { onAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) }, Modifier.padding(horizontal = 24.dp)) }
        } else {
            if (discovery.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("discovery-loading")) }
            discovery.failure?.let { failure -> item {
                if (failure.code == FailureCode.NOT_FOUND && discovery.page.isEmpty()) {
                    Text(strings.text(TextKey.NoRecommendations), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                } else {
                    ErrorState(strings.errorFor(failure), { onAction(MusicUiAction.Discover(discovery.provider)) }, Modifier.padding(24.dp))
                }
            } }
            discovery.page.notice?.let { notice -> item {
                if (notice.code == FailureCode.NOT_FOUND && discovery.page.isEmpty()) {
                    Text(strings.text(TextKey.NoRecommendations), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
                } else {
                    ErrorState(strings.errorFor(notice), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                }
            } }
            if (discovery.page.items.isNotEmpty()) {
                item { ArtworkShelf(discovery.page.items.distinctBy { it.id }.take(8), snapshot, options, onAction) }
                items(discovery.page.items.distinctBy { it.id }.drop(8), key = { "discovery-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
            }
            items(discovery.page.collections.distinctBy { it.id }, key = { "discovery-collection-${it.id}" }) { collection ->
                CollectionSearchRow(collection, false) { onAction(MusicUiAction.OpenCollection(collection)) }
            }
            if (!discovery.loading && discovery.page.isEmpty()) item {
                CollectionLink(strings.text(TextKey.ExploreMusic), strings.text(TextKey.SearchDescription), VeyraIcons.Search,
                    { onAction(MusicUiAction.OpenSearch()) }, Modifier.padding(horizontal = 24.dp))
            }
            items(discovery.page.shows.distinctBy { it.id }, key = { "discovery-show-${it.id}" }) { show ->
                ShowSearchRow(show, snapshot.settings.offlineOnly) { onAction(MusicUiAction.OpenShow(show)) }
            }
            discovery.page.attribution?.takeIf { it.isNotBlank() }?.let { attribution ->
                item { MetadataText(attribution, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, paragraph = true) }
            }
            if (discovery.page.nextPageToken != null) item {
                TextButton({ onAction(MusicUiAction.LoadMoreDiscovery) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !discovery.loading) {
                    Text(strings.text(TextKey.LoadMore))
                }
            }
        }
    }
}

@Composable
private fun ArtworkShelf(items: List<MediaItem>, snapshot: AppSnapshot, options: MusicUiOptions, onAction: (MusicUiAction) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.id }) { item ->
            Surface(onClick = {
                val binding = options.downloadSurface(snapshot).savedCatalogueBinding(item.id)
                onAction(if (binding == null) MusicUiAction.Play(item) else MusicUiAction.PlaySaved(item, binding))
            }, modifier = Modifier.width(152.dp).testTag("shelf-${item.id}"), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box {
                        Artwork(item.artworkUrl, item.title, Modifier.fillMaxWidth().aspectRatio(1f), snapshot.settings.offlineOnly, item.localArtworkPath)
                        Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(30.dp).background(Color.Black.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(VeyraIcons.Play, null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                    MetadataText(item.title, Modifier.fillMaxWidth().padding(horizontal = 3.dp), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.creator.isNotBlank()) MetadataText(item.creator, Modifier.fillMaxWidth().padding(start = 3.dp, end = 3.dp, bottom = 5.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canSearch = state.query.trim().length >= 2 && !state.loading &&
        state.scope == SearchScope.EVERYWHERE && !snapshot.settings.offlineOnly
    fun submitSearch() {
        focusManager.clearFocus()
        keyboard?.hide()
        onAction(MusicUiAction.RunSearch)
    }
    var filtersOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val latestState by rememberUpdatedState(state)
    val latestAction by rememberUpdatedState(onAction)
    LaunchedEffect(state.query, state.scope, state.kind, state.provider, snapshot.settings.offlineOnly) {
        if (state.query.trim().length >= 2 && state.scope == SearchScope.EVERYWHERE && !snapshot.settings.offlineOnly) {
            delay(650)
            if (!latestState.loading && !latestState.searched) latestAction(MusicUiAction.RunSearch)
        }
    }
    val local = remember(snapshot, state.query, state.kind, options.downloads) {
        libraryMedia(snapshot, options.downloads).filter { matchesSearchKind(it, state.kind) && matchesMediaQuery(it, state.query) }
    }
    val localPlaylists = remember(snapshot.playlists, state.query, state.kind) {
        if (state.kind == SearchKind.PODCASTS) emptyList() else snapshot.playlists.filter { mediaSearchKey(state.query.trim()) in mediaSearchKey(it.name) }
    }
    fun change(query: String = state.query, scope: SearchScope = state.scope, kind: SearchKind = state.kind, provider: SourceProvider = state.provider) {
        onAction(MusicUiAction.SearchChanged(query, scope, kind, provider))
    }
    LazyColumn(modifier.fillMaxSize().testTag("search-screen"), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenIntro(strings.text(TextKey.Search), strings.text(TextKey.SearchSubtitle))
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    state.query, { change(query = it) }, placeholder = { Text(strings.text(TextKey.SearchHint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, shape = CircleShape,
                    leadingIcon = { Icon(VeyraIcons.Search, null, Modifier.size(21.dp)) },
                    trailingIcon = { if (state.query.isNotEmpty()) ActionIcon(VeyraIcons.Close, strings.text(TextKey.ClearSearch), { change(query = "") }) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = LocalVeyraColors.current.outlineSubtle,
                        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus).testTag("search-query"),
                )
                ActionIcon(VeyraIcons.Search, strings.text(TextKey.Search), { submitSearch() },
                    Modifier.testTag("run-search"), enabled = canSearch)
                ActionIcon(VeyraIcons.FilterList, strings.text(TextKey.SearchOptions), { filtersOpen = !filtersOpen },
                    Modifier.testTag("search-options"), isSelected = filtersOpen)
            }
            FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.scope == SearchScope.EVERYWHERE && state.kind != SearchKind.PODCASTS,
                    { change(scope = SearchScope.EVERYWHERE, kind = SearchKind.MUSIC, provider = if (state.provider == SourceProvider.RSS) SourceProvider.YOUTUBE else state.provider) },
                    { Text(strings.text(TextKey.Music)) }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(state.scope == SearchScope.EVERYWHERE && state.kind == SearchKind.PODCASTS,
                    { change(scope = SearchScope.EVERYWHERE, kind = SearchKind.PODCASTS, provider = SourceProvider.RSS) },
                    { Text(strings.text(TextKey.Podcasts)) }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(state.scope == SearchScope.LIBRARY, { change(scope = SearchScope.LIBRARY) },
                    { Text(strings.text(TextKey.YourLibrary)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (filtersOpen) Surface(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text(TextKey.SearchSource), style = MaterialTheme.typography.labelLarge)
                    SourceChoices(state.provider, { change(kind = SearchKind.MUSIC, provider = it, scope = SearchScope.EVERYWHERE) })
                    TextButton({ change(kind = SearchKind.ALL) }, Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.All)) }
                    TextButton({ submitSearch(); filtersOpen = false }, Modifier.heightIn(min = 48.dp).testTag("search-submit"), enabled = canSearch) { Text(strings.text(TextKey.Search)) }
                }
            }
        }
        if (state.scope == SearchScope.LIBRARY || state.query.isNotBlank() && local.isNotEmpty()) {
            item { SectionTitle(strings.text(TextKey.YourLibrary)) }
            items(local, key = { "local-${it.id}" }) { MusicMediaRow(it, snapshot, onAction, options = options) }
            items(localPlaylists, key = { "local-playlist-${it.id}" }) { playlist ->
                CollectionLink(playlist.name, strings.format(TextKey.TrackCount, strings.number(playlist.entries.size.toLong())), VeyraIcons.Library,
                    { onAction(MusicUiAction.OpenPlaylist(playlist.id)) }, Modifier.padding(horizontal = 24.dp))
            }
            if (state.scope == SearchScope.LIBRARY && local.isEmpty() && localPlaylists.isEmpty()) item {
                EmptyState(strings.text(TextKey.NoResults), strings.text(TextKey.NoResultsDescription), modifier = Modifier.padding(24.dp))
            }
        }
        if (state.scope == SearchScope.EVERYWHERE) {
            if (snapshot.settings.offlineOnly) item {
                EmptyState(strings.text(TextKey.Offline), strings.text(TextKey.OfflineModeDescription), strings.text(TextKey.Downloads),
                    { onAction(MusicUiAction.OpenLibrary(LibrarySection.DOWNLOADS)) }, Modifier.padding(24.dp))
            } else {
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("search-loading")) }
                state.failure?.let { failure -> item { ErrorState(strings.errorFor(failure), { onAction(MusicUiAction.RunSearch) }, Modifier.padding(24.dp)) } }
                state.page.notice?.let { notice -> item { ErrorState(strings.errorFor(notice), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) } }
                if (state.searched || state.page.items.isNotEmpty()) item { SectionTitle(strings.text(TextKey.SearchResults)) }
                catalogPage(state.page, snapshot, options, onAction, "search")
                if (!state.loading && state.searched && state.failure == null && state.page.isEmpty()) item {
                    EmptyState(strings.text(TextKey.NoResults), strings.text(TextKey.NoResultsDescription), modifier = Modifier.padding(24.dp))
                }
                if (!state.loading && !state.searched && state.query.isBlank()) item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CollectionLink(strings.text(TextKey.ExploreMusic), strings.text(TextKey.MadeForYourLibrary), VeyraIcons.Music,
                            { change(query = "", kind = SearchKind.MUSIC); filtersOpen = false; searchFocus.requestFocus() })
                        CollectionLink(strings.text(TextKey.Podcasts), strings.text(TextKey.PodcastsSubtitle), VeyraIcons.Podcasts,
                            { change(kind = SearchKind.PODCASTS, provider = SourceProvider.RSS); searchFocus.requestFocus() })
                    }
                }
                if (state.page.nextPageToken != null) item {
                    TextButton({ onAction(MusicUiAction.LoadMoreSearch) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.loading) { Text(strings.text(TextKey.LoadMore)) }
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
