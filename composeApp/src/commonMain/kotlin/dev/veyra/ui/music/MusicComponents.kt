package dev.veyra.ui.music

import androidx.compose.foundation.combinedClickable
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
import dev.veyra.ui.design.NativeMenu
import dev.veyra.ui.design.displayMetadata
import dev.veyra.nativecomponents.NativeAlert
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosAction
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosProperties
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosStyle
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

/** Exact typed catalogue R1 from a currently verified ready row; never a title/media lookup. */
internal fun DownloadSurface?.savedCatalogueBinding(mediaId: String): String? =
    this?.unambiguousCatalogueBinding(mediaId)
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
    val savedBinding = surface.savedCatalogueBinding(item.id)
    val unavailable = options.unavailableItems[item.id]
    val menu = remember(item.id) { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("media-row-${item.id}"), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 72.dp)) {
                Artwork(item.artworkUrl, item.title, Modifier.size(56.dp), offlineOnly = snapshot.settings.offlineOnly, localPath = item.localArtworkPath)
                // Tap plays; long-press (or a secondary click) opens the same actions as the ⋮ menu.
                Box(
                    Modifier.weight(1f).heightIn(min = 56.dp)
                        .combinedClickable(
                            role = androidx.compose.ui.semantics.Role.Button,
                            onLongClickLabel = strings.format(TextKey.MoreActionsFor, item.title),
                            onLongClick = { menu.value = true },
                            onClick = {
                                onAction(if (savedBinding != null) MusicUiAction.PlaySaved(item, savedBinding)
                                else MusicUiAction.Play(item))
                            },
                        ).padding(horizontal = 12.dp, vertical = 8.dp).testTag("play-${item.id}"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MetadataText(item.title, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (item.creator.isNotBlank()) MetadataText(item.creator, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                MediaActionsMenu(item, snapshot, onAction, options = options, menuState = menu)
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
    /** Lets the owning row open this menu, e.g. on long-press. */
    menuState: androidx.compose.runtime.MutableState<Boolean>? = null,
) {
    val strings = LocalStrings.current
    val ownMenu = remember(item.id) { mutableStateOf(false) }
    var menu by (menuState ?: ownMenu)
    var picker by remember(item.id) { mutableStateOf(false) }
    var removeDownload by remember(item.id) { mutableStateOf(false) }
    val surface = options.downloadSurface(snapshot)
    val download = surface.forMedia(item.id)
    Box(modifier) {
        ActionIcon(VeyraIcons.MoreOptions, strings.format(TextKey.MoreActionsFor, item.title), { menu = true })
        NativeMenu(expanded = menu, onDismiss = { menu = false }) {
            fun dispatch(action: MusicUiAction) { menu = false; onAction(action) }
            val saved = snapshot.isSaved(item)
            item(strings.text(TextKey.PlayNext), symbol = "text.line.first.and.arrowtriangle.forward") { dispatch(MusicUiAction.Enqueue(item, playNext = true)) }
            item(strings.text(TextKey.AddToEnd), symbol = "text.line.last.and.arrowtriangle.forward") { dispatch(MusicUiAction.Enqueue(item, playNext = false)) }
            divider()
            item(strings.text(savedActionLabel(item, saved)), symbol = savedSymbol(item, saved)) { dispatch(MusicUiAction.ToggleFavorite(item)) }
            if (item.kind == MediaKind.MUSIC) item(strings.text(TextKey.AddToPlaylist), symbol = "text.badge.plus") { menu = false; picker = true }
            divider()
            if (download != null && (download.ready || download.checking)) {
                if (download.checking) item(strings.downloadStatus(download.status), tag = "menu-download-status-${item.id}", enabled = false) {}
                item(strings.text(TextKey.RemoveDownload), destructive = true, symbol = "trash") { menu = false; removeDownload = true }
            } else if (download != null && download.status !in setOf(DownloadStatus.CANCELLED, DownloadStatus.COMPLETE)) {
                val retainedFailure = download.status == DownloadStatus.FAILED && download.hasOwnedBytes &&
                    !download.cleanupPending && download.origin != DownloadSurfaceOrigin.LEGACY
                when (download.status) {
                    DownloadStatus.QUEUED, DownloadStatus.RESOLVING, DownloadStatus.RUNNING ->
                        item(strings.text(TextKey.PauseDownload), symbol = "pause.circle") { dispatch(MusicUiAction.PauseDownload(download.id)) }
                    DownloadStatus.PAUSED, DownloadStatus.WAITING_NETWORK, DownloadStatus.NEEDS_FOREGROUND ->
                        item(strings.text(TextKey.ResumeDownload), symbol = "arrow.down.circle") { dispatch(MusicUiAction.ResumeDownload(download.id)) }
                    DownloadStatus.FAILED -> if (retainedFailure) {
                        val target = download.retainedValidation
                        val choices = download.retainedValidationChoices
                        if (target != null) item(strings.text(TextKey.ValidateDownload), tag = "menu-validate-${item.id}", symbol = "checkmark.shield") {
                            dispatch(MusicUiAction.ValidateRetainedDownload(target.jobId,
                                target.bindingId, target.batchId, target.memberPosition,
                                target.expectedIntentRevision))
                        } else if (choices.isNotEmpty()) choices.forEachIndexed { index, choice ->
                            item(strings.format(TextKey.ValidateDownloadMember, strings.number((index + 1).toLong())),
                                tag = "menu-validate-member-${item.id}-${choice.memberPosition}", symbol = "checkmark.shield") {
                                dispatch(MusicUiAction.ValidateRetainedDownload(choice.jobId,
                                    choice.bindingId, choice.batchId, choice.memberPosition,
                                    choice.expectedIntentRevision))
                            }
                        } else item(strings.text(TextKey.RetainedValidationUnavailable), tag = "menu-validate-unavailable-${item.id}", enabled = false) {}
                        item(strings.text(TextKey.RemoveDownload), destructive = true, symbol = "trash") { menu = false; removeDownload = true }
                    } else item(strings.text(TextKey.Retry), symbol = "arrow.clockwise") { dispatch(MusicUiAction.ResumeDownload(download.id)) }
                    DownloadStatus.VERIFYING -> item(strings.text(TextKey.DownloadVerifying), enabled = false) {}
                    DownloadStatus.COMPLETE, DownloadStatus.CANCELLED -> Unit
                }
                if (!retainedFailure) item(strings.text(TextKey.CancelDownload), symbol = "xmark.circle") { dispatch(MusicUiAction.CancelDownload(download.id)) }
            } else if (item.source.provider in options.downloadableProviders) {
                item(strings.text(TextKey.Download), symbol = "arrow.down.circle") { dispatch(MusicUiAction.Download(item)) }
            }
            divider()
            item(strings.text(TextKey.Details), symbol = "info.circle") { dispatch(MusicUiAction.OpenMedia(item)) }
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

/** SF Symbol matching [savedIcon] for native iOS menus. */
internal fun savedSymbol(item: MediaItem, saved: Boolean): String = when {
    item.kind == MediaKind.EPISODE -> if (saved) "bookmark.slash" else "bookmark"
    saved -> "heart.slash"
    else -> "heart"
}

internal fun savedIcon(item: MediaItem, saved: Boolean): ImageVector = when {
    item.kind == MediaKind.EPISODE -> if (saved) VeyraIcons.Bookmark else VeyraIcons.BookmarkBorder
    saved -> VeyraIcons.Favorite
    else -> VeyraIcons.FavoriteBorder
}

@Composable
internal fun ConfirmActionDialog(title: String, description: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalStrings.current
    com.mohamedrejeb.calf.ui.dialog.AdaptiveAlertDialog(
        onConfirm = onConfirm, onDismiss = onDismiss,
        confirmText = confirm, dismissText = strings.text(TextKey.Cancel), title = title, text = description,
        iosConfirmButtonStyle = com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle.Default,
        iosDismissButtonStyle = com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle.Cancel,
        materialConfirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag("confirm-action")) { Text(confirm) } },
        materialDismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel-action")) { Text(strings.text(TextKey.Cancel)) } },
    )
}

@Composable
internal fun PlaylistPicker(item: MediaItem, playlists: List<Playlist>, onAction: (MusicUiAction) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var creating by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Playlist?>(null) }
    if (creating) {
        PlaylistNameDialog(onDismiss = { creating = false },
            suggestedName = defaultPlaylistName(playlists) { strings.text(TextKey.DefaultPlaylistName).replace("{0}", it) }, onSave = {
            onAction(MusicUiAction.CreatePlaylist(it, item)); onDismiss()
        })
    } else if (duplicate != null) {
        val playlist = duplicate!!
        ConfirmActionDialog(strings.text(TextKey.DuplicateTrack), isolateMetadata(item.title), strings.text(TextKey.AddAgain), { duplicate = null }) {
            onAction(MusicUiAction.AddToPlaylist(playlist.id, item, allowDuplicate = true)); onDismiss()
        }
    } else {
        fun choose(playlist: Playlist) {
            if (playlist.entries.any { it.mediaId == item.id }) duplicate = playlist
            else { onAction(MusicUiAction.AddToPlaylist(playlist.id, item)); onDismiss() }
        }
        // iOS: a native action sheet listing the playlists, then "New playlist" and Cancel.
        NativeAlert(
            ios = AlertDialogIosProperties(
                title = strings.text(TextKey.AddToPlaylist),
                text = if (playlists.isEmpty()) strings.text(TextKey.NoPlaylists) else "",
                style = AlertDialogIosStyle.ActionSheet,
                actions = playlists.map { playlist ->
                    AlertDialogIosAction(displayMetadata(playlist.name), AlertDialogIosActionStyle.Default, onClick = { choose(playlist) })
                } + AlertDialogIosAction(strings.text(TextKey.NewPlaylist), AlertDialogIosActionStyle.Default, onClick = { creating = true }) +
                    AlertDialogIosAction(strings.text(TextKey.Cancel), AlertDialogIosActionStyle.Cancel, onClick = onDismiss),
            ),
            onDismiss = onDismiss,
        ) { AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.text(TextKey.AddToPlaylist)) },
            text = {
                Column {
                    if (playlists.isEmpty()) Text(strings.text(TextKey.NoPlaylists), style = MaterialTheme.typography.bodyMedium)
                    LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(onClick = { choose(playlist) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                MetadataText(playlist.name, Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.NewPlaylist)) } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
        ) }
    }
}
