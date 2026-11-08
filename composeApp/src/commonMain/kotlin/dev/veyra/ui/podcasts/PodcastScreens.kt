@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.veyra.ui.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppFailure
import dev.veyra.model.FailureCode
import dev.veyra.model.MediaKind
import dev.veyra.model.PodcastShow
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LoadingState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons
import dev.veyra.ui.design.displayMetadata
import dev.veyra.ui.design.isolateMetadata

/** Dedicated podcast hub. Discovery navigates to shared Search, rather than owning another client. */
@Composable
fun PodcastsScreen(
    state: PodcastsUiState,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val continuing = remember(state.snapshot.media, state.snapshot.progress, state.progressView) { state.continueEpisodes() }
    val newEpisodes = remember(state.snapshot.media, state.snapshot.shows, state.snapshot.progress,
        state.snapshot.followedShowIds, state.progressView) { state.newEpisodes() }
    val following = remember(state.snapshot.shows, state.snapshot.followedShowIds) { state.followedShows() }
    var showAllContinuing by rememberSaveable { mutableStateOf(false) }
    var showAllNew by rememberSaveable { mutableStateOf(false) }
    var showAllFollowing by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize().testTag("podcasts-screen"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.text(TextKey.Podcasts), style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() })
                    Text(strings.text(TextKey.FollowLocalDescription), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(PodcastAction.FindPodcasts) }, modifier = Modifier.testTag("podcasts-find")) {
                            Icon(VeyraIcons.Search, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.text(TextKey.FindPodcasts))
                        }
                        OutlinedButton(onClick = { onAction(PodcastAction.OpenAddFeed) }, enabled = !state.snapshot.settings.offlineOnly,
                            modifier = Modifier.testTag("podcasts-add-feed")) {
                            Icon(VeyraIcons.Add, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.text(TextKey.AddRssFeed))
                        }
                        if (following.isNotEmpty()) {
                            TextButton(onClick = { onAction(PodcastAction.RefreshShows) },
                                enabled = !state.isRefreshing && !state.snapshot.settings.offlineOnly) {
                                Icon(VeyraIcons.Refresh, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.text(TextKey.Refresh))
                            }
                        }
                    }
                }
            }
            item(key = "audio-policy") { PodcastAudioResolutionNotice() }
            if (state.snapshot.settings.offlineOnly) item(key = "offline") { PodcastOfflineNotice() }
            if (state.isLoading || state.isRefreshing) {
                item(key = "loading") { PodcastLoadingIndicator() }
            }
            state.failure?.let { failure ->
                item(key = "failure") {
                    PodcastRefreshFailure(failure, hasCachedContent = following.isNotEmpty() || continuing.isNotEmpty(),
                        onRetry = { onAction(PodcastAction.RefreshShows) }.takeUnless { state.snapshot.settings.offlineOnly })
                }
            }
            if (continuing.isNotEmpty()) {
                item(key = "continue-heading") {
                    PodcastSectionHeading(strings.text(TextKey.ContinueListening),
                        description = strings.text(TextKey.ContinueListeningDescription),
                        toggleLabel = if (continuing.size > 3) strings.text(if (showAllContinuing) TextKey.ShowLess else TextKey.ShowMore) else null,
                        onToggle = { showAllContinuing = !showAllContinuing })
                }
                items(if (showAllContinuing) continuing else continuing.take(3), key = { "continue-${it.id}" }) { episode ->
                    PodcastEpisodeRow(state.snapshot.podcastEpisodeUiState(episode, downloadSurface = state.downloads, progressView = state.progressView), onAction)
                }
            }
            if (following.isNotEmpty()) {
                item(key = "new-heading") {
                    PodcastSectionHeading(strings.text(TextKey.NewEpisodes),
                        toggleLabel = if (newEpisodes.size > 5) strings.text(if (showAllNew) TextKey.ShowLess else TextKey.ShowMore) else null,
                        onToggle = { showAllNew = !showAllNew })
                }
                if (newEpisodes.isEmpty() && !state.isLoading && !state.isRefreshing && state.failure == null) {
                    item(key = "no-new") {
                        EmptyState(strings.text(TextKey.NoNewEpisodes), strings.text(TextKey.NoNewEpisodesDescription))
                    }
                } else {
                    items(if (showAllNew) newEpisodes else newEpisodes.take(5), key = { "new-${it.id}" }) { episode ->
                        PodcastEpisodeRow(state.snapshot.podcastEpisodeUiState(episode, downloadSurface = state.downloads, progressView = state.progressView), onAction)
                    }
                }
                item(key = "following-heading") {
                    PodcastSectionHeading(strings.text(TextKey.Following),
                        description = strings.format(TextKey.ShowCount, following.size.toString()),
                        toggleLabel = if (following.size > 6) strings.text(if (showAllFollowing) TextKey.ShowLess else TextKey.ShowMore) else null,
                        onToggle = { showAllFollowing = !showAllFollowing })
                }
                items(if (showAllFollowing) following else following.take(6), key = { "show-${it.id}" }) { show ->
                    PodcastShowRow(show, followed = true, offlineOnly = state.snapshot.settings.offlineOnly, onAction = onAction)
                }
            } else if (!state.isLoading && !state.isRefreshing && state.failure == null) {
                item(key = "no-followed-shows") {
                    EmptyState(
                        title = strings.text(TextKey.NoFollowedShows),
                        description = strings.text(TextKey.NoFollowedShowsDescription),
                        actionLabel = strings.text(TextKey.FindPodcasts),
                        onAction = { onAction(PodcastAction.FindPodcasts) },
                        modifier = Modifier.testTag("podcasts-empty"),
                    )
                }
            }
        }
    }
    state.addFeed?.let { addFeed ->
        AddPodcastFeedDialog(addFeed, state.snapshot.settings.offlineOnly, onAction)
    }
}

@Composable
fun PodcastShowScreen(
    state: PodcastShowUiState,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val show = state.show
    val episodes = remember(state.showId, state.snapshot.media, state.snapshot.progress, state.snapshot.downloads, state.downloads, state.options, state.progressView) {
        state.visibleEpisodes()
    }
    val allCount = remember(state.showId, state.snapshot.media) {
        state.snapshot.media.values.count { it.kind == MediaKind.EPISODE && it.showId == state.showId }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize().testTag("podcast-show-screen"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "back") { PodcastBackButton { onAction(PodcastAction.NavigateBack) } }
            if (show == null) {
                item(key = "missing-show") {
                    if (state.isLoading || state.isRefreshing) PodcastLoadingIndicator() else ErrorState(
                        strings.errorFor(state.failure ?: AppFailure(FailureCode.NOT_FOUND)),
                        onRetry = { onAction(PodcastAction.RefreshShow(state.showId)) }.takeUnless { state.snapshot.settings.offlineOnly },
                    )
                }
            } else {
                item(key = "show-header") {
                    PodcastHero(show.title, show.publisher, show.artworkUrl, state.snapshot.settings.offlineOnly,
                        localArtworkPath = show.localArtworkPath)
                }
                item(key = "show-actions") {
                    val followed = show.id in state.snapshot.followedShowIds
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(PodcastAction.SetFollowed(show.id, !followed)) },
                            modifier = Modifier.testTag("podcast-follow")) {
                            Icon(if (followed) VeyraIcons.Check else VeyraIcons.Add, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.text(if (followed) TextKey.Unfollow else TextKey.Follow))
                        }
                        OutlinedButton(onClick = { onAction(PodcastAction.RefreshShow(show.id)) },
                            enabled = !state.isRefreshing && !state.snapshot.settings.offlineOnly) {
                            Icon(VeyraIcons.Refresh, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.text(TextKey.Refresh))
                        }
                        podcastPublisherUrl(show)?.let { url ->
                            TextButton(onClick = { onAction(PodcastAction.OpenPublisher(url)) },
                                enabled = !state.snapshot.settings.offlineOnly) {
                                Icon(VeyraIcons.OpenInNew, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.text(TextKey.OpenPublisher))
                            }
                        }
                    }
                }
                item(key = "audio-policy") { PodcastAudioResolutionNotice() }
                if (state.snapshot.settings.offlineOnly) item(key = "offline") { PodcastOfflineNotice() }
                if (state.isLoading || state.isRefreshing) item(key = "refreshing") { PodcastLoadingIndicator() }
                state.failure?.let { failure ->
                    item(key = "refresh-error") {
                        PodcastRefreshFailure(failure, hasCachedContent = allCount > 0,
                            onRetry = { onAction(PodcastAction.RefreshShow(show.id)) }.takeUnless { state.snapshot.settings.offlineOnly })
                    }
                }
                item(key = "show-source") {
                    PodcastSourceInformation(show, state.snapshot.settings.offlineOnly, onAction)
                }
                if (show.description.isNotBlank()) item(key = "description") {
                    PodcastExpandableText(show.description, strings.text(TextKey.ShowDescription), show.id)
                }
                item(key = "episode-heading") {
                    PodcastSectionHeading(strings.text(TextKey.Episodes),
                        description = strings.format(TextKey.EpisodeCount, episodes.size.toString()))
                }
                item(key = "episode-filters") { PodcastEpisodeFilters(state, onAction) }
                if (episodes.isEmpty() && !state.isLoading && !state.isRefreshing) {
                    item(key = "empty-episodes") {
                        if (allCount == 0) {
                            if (state.failure == null) EmptyState(strings.text(TextKey.NoEpisodes), strings.text(TextKey.NoEpisodesDescription))
                        } else {
                            EmptyState(strings.text(TextKey.NoMatchingEpisodes), strings.text(TextKey.NoResultsDescription),
                                actionLabel = strings.text(TextKey.ClearFilters),
                                onAction = { onAction(PodcastAction.SetShowListOptions(show.id, PodcastListOptions(sort = state.options.sort))) })
                        }
                    }
                } else {
                    items(episodes, key = { it.id }) { episode ->
                        PodcastEpisodeRow(state.snapshot.podcastEpisodeUiState(episode, downloadSurface = state.downloads, progressView = state.progressView), onAction, showPublisher = false)
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeDetailScreen(
    state: EpisodeDetailUiState,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val episode = state.episode
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxSize().testTag("podcast-episode-detail"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "back") { PodcastBackButton { onAction(PodcastAction.NavigateBack) } }
            if (episode == null) {
                item(key = "missing-episode") {
                    if (state.isLoading) PodcastLoadingIndicator() else ErrorState(
                        strings.errorFor(state.failure ?: AppFailure(FailureCode.NOT_FOUND)),
                    )
                }
            } else {
                val row = state.snapshot.podcastEpisodeUiState(episode, state.currentBinding, state.downloads, state.progressView)
                item(key = "episode-hero") {
                    PodcastHero(episode.title, row.show?.title ?: episode.creator,
                        episode.artworkUrl ?: row.show?.artworkUrl, state.snapshot.settings.offlineOnly,
                        localArtworkPath = episode.localArtworkPath?.takeIf(String::isNotBlank) ?: row.show?.localArtworkPath)
                }
                row.show?.let { show ->
                    item(key = "show-link") {
                        TextButton(onClick = { onAction(PodcastAction.OpenShow(show.id)) }) {
                            Icon(VeyraIcons.Podcasts, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(strings.text(TextKey.ShowDetails))
                        }
                    }
                }
                if (state.snapshot.settings.offlineOnly) item(key = "offline") { PodcastOfflineNotice() }
                state.failure?.let { failure -> item(key = "failure") { ErrorState(strings.errorFor(failure)) } }
                item(key = "episode-metadata") { PodcastEpisodeMetadata(row) }
                item(key = "episode-status") { PodcastEpisodeStatus(row) }
                item(key = "audio-policy") {
                    if (row.binding == null) PodcastAudioResolutionNotice() else PodcastEpisodeProvenance(row, onAction)
                }
                if (row.resumeActionable) item(key = "episode-progress") {
                    Column(modifier = Modifier.testTag("podcast-progress-current"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.format(TextKey.ResumeAt, strings.mediaTime(row.positionMs)),
                            modifier = Modifier.testTag("podcast-progress-resume"), style = MaterialTheme.typography.titleMedium)
                        podcastProgressFraction(row.positionMs, row.progressDurationMs)?.let { fraction ->
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()
                                .testTag("podcast-listening-progress-${row.episode.id}"))
                        }
                        podcastRemainingMs(row.positionMs, row.progressDurationMs)?.let { remaining ->
                            // Keep semantic and painted paragraphs within the same content-sized width.
                            Text(strings.format(TextKey.MediaRemaining, strings.spokenDuration(remaining)),
                                modifier = Modifier.width(IntrinsicSize.Max).testTag("podcast-progress-remaining"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (podcastHistoricalProgressNoticeKey(row) != null) item(key = "historical-progress") {
                    PodcastHistoricalProgressNotice(row)
                }
                item(key = "episode-actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PodcastEpisodeControls(row, onAction)
                        podcastUnavailablePlaybackNotice(row)?.let { notice ->
                            Text(strings.text(notice),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        row.activeDownload?.let { PodcastDownloadProgress(it) }
                        row.activeDownload?.failure?.let { failure -> ErrorState(strings.errorFor(failure)) }
                    }
                }
                if (episode.description.isNotBlank()) item(key = "episode-notes") {
                    PodcastExpandableText(episode.description, strings.text(TextKey.EpisodeNotes), episode.id,
                        initialLines = 12, disclaimer = strings.text(TextKey.OriginalDescriptionNotice))
                }
                if (episode.attribution != null || row.show?.attribution != null || row.show?.copyright != null ||
                    podcastPublisherUrl(row) != null) item(key = "episode-source") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val attribution = episode.attribution ?: row.show?.attribution
                        podcastPublisherUrl(row)?.let { url ->
                            podcastPublicHost(url)?.let { host -> Text(strings.format(TextKey.OriginalMetadataSource, host),
                                style = MaterialTheme.typography.bodySmall) }
                        }
                        attribution?.creator?.takeIf(String::isNotBlank)?.let {
                            MetadataText(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        row.show?.copyright?.takeIf(String::isNotBlank)?.let {
                            MetadataText(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PodcastLicenseInformation(attribution?.licenseName, attribution?.licenseUrl,
                            row.offlineOnly, onAction, hasCopyright = !row.show?.copyright.isNullOrBlank())
                        podcastPublisherUrl(row)?.let { url ->
                            TextButton(onClick = { onAction(PodcastAction.OpenPublisher(url)) }, enabled = !row.offlineOnly) {
                                Icon(VeyraIcons.OpenInNew, null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(strings.text(TextKey.OpenPublisher))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PodcastShowRow(
    show: PodcastShow,
    followed: Boolean,
    offlineOnly: Boolean,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Card(
        onClick = { onAction(PodcastAction.OpenShow(show.id)) },
        modifier = modifier.fillMaxWidth().testTag("podcast-show-${show.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top) {
            Artwork(show.artworkUrl, show.title, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), offlineOnly,
                localPath = show.localArtworkPath)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MetadataText(show.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (show.publisher.isNotBlank()) MetadataText(show.publisher, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                podcastPublicHost(show.feedUrl)?.let { host -> Text(strings.format(TextKey.OriginalMetadataSource, host),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (followed) PodcastStatusLabel(strings.text(TextKey.Following))
            }
        }
    }
}

@Composable
private fun PodcastEpisodeFilters(state: PodcastShowUiState, onAction: (PodcastAction) -> Unit) {
    val strings = LocalStrings.current
    val focusManager = LocalFocusManager.current
    var sortExpanded by remember(state.showId) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.options.query,
            onValueChange = { onAction(PodcastAction.SetShowListOptions(state.showId, state.options.copy(query = it))) },
            modifier = Modifier.fillMaxWidth().testTag("podcast-episode-search"),
            label = { Text(strings.text(TextKey.SearchEpisodes)) },
            leadingIcon = { Icon(VeyraIcons.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PodcastEpisodeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.options.filter == filter,
                    onClick = { onAction(PodcastAction.SetShowListOptions(state.showId, state.options.copy(filter = filter))) },
                    label = { Text(strings.text(filter.textKey())) },
                    modifier = Modifier.testTag("podcast-filter-${filter.name}"),
                )
            }
            Box {
                OutlinedButton(onClick = { sortExpanded = true }, modifier = Modifier.testTag("podcast-sort")) {
                    Icon(VeyraIcons.Sort, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.text(state.options.sort.textKey()))
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    PodcastEpisodeSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(strings.text(sort.textKey())) },
                            leadingIcon = { if (state.options.sort == sort) Icon(VeyraIcons.Check, null) },
                            onClick = {
                                sortExpanded = false
                                onAction(PodcastAction.SetShowListOptions(state.showId, state.options.copy(sort = sort)))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastHero(title: String, subtitle: String, artworkUrl: String?, offlineOnly: Boolean,
    localArtworkPath: String?) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomy = maxWidth >= 600.dp && density.fontScale <= 1.5f
        if (roomy) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(artworkUrl, title, Modifier.size(144.dp).clip(RoundedCornerShape(28.dp)), offlineOnly,
                    localPath = localArtworkPath)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PodcastHeroTitle(title, subtitle)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Artwork(artworkUrl, title, Modifier.size(112.dp).clip(RoundedCornerShape(24.dp)), offlineOnly,
                    localPath = localArtworkPath)
                PodcastHeroTitle(title, subtitle)
            }
        }
    }
}

@Composable
private fun PodcastHeroTitle(title: String, subtitle: String) {
    // Keep the semantic paragraph within the same content-sized width as the painted title.
    MetadataText(title, style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.width(IntrinsicSize.Max).semantics { heading() })
    if (subtitle.isNotBlank()) MetadataText(subtitle, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PodcastSourceInformation(show: PodcastShow, offline: Boolean, onAction: (PodcastAction) -> Unit) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        podcastPublicHost(show.feedUrl)?.let { host ->
            Text(strings.format(TextKey.OriginalMetadataSource, host), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        show.copyright?.takeIf(String::isNotBlank)?.let {
            MetadataText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        show.attribution?.creator?.takeIf { it.isNotBlank() && it != show.publisher }?.let {
            MetadataText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PodcastLicenseInformation(show.attribution?.licenseName, show.attribution?.licenseUrl, offline, onAction,
            hasCopyright = !show.copyright.isNullOrBlank())
        podcastPublishedDate(show.lastRefreshEpochMs)?.let { date ->
            Text(strings.format(TextKey.LastUpdated, strings.localizeDigits(date)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            show.language?.takeIf(String::isNotBlank)?.let { PodcastStatusLabel(isolateMetadata(it)) }
            if (show.explicit) PodcastStatusLabel(strings.text(TextKey.Explicit))
        }
    }
}

@Composable
private fun PodcastLicenseInformation(licenseName: String?, licenseUrl: String?, offline: Boolean,
    onAction: (PodcastAction) -> Unit, hasCopyright: Boolean = false) {
    val strings = LocalStrings.current
    if (!licenseName.isNullOrBlank() || licenseUrl != null && podcastPublicHost(licenseUrl) != null) {
        Text(strings.text(TextKey.OriginalListingLicense), style = MaterialTheme.typography.labelMedium)
    }
    licenseName?.takeIf(String::isNotBlank)?.let {
        MetadataText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (licenseUrl != null && podcastPublicHost(licenseUrl) != null) {
        TextButton(onClick = { onAction(PodcastAction.OpenPublisher(licenseUrl)) }, enabled = !offline) {
            Icon(VeyraIcons.OpenInNew, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.text(TextKey.License))
        }
    }
    if (hasCopyright || !licenseName.isNullOrBlank() || licenseUrl != null && podcastPublicHost(licenseUrl) != null) {
        Text(strings.text(TextKey.ResolvedAudioRightsNotice), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PodcastExpandableText(value: String, title: String, key: String, initialLines: Int = 5, disclaimer: String? = null) {
    val strings = LocalStrings.current
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var canExpand by remember(key, value) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        disclaimer?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Text, never a WebView: publisher prose cannot execute markup, scripts or external loads.
        Text(
            displayMetadata(value),
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            textAlign = TextAlign.Start,
            maxLines = if (expanded) Int.MAX_VALUE else initialLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) canExpand = it.hasVisualOverflow },
        )
        if (canExpand || expanded) TextButton(onClick = { expanded = !expanded }) {
            Text(strings.text(if (expanded) TextKey.ShowLess else TextKey.ShowMore))
        }
    }
}

@Composable
private fun PodcastSectionHeading(title: String, description: String? = null, toggleLabel: String? = null,
    onToggle: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (toggleLabel != null && onToggle != null) TextButton(onClick = onToggle) { Text(toggleLabel) }
        }
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PodcastBackButton(onBack: () -> Unit) {
    val strings = LocalStrings.current
    TextButton(onClick = onBack) {
        Icon(VeyraIcons.ArrowBack, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(strings.text(TextKey.Back))
    }
}

@Composable
private fun PodcastOfflineNotice() {
    val strings = LocalStrings.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("podcast-offline-notice")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strings.text(TextKey.Offline), style = MaterialTheme.typography.titleSmall)
            Text(strings.text(TextKey.OfflineDescription), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PodcastAudioResolutionNotice() {
    val strings = LocalStrings.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("podcast-audio-policy")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(strings.text(TextKey.AudioSource), style = MaterialTheme.typography.titleSmall)
            Text(strings.text(TextKey.SourceResolutionDescription), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PodcastLoadingIndicator() {
    LoadingState(modifier = Modifier.testTag("podcast-loading"))
}

@Composable
private fun PodcastRefreshFailure(failure: AppFailure, hasCachedContent: Boolean, onRetry: (() -> Unit)?) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        ErrorState(strings.errorFor(failure), onRetry)
        if (hasCachedContent) Text(strings.text(TextKey.StaleFeed), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddPodcastFeedDialog(state: AddPodcastFeedUiState, offline: Boolean, onAction: (PodcastAction) -> Unit) {
    val strings = LocalStrings.current
    val canSubmit = state.url.isNotBlank() && !state.isSubmitting && !offline
    val failureMessage = state.failure?.let(strings::errorFor)
    AlertDialog(
        onDismissRequest = { onAction(PodcastAction.DismissAddFeed) },
        modifier = Modifier.testTag("podcast-add-feed-dialog"),
        title = { Text(strings.text(TextKey.AddRssFeed)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(TextKey.AddFeedDescription))
                OutlinedTextField(
                    value = state.url,
                    onValueChange = { onAction(PodcastAction.FeedUrlChanged(it)) },
                    enabled = !state.isSubmitting,
                    modifier = Modifier.fillMaxWidth().testTag("podcast-feed-url").semantics {
                        failureMessage?.let { error(it) }
                    },
                    label = { Text(strings.text(TextKey.FeedUrl)) },
                    placeholder = { Text(strings.text(TextKey.FeedUrlHint)) },
                    isError = state.failure != null,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) onAction(PodcastAction.SubmitFeed(state.url.trim())) }),
                )
                Text(strings.text(TextKey.FeedPrivateWarning), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (offline) Text(strings.text(TextKey.OfflineDescription))
                failureMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("podcast-feed-error").semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(PodcastAction.SubmitFeed(state.url.trim())) }, enabled = canSubmit,
                modifier = Modifier.testTag("podcast-submit-feed")) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(strings.text(if (state.isSubmitting) TextKey.Loading else TextKey.AddFeed))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(PodcastAction.DismissAddFeed) }) { Text(strings.text(TextKey.Cancel)) }
        },
    )
}

private fun PodcastEpisodeFilter.textKey(): TextKey = when (this) {
    PodcastEpisodeFilter.ALL -> TextKey.AllEpisodes
    PodcastEpisodeFilter.UNPLAYED -> TextKey.Unplayed
    PodcastEpisodeFilter.IN_PROGRESS -> TextKey.InProgress
    PodcastEpisodeFilter.PLAYED -> TextKey.Played
    PodcastEpisodeFilter.DOWNLOADED -> TextKey.Downloaded
}

private fun PodcastEpisodeSort.textKey(): TextKey = when (this) {
    PodcastEpisodeSort.NEWEST_FIRST -> TextKey.NewestFirst
    PodcastEpisodeSort.OLDEST_FIRST -> TextKey.OldestFirst
}
