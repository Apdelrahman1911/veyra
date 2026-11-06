@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.veyra.ui.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIconButton
import dev.veyra.ui.design.VeyraIcons
import dev.veyra.ui.design.VeyraSpacing
import dev.veyra.ui.music.PlaybackProvenanceCard
import dev.veyra.ui.music.PlaybackProvenanceContext

/** A listening record, not another player. All actions route to the app's one coordinator. */
@Composable
fun PodcastEpisodeRow(
    state: PodcastEpisodeUiState,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
    showPublisher: Boolean = true,
) {
    val strings = LocalStrings.current
    Card(
        onClick = { onAction(PodcastAction.OpenEpisode(state.episode.id)) },
        modifier = modifier.fillMaxWidth().testTag("podcast-episode-${state.episode.id}"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Artwork(
                    url = state.episode.artworkUrl ?: state.show?.artworkUrl,
                    title = state.episode.title,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                    offlineOnly = state.offlineOnly,
                    localPath = state.episode.localArtworkPath?.takeIf(String::isNotBlank) ?: state.show?.localArtworkPath,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    MetadataText(
                        state.episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showPublisher) {
                        val publisher = state.show?.title?.takeIf(String::isNotBlank)
                            ?: state.episode.creator.takeIf(String::isNotBlank)
                        publisher?.let {
                            MetadataText(it, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // Full-width metadata and actions remain readable beside long titles at 200% text.
            PodcastEpisodeMetadata(state)
            PodcastEpisodeStatus(state)
            if (state.binding != null) PodcastEpisodeProvenance(state, onAction, compact = true)
            if (state.canResume) {
                podcastProgressFraction(state.positionMs, state.progressDurationMs)?.let { fraction ->
                    val remaining = strings.format(TextKey.MediaRemaining,
                        strings.spokenDuration(podcastRemainingMs(state.positionMs, state.progressDurationMs)))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().testTag("podcast-listening-progress-${state.episode.id}")
                            .semantics { contentDescription = remaining },
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onAction(PodcastAction.PlayEpisode(state.episode.id)) },
                    enabled = state.canPlay,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("podcast-play-${state.episode.id}").semantics {
                            contentDescription = strings.format(TextKey.PlayItem, state.episode.title)
                            if (state.canResume) stateDescription = strings.format(TextKey.ResumeAt, strings.spokenDuration(state.positionMs))
                            else podcastHistoricalProgressNoticeKey(state)?.let {
                                stateDescription = strings.format(it, strings.spokenDuration(state.historicalPositionMs))
                            }
                        },
                ) {
                    Icon(VeyraIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(podcastPlayLabel(state, strings))
                }
                PodcastEpisodeMenu(state, onAction)
            }
            if (!state.canPlay) {
                Text(
                    strings.text(if (state.offlineOnly && state.hasAudioSource) TextKey.NotDownloaded else TextKey.EpisodeUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun PodcastEpisodeProvenance(state: PodcastEpisodeUiState, onAction: (PodcastAction) -> Unit,
    compact: Boolean = false) {
    val strings = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PlaybackProvenanceCard(
            item = state.episode,
            binding = state.binding,
            compact = compact,
            context = when (state.bindingContext) {
                PodcastBindingContext.CURRENT -> PlaybackProvenanceContext.CURRENT
                PodcastBindingContext.DOWNLOADED -> PlaybackProvenanceContext.DOWNLOADED
                PodcastBindingContext.PREVIOUS -> PlaybackProvenanceContext.PREVIOUS
            },
        )
        if (!compact) state.binding?.firstResultUrl?.takeIf { podcastPublicHost(it) != null }?.let { url ->
            TextButton(onClick = { onAction(PodcastAction.OpenPublisher(url)) }, enabled = !state.offlineOnly) {
                Icon(VeyraIcons.OpenInNew, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(strings.text(TextKey.OpenSourcePage))
            }
        }
    }
}

@Composable
internal fun PodcastEpisodeMetadata(state: PodcastEpisodeUiState, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        podcastPublishedDate(state.episode.publishedEpochMs)?.let { date ->
            Text(strings.format(TextKey.PublishedOn, strings.localizeDigits(date)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(strings.format(TextKey.OriginalDuration, strings.mediaTime(state.listingDurationMs)),
            modifier = Modifier.semantics {
                contentDescription = strings.format(TextKey.OriginalDuration, strings.spokenDuration(state.listingDurationMs))
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.episode.explicit) PodcastStatusLabel(strings.text(TextKey.Explicit))
    }
}

@Composable
internal fun PodcastEpisodeStatus(state: PodcastEpisodeUiState, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            state.played -> PodcastStatusLabel(strings.text(TextKey.Played))
            state.inProgress -> PodcastStatusLabel(strings.text(TextKey.InProgress))
            state.historicalPositionMs == 0L -> PodcastStatusLabel(strings.text(TextKey.NewEpisode))
        }
        if (state.saved) PodcastStatusLabel(strings.text(TextKey.SavedForLater))
        if (state.queued) PodcastStatusLabel(strings.text(TextKey.InQueue))
        if (state.downloaded) {
            PodcastStatusLabel(strings.text(TextKey.Downloaded))
        } else {
            state.download?.takeIf { it.status != DownloadStatus.COMPLETE && it.status != DownloadStatus.CANCELLED }?.let {
                PodcastStatusLabel(strings.downloadStatus(it.status))
            }
        }
        if (state.noLongerInFeed) PodcastStatusLabel(strings.text(TextKey.NoLongerInFeed))
    }
}

@Composable
internal fun PodcastStatusLabel(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PodcastEpisodeMenu(
    state: PodcastEpisodeUiState,
    onAction: (PodcastAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var expanded by remember(state.episode.id) { mutableStateOf(false) }
    var confirmStartOver by remember(state.episode.id) { mutableStateOf(false) }
    var confirmRemoveDownload by remember(state.episode.id) { mutableStateOf(false) }
    fun dispatch(action: PodcastAction) {
        expanded = false
        onAction(action)
    }
    Box(modifier) {
        VeyraIconButton(
            icon = VeyraIcons.MoreVert,
            label = strings.format(TextKey.MoreActionsFor, state.episode.title),
            onClick = { expanded = true },
            modifier = Modifier.testTag("podcast-more-${state.episode.id}"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(strings.text(TextKey.EpisodeDetails)) },
                onClick = { dispatch(PodcastAction.OpenEpisode(state.episode.id)) },
            )
            state.show?.let { show ->
                DropdownMenuItem(text = { Text(strings.text(TextKey.ShowDetails)) },
                    onClick = { dispatch(PodcastAction.OpenShow(show.id)) })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(strings.text(if (state.saved) TextKey.Unsave else TextKey.SaveForLater)) },
                leadingIcon = { Icon(if (state.saved) VeyraIcons.Bookmark else VeyraIcons.BookmarkBorder, null) },
                onClick = { dispatch(PodcastAction.SetSaved(state.episode.id, !state.saved)) },
            )
            DropdownMenuItem(
                text = { Text(strings.text(if (state.played) TextKey.MarkUnplayed else TextKey.MarkPlayed)) },
                onClick = { dispatch(PodcastAction.SetPlayed(state.episode.id, !state.played)) },
            )
            if (state.historicalPositionMs > 0L || state.played) {
                DropdownMenuItem(
                    text = { Text(strings.text(TextKey.StartOver)) },
                    enabled = state.canPlay,
                    onClick = { expanded = false; confirmStartOver = true },
                )
            }
            HorizontalDivider()
            if (state.queued) {
                DropdownMenuItem(text = { Text(strings.text(TextKey.ShowQueue)) },
                    onClick = { dispatch(PodcastAction.OpenQueue) })
            } else {
                DropdownMenuItem(text = { Text(strings.text(TextKey.PlayNext)) },
                    onClick = { dispatch(PodcastAction.PlayNext(state.episode.id)) })
                DropdownMenuItem(text = { Text(strings.text(TextKey.AddToEnd)) },
                    onClick = { dispatch(PodcastAction.AddToEnd(state.episode.id)) })
            }
            HorizontalDivider()
            val downloadAction = podcastDownloadAction(state)
            listOfNotNull(downloadAction, podcastRetainedAssetRemovalAction(state)).forEachIndexed { index, action ->
                DropdownMenuItem(
                    text = { Text(strings.text(action.label)) },
                    enabled = action.enabled,
                    leadingIcon = { Icon(VeyraIcons.Download, null) },
                    modifier = Modifier.testTag(if (index == 0) "podcast-menu-download" else "podcast-menu-remove-download"),
                    onClick = {
                        expanded = false
                        if (action.requiresRemovalConfirmation) confirmRemoveDownload = true
                        else action.action?.let(onAction)
                    },
                )
            }
            state.download?.takeIf(::podcastCanCancelDownload)?.let { download ->
                if (downloadAction.action !is PodcastAction.CancelDownload) {
                    DropdownMenuItem(text = { Text(strings.text(TextKey.CancelDownload)) },
                        onClick = { dispatch(PodcastAction.CancelDownload(download.id)) })
                }
            }
            podcastPublisherUrl(state)?.let { url ->
                DropdownMenuItem(
                    text = { Text(strings.text(TextKey.OpenPublisher)) },
                    leadingIcon = { Icon(VeyraIcons.OpenInNew, null) },
                    enabled = !state.offlineOnly,
                    onClick = { dispatch(PodcastAction.OpenPublisher(url)) },
                )
            }
        }
    }
    if (confirmStartOver) {
        PodcastActionConfirmation(
            title = strings.text(TextKey.StartOver),
            description = strings.text(TextKey.StartOverConfirm),
            onDismiss = { confirmStartOver = false },
            onConfirm = { confirmStartOver = false; onAction(PodcastAction.StartOver(state.episode.id)) },
        )
    }
    if (confirmRemoveDownload) {
        PodcastActionConfirmation(
            title = strings.text(TextKey.RemoveDownload),
            description = strings.text(TextKey.RemoveDownloadConfirm),
            onDismiss = { confirmRemoveDownload = false },
            onConfirm = {
                confirmRemoveDownload = false
                state.download?.let { onAction(PodcastAction.RemoveDownload(it.id)) }
            },
        )
    }
}

@Composable
internal fun PodcastEpisodeControls(state: PodcastEpisodeUiState, onAction: (PodcastAction) -> Unit) {
    val strings = LocalStrings.current
    var confirmStartOver by remember(state.episode.id) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onAction(PodcastAction.PlayEpisode(state.episode.id)) }, enabled = state.canPlay,
            modifier = Modifier.podcastActionTarget().testTag("podcast-detail-play").semantics {
                contentDescription = strings.format(TextKey.PlayItem, state.episode.title)
                if (state.canResume) stateDescription = strings.format(TextKey.ResumeAt, strings.spokenDuration(state.positionMs))
                else podcastHistoricalProgressNoticeKey(state)?.let {
                    stateDescription = strings.format(it, strings.spokenDuration(state.historicalPositionMs))
                }
            }) {
            Icon(VeyraIcons.PlayArrow, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(podcastPlayLabel(state, strings))
        }
        PodcastDownloadButton(state, onAction)
        OutlinedButton(onClick = { onAction(PodcastAction.SetSaved(state.episode.id, !state.saved)) },
            modifier = Modifier.podcastActionTarget().testTag("podcast-detail-save")) {
            Icon(if (state.saved) VeyraIcons.Bookmark else VeyraIcons.BookmarkBorder, null,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.text(if (state.saved) TextKey.Unsave else TextKey.SaveForLater))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.queued) {
            TextButton(onClick = { onAction(PodcastAction.OpenQueue) },
                modifier = Modifier.podcastActionTarget().testTag("podcast-detail-show-queue")) { Text(strings.text(TextKey.ShowQueue)) }
        } else {
            TextButton(onClick = { onAction(PodcastAction.PlayNext(state.episode.id)) },
                modifier = Modifier.podcastActionTarget().testTag("podcast-detail-play-next")) { Text(strings.text(TextKey.PlayNext)) }
            TextButton(onClick = { onAction(PodcastAction.AddToEnd(state.episode.id)) },
                modifier = Modifier.podcastActionTarget().testTag("podcast-detail-add-to-end")) { Text(strings.text(TextKey.AddToEnd)) }
        }
        TextButton(onClick = { onAction(PodcastAction.SetPlayed(state.episode.id, !state.played)) },
            modifier = Modifier.podcastActionTarget().testTag("podcast-detail-mark-played")) {
            Text(strings.text(if (state.played) TextKey.MarkUnplayed else TextKey.MarkPlayed))
        }
        if (state.historicalPositionMs > 0L || state.played) {
            TextButton(onClick = { confirmStartOver = true }, enabled = state.canPlay,
                modifier = Modifier.podcastActionTarget().testTag("podcast-detail-start-over")) { Text(strings.text(TextKey.StartOver)) }
        }
    }
    if (confirmStartOver) {
        PodcastActionConfirmation(strings.text(TextKey.StartOver), strings.text(TextKey.StartOverConfirm),
            onDismiss = { confirmStartOver = false },
            onConfirm = { confirmStartOver = false; onAction(PodcastAction.StartOver(state.episode.id)) })
    }
}

@Composable
internal fun PodcastDownloadButton(state: PodcastEpisodeUiState, onAction: (PodcastAction) -> Unit) {
    val strings = LocalStrings.current
    val action = podcastDownloadAction(state)
    var confirmRemoval by remember(state.episode.id) { mutableStateOf(false) }
    listOfNotNull(action, podcastRetainedAssetRemovalAction(state)).forEachIndexed { index, offered ->
        OutlinedButton(onClick = {
            if (offered.requiresRemovalConfirmation) confirmRemoval = true else offered.action?.let(onAction)
        }, enabled = offered.enabled, modifier = Modifier.podcastActionTarget().testTag(
            if (index == 0) "podcast-detail-download" else "podcast-detail-remove-download")) {
            Icon(if (state.downloaded) VeyraIcons.Check else VeyraIcons.Download, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(strings.text(offered.label))
        }
    }
    if (confirmRemoval) {
        PodcastActionConfirmation(strings.text(TextKey.RemoveDownload), strings.text(TextKey.RemoveDownloadConfirm),
            onDismiss = { confirmRemoval = false },
            onConfirm = {
                confirmRemoval = false
                state.download?.let { onAction(PodcastAction.RemoveDownload(it.id)) }
            })
    }
}

/** The byte counter is download progress, not proof that a staged file is ready offline. */
@Composable
internal fun PodcastDownloadProgress(download: DownloadRecord, modifier: Modifier = Modifier) {
    if (download.status !in cancellableDownloadStatuses && download.status != DownloadStatus.FAILED) return
    val strings = LocalStrings.current
    val downloadedBytes = download.bytesDownloaded.coerceAtLeast(0L)
    val total = download.totalBytes?.takeIf { it > 0L }
    val label = if (total != null) strings.format(TextKey.DownloadProgress,
        strings.byteSize(downloadedBytes), strings.byteSize(total))
    else strings.format(TextKey.DownloadProgressUnknown, strings.byteSize(downloadedBytes))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (total != null) {
            LinearProgressIndicator(
                progress = { (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = strings.downloadStatus(download.status) },
            )
        }
    }
}

@Composable
internal fun PodcastActionConfirmation(title: String, description: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("podcast-action-confirmation"),
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.podcastActionTarget().testTag("podcast-action-confirm")) {
                Text(strings.text(TextKey.Confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.podcastActionTarget().testTag("podcast-action-dismiss")) {
                Text(strings.text(TextKey.Cancel))
            }
        },
    )
}

// Minimum interactive geometry, not fixed height: long localized labels can still wrap and grow.
private fun Modifier.podcastActionTarget(): Modifier =
    sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget)

internal fun podcastPlayLabel(state: PodcastEpisodeUiState, strings: AppStrings): String = when {
    state.canResume -> strings.format(TextKey.ResumeAt, strings.mediaTime(state.positionMs))
    state.progressRecording != PodcastProgressRecording.MATCHED -> strings.text(TextKey.Play)
    state.played -> strings.text(TextKey.Replay)
    else -> strings.text(TextKey.Play)
}

internal fun podcastHistoricalProgressNoticeKey(state: PodcastEpisodeUiState): TextKey? =
    if (state.historicalPositionMs <= 0L) null else when (state.progressRecording) {
        PodcastProgressRecording.MATCHED -> null
        PodcastProgressRecording.CHANGED -> TextKey.PodcastProgressRecordingChanged
        PodcastProgressRecording.UNVERIFIED -> TextKey.PodcastProgressRecordingUnverified
    }

@Composable
internal fun PodcastHistoricalProgressNotice(state: PodcastEpisodeUiState) {
    val key = podcastHistoricalProgressNoticeKey(state) ?: return
    val strings = LocalStrings.current
    Text(strings.format(key, strings.mediaTime(state.historicalPositionMs)),
        modifier = Modifier.fillMaxWidth().testTag("podcast-progress-recording-notice-${state.episode.id}").semantics {
            contentDescription = strings.format(key, strings.spokenDuration(state.historicalPositionMs))
        },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal data class PodcastDownloadAction(
    val label: TextKey,
    val action: PodcastAction?,
    val enabled: Boolean = true,
    val requiresRemovalConfirmation: Boolean = false,
)

internal val cancellableDownloadStatuses = setOf(
    DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.PAUSED,
    DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.VERIFYING,
)

// A committed file may be projected as checking/restricted until the current host validates it.
// Cancel/resume of the durable COMPLETE record are no-ops; file ownership is not proof of Ready.
private fun DownloadRecord.isCheckingExistingAsset(): Boolean = asset != null && !cleanupPending &&
    status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND)

internal fun podcastCanCancelDownload(download: DownloadRecord): Boolean =
    download.status in cancellableDownloadStatuses && !download.isCheckingExistingAsset()

internal fun podcastRetainedAssetRemovalAction(state: PodcastEpisodeUiState): PodcastDownloadAction? {
    // Failed validation can retain owned bytes. Removing those bytes is a separate local action,
    // not a retry, and must remain available offline through the coordinator's confirmed cleanup.
    val download = state.download?.takeIf {
        it.asset != null && !it.cleanupPending &&
            it.status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED)
    } ?: return null
    return PodcastDownloadAction(TextKey.RemoveDownload, PodcastAction.RemoveDownload(download.id),
        requiresRemovalConfirmation = true)
}

internal fun podcastDownloadAction(state: PodcastEpisodeUiState): PodcastDownloadAction {
    fun newDownload() = PodcastDownloadAction(TextKey.Download, PodcastAction.Download(state.episode.id),
        enabled = !state.offlineOnly && state.hasAudioSource)
    val download = state.download ?: return newDownload()
    if (state.downloaded) {
        return PodcastDownloadAction(TextKey.RemoveDownload, PodcastAction.RemoveDownload(download.id),
            requiresRemovalConfirmation = true)
    }
    if (download.isCheckingExistingAsset()) {
        return PodcastDownloadAction(
            if (download.status == DownloadStatus.VERIFYING) TextKey.DownloadVerifying else TextKey.DownloadNeedsForeground,
            action = null, enabled = false)
    }
    return when (download.status) {
        DownloadStatus.RUNNING -> PodcastDownloadAction(TextKey.PauseDownload, PodcastAction.PauseDownload(download.id))
        DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.WAITING_NETWORK, DownloadStatus.VERIFYING ->
            PodcastDownloadAction(TextKey.CancelDownload, PodcastAction.CancelDownload(download.id))
        DownloadStatus.PAUSED, DownloadStatus.NEEDS_FOREGROUND ->
            PodcastDownloadAction(TextKey.ResumeDownload, PodcastAction.ResumeDownload(download.id), enabled = !state.offlineOnly)
        DownloadStatus.FAILED ->
            PodcastDownloadAction(TextKey.Retry, PodcastAction.ResumeDownload(download.id), enabled = !state.offlineOnly)
        DownloadStatus.CANCELLED, DownloadStatus.COMPLETE -> newDownload()
    }
}
