package dev.veyra.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.PlaybackBinding
import dev.veyra.playback.PlaybackPhase
import dev.veyra.playback.PlaybackState
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.EmptyState
import dev.veyra.ui.design.ErrorState
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons

/** Read-only details until an explicit action. Opening a track never starts or replaces audio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackDetailScreen(
    snapshot: AppSnapshot,
    mediaId: String,
    playback: PlaybackState,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    val item = (snapshot.media[mediaId] ?: playback.currentItem?.takeIf { it.id == mediaId })
        ?.takeIf { it.kind == MediaKind.MUSIC }
    var playlistPicker by remember(mediaId) { mutableStateOf(false) }
    Column(modifier.fillMaxSize().testTag("track-detail")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(VeyraIcons.Back, strings.text(TextKey.Back), { onAction(MusicUiAction.NavigateBack) }, Modifier.testTag("track-back"))
            Text(strings.text(TextKey.TrackDetails), Modifier.weight(1f).padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 1_000.dp).fillMaxSize().testTag("track-detail-content"),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (item == null) {
                    item(key = "missing") {
                        EmptyState(strings.text(TextKey.InformationUnavailable), strings.text(TextKey.NotFound), modifier = Modifier.testTag("track-missing"))
                    }
                } else {
                    val presentation = trackDetailPresentation(item, snapshot, playback)
                    val ready = presentation.download.isReady()
                    val current = presentation.isCurrent
                    val failure = options.unavailableItems[item.id] ?: playback.failure.takeIf { current }
                    item(key = "hero") { TrackHero(item, snapshot) }
                    if (snapshot.settings.offlineOnly) item(key = "offline") {
                        Column(Modifier.testTag("track-offline"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.text(TextKey.OfflineMode), style = MaterialTheme.typography.labelLarge)
                            Text(strings.text(TextKey.OfflineDescription), style = MaterialTheme.typography.bodySmall)
                            if (!ready && !current) Text(strings.text(TextKey.NotDownloaded), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (failure != null) item(key = "failure") { ErrorState(strings.errorFor(failure), modifier = Modifier.testTag("track-failure")) }
                    item(key = "actions") {
                        FlowRow(Modifier.fillMaxWidth().testTag("track-actions"), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val playLabel = when {
                                current && showsPause(playback) -> TextKey.Pause
                                current && playback.phase == PlaybackPhase.ERROR -> TextKey.Retry
                                else -> TextKey.Play
                            }
                            Button(
                                { onAction(if (current) MusicUiAction.TogglePlayPause else MusicUiAction.Play(item)) },
                                enabled = current || ready || !snapshot.settings.offlineOnly,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("track-play"),
                            ) { Text(strings.text(playLabel)) }
                            OutlinedButton(
                                { onAction(MusicUiAction.ToggleFavorite(item)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("track-favorite").semantics { selected = item.id in snapshot.favoriteIds },
                            ) { Text(strings.text(savedActionLabel(item, snapshot.isSaved(item)))) }
                            OutlinedButton({ onAction(MusicUiAction.Enqueue(item, playNext = true)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-play-next")) { Text(strings.text(TextKey.PlayNext)) }
                            OutlinedButton({ onAction(MusicUiAction.Enqueue(item, playNext = false)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-enqueue")) { Text(strings.text(TextKey.AddToEnd)) }
                            TextButton({ playlistPicker = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-playlist")) { Text(strings.text(TextKey.AddToPlaylist)) }
                            if (current) TextButton({ onAction(MusicUiAction.OpenPlayer) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.OpenPlayer)) }
                        }
                    }
                    // There can be two different recordings: the currently loaded session and an
                    // older downloaded asset. Their receipts and measured durations stay separate.
                    if (current) item(key = "current-audio") {
                        Column(Modifier.testTag("track-current-audio"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackDuration(TextKey.CurrentAudioDuration, presentation.currentDurationMs)
                            TrackResolution(item, presentation.currentBinding, PlaybackProvenanceContext.CURRENT, snapshot.settings.offlineOnly, onAction)
                        }
                    }
                    if (ready) item(key = "downloaded-audio") {
                        Column(Modifier.testTag("track-downloaded-audio"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TrackDuration(TextKey.DownloadedAudioDuration, presentation.downloadedDurationMs)
                            TrackResolution(item, presentation.downloadedBinding, PlaybackProvenanceContext.DOWNLOADED, snapshot.settings.offlineOnly, onAction)
                        }
                    }
                    presentation.previousBinding?.let { binding -> item(key = "previous-audio") {
                        TrackResolution(item, binding, PlaybackProvenanceContext.PREVIOUS, snapshot.settings.offlineOnly, onAction, Modifier.testTag("track-previous-audio"))
                    } }
                    if (!current && !ready && presentation.previousBinding == null) item(key = "audio-policy") {
                        PlaybackProvenanceCard(item, null)
                    }
                    item(key = "download") { TrackDownloadSection(item, presentation.download, snapshot.settings.offlineOnly, onAction, options) }
                    if (item.description.isNotBlank()) item(key = "description") {
                        Column(Modifier.testTag("track-description"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(strings.text(TextKey.AboutTrack), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(strings.text(TextKey.OriginalDescriptionNotice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MetadataText(item.description, Modifier.fillMaxWidth(), paragraph = true)
                        }
                    }
                    item(key = "attribution") { TrackAttribution(item, snapshot.settings.offlineOnly, onAction) }
                }
            }
        }
    }
    if (playlistPicker && item != null) PlaylistPicker(item, snapshot.playlists, onAction, onDismiss = { playlistPicker = false })
}

@Composable
private fun TrackHero(item: MediaItem, snapshot: AppSnapshot) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val split = maxWidth >= (600f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
        if (split) Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Artwork(item.artworkUrl, item.title, Modifier.size(168.dp), snapshot.settings.offlineOnly, item.localArtworkPath)
            TrackMetadata(item, snapshot, Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(item.artworkUrl, item.title, Modifier.size(128.dp), snapshot.settings.offlineOnly, item.localArtworkPath)
            TrackMetadata(item, snapshot, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TrackMetadata(item: MediaItem, snapshot: AppSnapshot, modifier: Modifier) {
    val strings = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.text(TextKey.RequestedTitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetadataText(item.title, Modifier.fillMaxWidth().testTag("track-title").semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (item.creator.isNotBlank()) MetadataText(item.creator, Modifier.fillMaxWidth().testTag("track-creator"), style = MaterialTheme.typography.titleMedium)
        val artists = (item.artistIds.mapNotNull { snapshot.artists[it]?.name } + listOfNotNull(item.artist))
            .filter { it.isNotBlank() && it != item.creator }.distinct()
        if (artists.isNotEmpty()) {
            Text(strings.text(TextKey.Artist), style = MaterialTheme.typography.labelMedium)
            artists.forEach { MetadataText(it, Modifier.fillMaxWidth()) }
        }
        val album = item.albumId?.let { snapshot.albums[it]?.title } ?: item.album
        if (!album.isNullOrBlank()) {
            Text(strings.text(TextKey.Album), style = MaterialTheme.typography.labelMedium)
            MetadataText(album, Modifier.fillMaxWidth())
        }
        Text(strings.format(TextKey.OriginalMetadataSource, providerName(item.source.provider)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TrackDuration(TextKey.OriginalDuration, item.durationMs?.takeIf { it > 0L })
        if (item.explicit) Text(strings.text(TextKey.Explicit), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TrackDuration(label: TextKey, durationMs: Long?) {
    val strings = LocalStrings.current
    Text(
        strings.format(label, strings.mediaTime(durationMs)),
        modifier = Modifier.semantics { contentDescription = strings.format(label, strings.spokenDuration(durationMs)) },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackDownloadSection(item: MediaItem, record: DownloadRecord?, offline: Boolean, onAction: (MusicUiAction) -> Unit, options: MusicUiOptions) {
    val strings = LocalStrings.current
    val ready = record.isReady()
    val supported = item.source.provider in options.downloadableProviders
    var remove by remember(record?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("track-download"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.text(TextKey.Downloads), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (record != null) {
            Text(
                if (record.status == DownloadStatus.COMPLETE && !ready) strings.text(TextKey.FileUnavailable) else strings.downloadStatus(record.status),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (record.status !in setOf(DownloadStatus.COMPLETE, DownloadStatus.CANCELLED)) {
                val total = record.totalBytes?.takeIf { it > 0L }
                if (total != null) {
                    LinearProgressIndicator(progress = { (record.bytesDownloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(strings.format(TextKey.DownloadProgress, strings.byteSize(record.bytesDownloaded), strings.byteSize(total)), style = MaterialTheme.typography.bodySmall)
                } else {
                    if (record.status in setOf(DownloadStatus.RESOLVING, DownloadStatus.RUNNING, DownloadStatus.VERIFYING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(strings.format(TextKey.DownloadProgressUnknown, strings.byteSize(record.bytesDownloaded)), style = MaterialTheme.typography.bodySmall)
                }
            }
            record.failure?.let { ErrorState(strings.errorFor(it)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (record?.status) {
                DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING -> TextButton({ onAction(MusicUiAction.PauseDownload(record.id)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-download-pause")) { Text(strings.text(TextKey.PauseDownload)) }
                DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED -> TextButton({ onAction(MusicUiAction.ResumeDownload(record.id)) }, enabled = supported && !offline, modifier = Modifier.heightIn(min = 48.dp).testTag("track-download-resume")) { Text(strings.text(if (record.status == DownloadStatus.FAILED) TextKey.Retry else TextKey.ResumeDownload)) }
                DownloadStatus.VERIFYING -> Unit
                DownloadStatus.COMPLETE, DownloadStatus.CANCELLED, null -> if (!ready && supported) OutlinedButton({ onAction(MusicUiAction.Download(item)) }, enabled = !offline, modifier = Modifier.heightIn(min = 48.dp).testTag("track-download-start")) { Text(strings.text(if (record == null) TextKey.Download else TextKey.Retry)) }
            }
            if (record != null && record.status !in setOf(DownloadStatus.COMPLETE, DownloadStatus.CANCELLED)) TextButton({ onAction(MusicUiAction.CancelDownload(record.id)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-download-cancel")) { Text(strings.text(TextKey.CancelDownload)) }
            if (record?.status == DownloadStatus.COMPLETE) TextButton({ remove = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("track-download-remove")) { Text(strings.text(TextKey.RemoveDownload)) }
        }
        if (!supported && !ready) Text(strings.text(TextKey.SourceRightsRequired), style = MaterialTheme.typography.bodySmall)
    }
    if (remove && record != null) ConfirmActionDialog(strings.text(TextKey.RemoveDownload), strings.format(TextKey.ConfirmRemoveDownload, item.title), strings.text(TextKey.RemoveDownload), { remove = false }) {
        onAction(MusicUiAction.RemoveDownload(record.id)); remove = false
    }
}

@Composable
private fun TrackAttribution(item: MediaItem, offline: Boolean, onAction: (MusicUiAction) -> Unit) {
    val strings = LocalStrings.current
    val licenseName = item.attribution?.licenseName?.takeIf { it.isNotBlank() }
    val licensePage = musicSourcePage(item.attribution?.licenseUrl)
    Column(Modifier.fillMaxWidth().testTag("track-attribution"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.text(TextKey.Source), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(strings.format(TextKey.OriginalMetadataSource, providerName(item.source.provider)), style = MaterialTheme.typography.bodyMedium)
        musicSourceHost(item.attribution?.sourceUrl?.takeIf { it.isNotBlank() } ?: item.source.canonicalUrl)?.let { MetadataText(it, style = MaterialTheme.typography.bodySmall) }
        item.attribution?.creator?.takeIf { it.isNotBlank() }?.let { MetadataText(it, Modifier.fillMaxWidth()) }
        if (licenseName != null || licensePage != null) {
            Text(strings.text(TextKey.OriginalListingLicense), style = MaterialTheme.typography.labelLarge)
            licenseName?.let { MetadataText(it, Modifier.fillMaxWidth()) }
        }
        Text(strings.text(TextKey.ResolvedAudioRightsNotice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        musicSourcePage(item.attribution?.sourceUrl, item.source.canonicalUrl)?.let { url ->
            TextButton({ onAction(MusicUiAction.OpenSourcePage(url)) }, enabled = !offline, modifier = Modifier.heightIn(min = 48.dp).testTag("track-source-link")) { Text(strings.text(TextKey.OpenSourcePage)) }
        }
        licensePage?.let { url ->
            TextButton({ onAction(MusicUiAction.OpenSourcePage(url)) }, enabled = !offline, modifier = Modifier.heightIn(min = 48.dp).testTag("track-license-link")) { Text(strings.text(TextKey.License)) }
        }
    }
}

@Composable
private fun TrackResolution(item: MediaItem, binding: PlaybackBinding?, context: PlaybackProvenanceContext, offline: Boolean,
    onAction: (MusicUiAction) -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PlaybackProvenanceCard(item, binding, context = context)
        musicSourcePage(binding?.firstResultUrl)?.let { url ->
            TextButton({ onAction(MusicUiAction.OpenSourcePage(url)) }, enabled = !offline, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.OpenSourcePage)) }
        }
    }
}

internal data class TrackDetailPresentation(
    val isCurrent: Boolean,
    val download: DownloadRecord?,
    val currentBinding: PlaybackBinding?,
    val downloadedBinding: PlaybackBinding?,
    val previousBinding: PlaybackBinding?,
    val currentDurationMs: Long?,
    val downloadedDurationMs: Long?,
)

/** A receipt can describe the current session or a file only through its owner's explicit ID. */
internal fun trackDetailPresentation(item: MediaItem, snapshot: AppSnapshot, playback: PlaybackState): TrackDetailPresentation {
    val current = playback.currentItem?.id == item.id && playback.currentEntryId != null
    val download = snapshot.downloadFor(item.id)
    val currentBinding = playback.executionBinding?.takeIf { current && it.mediaId == item.id }
    val asset = download?.takeIf { it.isReady() }?.asset
    val downloadedBinding = asset?.executionBindingId?.let(snapshot.playbackBindings::get)?.takeIf { it.mediaId == item.id }
    val previous = snapshot.playbackBindings.values
        .filter { it.mediaId == item.id && it.id != currentBinding?.id && it.id != downloadedBinding?.id }
        .maxWithOrNull(compareBy<PlaybackBinding> { it.resolvedEpochMs }.thenBy { it.id })
    return TrackDetailPresentation(current, download, currentBinding, downloadedBinding, previous,
        playback.durationMs?.takeIf { current && it > 0L }, asset?.measuredDurationMs?.takeIf { it > 0L })
}

/** Attribution display only. Never expose a private path, credential, query, or local filename. */
internal fun musicSourceHost(value: String?): String? {
    val url = value?.trim() ?: return null
    if (url.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' }) return null
    val scheme = url.substringBefore("://").lowercase()
    if (scheme !in setOf("https", "http") || "://" !in url) return null
    val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.takeIf { it.isNotBlank() && '@' !in it }
}

/** A presentation gate only; root must still apply DNS/redirect/network policy on activation. */
internal fun musicSourcePage(vararg candidates: String?): String? = candidates.filterNotNull().map(String::trim)
    .firstOrNull { it.startsWith("https://", ignoreCase = true) && musicSourceHost(it) != null }
