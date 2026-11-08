package dev.veyra.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.veyra.model.AppSnapshot
import dev.veyra.model.DownloadRecord
import dev.veyra.model.DownloadStatus
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.model.SourceProvider
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.AppStrings
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIconButton
import dev.veyra.ui.design.VeyraIcons
import dev.veyra.ui.downloads.DownloadSurface
import dev.veyra.ui.downloads.DownloadSurfaceOrigin
import dev.veyra.ui.downloads.DownloadSurfaceEntry
import dev.veyra.ui.downloads.legacyDownloadSurface

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier, description: String? = null) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ActionIcon(
    image: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isSelected: Boolean? = null,
    stateLabel: String? = null,
) {
    VeyraIconButton(
        icon = image,
        label = label,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        selected = isSelected,
        stateLabel = stateLabel,
    )
}

@Composable
internal fun providerName(provider: SourceProvider): String {
    val strings = LocalStrings.current
    return strings.text(when (provider) {
        SourceProvider.YOUTUBE -> TextKey.YouTube
        SourceProvider.COMMONS -> TextKey.OpenMusic
        SourceProvider.RSS -> TextKey.Podcasts
        SourceProvider.LOCAL -> TextKey.LocalFiles
    })
}

internal fun AppSnapshot.downloadFor(mediaId: String): DownloadRecord? = downloads.values.firstOrNull { it.mediaId == mediaId }
internal fun DownloadRecord?.isReady(): Boolean = this != null && status == DownloadStatus.COMPLETE &&
    asset?.let { it.mediaId == mediaId && it.sizeBytes > 0L && it.uri.isNotBlank() } == true
/** A materialized file awaiting a session integrity check is not an active transfer or Ready. */
internal fun DownloadRecord?.isRecheckingAsset(): Boolean = this != null && asset != null && !cleanupPending &&
    status in setOf(DownloadStatus.VERIFYING, DownloadStatus.NEEDS_FOREGROUND)
internal fun MusicUiOptions.downloadSurface(snapshot: AppSnapshot): DownloadSurface =
    downloads ?: legacyDownloadSurface(snapshot)
internal fun DownloadSurfaceEntry.statusText(strings: AppStrings): String = when {
    status == DownloadStatus.COMPLETE && !ready && origin != DownloadSurfaceOrigin.TYPED_SOURCE ->
        strings.text(TextKey.FileUnavailable)
    else -> strings.downloadStatus(status)
}
internal fun AppSnapshot.isSaved(item: MediaItem): Boolean =
    if (item.kind == MediaKind.EPISODE) item.id in savedEpisodeIds else item.id in favoriteIds

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicMediaRow(
    item: MediaItem,
    snapshot: AppSnapshot,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
    isCurrent: Boolean = false,
    showFavorite: Boolean = false,
) {
    val strings = LocalStrings.current
    val surface = options.downloadSurface(snapshot)
    val download = surface.forMedia(item.id)
    val ready = surface.isDownloaded(item.id)
    val unavailable = options.unavailableItems[item.id]
    Surface(modifier = modifier.fillMaxWidth().testTag("media-row-${item.id}"), color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 72.dp)) {
                Artwork(item.artworkUrl, item.title, Modifier.size(48.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = item.localArtworkPath)
                TextButton(
                    onClick = { onAction(MusicUiAction.Play(item)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("play-${item.id}"),
                    // Multiline metadata must not inherit a pill clip at its corner lines.
                    shape = RectangleShape,
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MetadataText(item.title, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.creator.isNotBlank()) MetadataText(item.creator, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(providerName(item.source.provider), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            item.durationMs?.takeIf { it >= 0 }?.let { Text(strings.mediaTime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (ready) Icon(VeyraIcons.Check, strings.text(TextKey.ReadyOffline), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        if (download != null && !download.ready && download.status != DownloadStatus.CANCELLED) Text(
                            download.statusText(strings),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showFavorite) ActionIcon(
                    savedIcon(item, snapshot.isSaved(item)),
                    strings.text(savedActionLabel(item, snapshot.isSaved(item))),
                    { onAction(MusicUiAction.ToggleFavorite(item)) },
                    isSelected = snapshot.isSaved(item),
                )
                MediaActionsMenu(item, snapshot, onAction, options = options)
            }
            unavailable?.let {
                Text(strings.errorFor(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 56.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
internal fun MediaActionsMenu(
    item: MediaItem,
    snapshot: AppSnapshot,
    onAction: (MusicUiAction) -> Unit,
    modifier: Modifier = Modifier,
    options: MusicUiOptions = MusicUiOptions(),
) {
    val strings = LocalStrings.current
    var menu by remember(item.id) { mutableStateOf(false) }
    var picker by remember(item.id) { mutableStateOf(false) }
    var removeDownload by remember(item.id) { mutableStateOf(false) }
    val surface = options.downloadSurface(snapshot)
    val download = surface.forMedia(item.id)
    Box(modifier) {
        ActionIcon(VeyraIcons.MoreOptions, strings.format(TextKey.MoreActionsFor, item.title), { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            fun dispatch(action: MusicUiAction) { menu = false; onAction(action) }
            DropdownMenuItem(text = { Text(strings.text(TextKey.PlayNext)) }, onClick = { dispatch(MusicUiAction.Enqueue(item, playNext = true)) })
            DropdownMenuItem(text = { Text(strings.text(TextKey.AddToEnd)) }, onClick = { dispatch(MusicUiAction.Enqueue(item, playNext = false)) })
            DropdownMenuItem(text = { Text(strings.text(savedActionLabel(item, snapshot.isSaved(item)))) }, onClick = { dispatch(MusicUiAction.ToggleFavorite(item)) })
            if (item.kind == MediaKind.MUSIC) DropdownMenuItem(text = { Text(strings.text(TextKey.AddToPlaylist)) }, onClick = { menu = false; picker = true })
            if (download != null && (download.ready || download.checking)) {
                if (download.checking) DropdownMenuItem(text = { Text(strings.downloadStatus(download.status)) }, onClick = {},
                    modifier = Modifier.testTag("menu-download-status-${item.id}"), enabled = false)
                DropdownMenuItem(text = { Text(strings.text(TextKey.RemoveDownload)) }, onClick = { menu = false; removeDownload = true })
            } else if (download != null && download.status !in setOf(DownloadStatus.CANCELLED, DownloadStatus.COMPLETE)) {
                when (download.status) {
                    DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING -> DropdownMenuItem(text = { Text(strings.text(TextKey.PauseDownload)) }, onClick = { dispatch(MusicUiAction.PauseDownload(download.id)) })
                    DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND, DownloadStatus.FAILED -> DropdownMenuItem(text = { Text(strings.text(if (download.status == DownloadStatus.FAILED) TextKey.Retry else TextKey.ResumeDownload)) }, onClick = { dispatch(MusicUiAction.ResumeDownload(download.id)) })
                    DownloadStatus.VERIFYING -> DropdownMenuItem(text = { Text(strings.text(TextKey.DownloadVerifying)) }, onClick = {}, enabled = false)
                    DownloadStatus.COMPLETE, DownloadStatus.CANCELLED -> Unit
                }
                DropdownMenuItem(text = { Text(strings.text(TextKey.CancelDownload)) }, onClick = { dispatch(MusicUiAction.CancelDownload(download.id)) })
            } else if (item.source.provider in options.downloadableProviders) {
                DropdownMenuItem(text = { Text(strings.text(TextKey.Download)) }, onClick = { dispatch(MusicUiAction.Download(item)) })
            }
            DropdownMenuItem(text = { Text(strings.text(TextKey.Details)) }, onClick = { dispatch(MusicUiAction.OpenMedia(item)) })
        }
    }
    if (picker) PlaylistPicker(item, snapshot.playlists, onAction, onDismiss = { picker = false })
    if (removeDownload && download != null) ConfirmActionDialog(
        title = strings.text(TextKey.RemoveDownload),
        description = strings.format(TextKey.ConfirmRemoveDownload, item.title),
        confirm = strings.text(TextKey.RemoveDownload),
        onDismiss = { removeDownload = false },
        onConfirm = { removeDownload = false; onAction(MusicUiAction.RemoveDownload(download.id)) },
    )
}

internal fun savedActionLabel(item: MediaItem, saved: Boolean): TextKey = when {
    item.kind == MediaKind.EPISODE -> if (saved) TextKey.Unsave else TextKey.SaveForLater
    saved -> TextKey.Unfavorite
    else -> TextKey.Favorite
}

internal fun savedIcon(item: MediaItem, saved: Boolean): ImageVector = when {
    item.kind == MediaKind.EPISODE -> if (saved) VeyraIcons.Bookmark else VeyraIcons.BookmarkBorder
    saved -> VeyraIcons.Favorite
    else -> VeyraIcons.FavoriteBorder
}

@Composable
internal fun ConfirmActionDialog(title: String, description: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-action")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-action")) { Text(strings.text(TextKey.Cancel)) } },
    )
}

@Composable
internal fun PlaylistNameDialog(initialName: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val strings = LocalStrings.current
    var name by remember(initialName) { mutableStateOf(initialName) }
    val valid = validatedPlaylistName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(if (initialName.isBlank()) TextKey.CreatePlaylist else TextKey.RenamePlaylist)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(strings.text(TextKey.PlaylistName)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("playlist-name"))
        },
        confirmButton = {
            TextButton(onClick = { valid?.let(onSave) }, enabled = valid != null, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist-save")) {
                Text(strings.text(if (initialName.isBlank()) TextKey.CreatePlaylist else TextKey.Rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
    )
}

@Composable
internal fun PlaylistPicker(item: MediaItem, playlists: List<Playlist>, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var creating by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Playlist?>(null) }
    if (creating) {
        PlaylistNameDialog(onDismiss = { creating = false }, onSave = {
            onAction(MusicUiAction.CreatePlaylist(it, item)); onDismiss()
        })
    } else if (duplicate != null) {
        val playlist = duplicate!!
        ConfirmActionDialog(strings.text(TextKey.DuplicateTrack), isolateMetadata(item.title), strings.text(TextKey.AddAgain), { duplicate = null }) {
            onAction(MusicUiAction.AddToPlaylist(playlist.id, item, allowDuplicate = true)); onDismiss()
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.text(TextKey.AddToPlaylist)) },
            text = {
                Column {
                    if (playlists.isEmpty()) Text(strings.text(TextKey.NoPlaylists), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(onClick = {
                                if (playlist.entries.any { it.mediaId == item.id }) duplicate = playlist
                                else { onAction(MusicUiAction.AddToPlaylist(playlist.id, item)); onDismiss() }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                MetadataText(playlist.name, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.NewPlaylist)) } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
        )
    }
}
