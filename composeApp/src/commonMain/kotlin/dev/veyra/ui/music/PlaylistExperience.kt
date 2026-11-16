package dev.veyra.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.veyra.model.AppSnapshot
import dev.veyra.model.MediaItem
import dev.veyra.model.MediaKind
import dev.veyra.model.Playlist
import dev.veyra.ui.design.Artwork
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey
import dev.veyra.ui.design.VeyraIcons

/** A unique, ready-to-use name ("Playlist 3"), so creating a playlist is a single tap. */
internal fun defaultPlaylistName(existing: List<Playlist>, format: (String) -> String): String {
    val names = existing.mapTo(mutableSetOf()) { it.name.trim() }
    var number = existing.size + 1
    while (format(number.toString()) in names) number++
    // Plain digits and no bidi isolate marks: this text is edited and stored as the name.
    return format(number.toString())
}

/**
 * Create (or rename) a playlist. A new playlist starts with a suggested name, fully selected, so
 * the user can accept it with Enter or just type a different one.
 */
@Composable
internal fun PlaylistNameDialog(initialName: String = "", onDismiss: () -> Unit, suggestedName: String = "", onSave: (String) -> Unit) {
    val strings = LocalStrings.current
    val creating = initialName.isBlank()
    val start = initialName.ifBlank { suggestedName }
    var field by remember(start) { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    val valid = validatedPlaylistName(field.text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(if (creating) TextKey.NewPlaylist else TextKey.RenamePlaylist)) },
        text = {
            OutlinedTextField(value = field, onValueChange = { field = it }, label = { Text(strings.text(TextKey.PlaylistName)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { valid?.let(onSave) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("playlist-name"))
        },
        confirmButton = {
            Button(onClick = { valid?.let(onSave) }, enabled = valid != null, modifier = Modifier.heightIn(min = 48.dp).testTag("playlist-save")) {
                Text(strings.text(if (creating) TextKey.Create else TextKey.Rename))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) } },
    )
}

/** Songs the user can add to a playlist: the library's music, favourites first, then by title. */
internal fun playlistCandidates(snapshot: AppSnapshot): List<MediaItem> {
    val favorites = snapshot.favoriteIds.toSet()
    return snapshot.media.values.filter { it.kind == MediaKind.MUSIC }
        .sortedWith(compareBy({ it.id !in favorites }, { mediaSearchKey(it.title) }))
}

/**
 * Pick several songs at once and add them in one step. Songs already in the playlist are shown
 * as added; the rest can be searched and ticked.
 */
@Composable
internal fun AddSongsDialog(snapshot: AppSnapshot, playlist: Playlist, onAction: (MusicUiAction) -> Unit,
    onFindMusic: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    var query by remember { mutableStateOf("") }
    var selected by remember(playlist.id) { mutableStateOf(setOf<String>()) }
    val present = remember(playlist) { playlist.entries.mapTo(mutableSetOf()) { it.mediaId } }
    val candidates = remember(snapshot.media, snapshot.favoriteIds) { playlistCandidates(snapshot) }
    val shown = candidates.filter { matchesMediaQuery(it, query) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp).heightIn(max = 720.dp).testTag("add-songs"),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(strings.text(TextKey.AddSongs), Modifier.padding(horizontal = 24.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                MetadataText(playlist.name, Modifier.padding(horizontal = 24.dp, vertical = 2.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (candidates.isEmpty()) {
                    Text(strings.text(TextKey.NoSongsToAdd), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                } else {
                    OutlinedTextField(query, { query = it }, label = { Text(strings.text(TextKey.SearchLocal)) }, singleLine = true,
                        leadingIcon = { Icon(VeyraIcons.Search, null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("add-songs-query"))
                    LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        items(shown, key = { it.id }) { item ->
                            val added = item.id in present
                            val checked = added || item.id in selected
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(enabled = !added, role = Role.Checkbox) {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                }.padding(horizontal = 16.dp, vertical = 4.dp).testTag("add-song-${item.id}"),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Artwork(item.artworkUrl, item.title, Modifier.size(44.dp), snapshot.settings.offlineOnly, item.localArtworkPath)
                                Column(Modifier.weight(1f)) {
                                    MetadataText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                    val detail = if (added) strings.text(TextKey.AlreadyInPlaylist) else item.creator.orEmpty()
                                    if (detail.isNotBlank()) MetadataText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(checked = checked, onCheckedChange = null, enabled = !added)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ onDismiss(); onFindMusic() }, Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.FindMusic)) }
                    Box(Modifier.weight(1f))
                    TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(strings.text(TextKey.Cancel)) }
                    Button({
                        val items = candidates.filter { it.id in selected }
                        if (items.isNotEmpty()) onAction(MusicUiAction.AddAllToPlaylist(playlist.id, items))
                        onDismiss()
                    }, enabled = selected.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("add-songs-confirm")) {
                        Text(strings.format(TextKey.AddSelectedSongs, strings.number(selected.size.toLong())))
                    }
                }
            }
        }
    }
}

/** A compact playlist entry for the library: a cover mosaic, the name and the song count. */
@Composable
internal fun PlaylistCover(playlist: Playlist, snapshot: AppSnapshot, modifier: Modifier = Modifier) {
    val covers = playlist.entries.asSequence().mapNotNull { snapshot.media[it.mediaId] }.distinctBy { it.id }.take(1).toList()
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val cover = covers.firstOrNull()
        if (cover != null) Artwork(cover.artworkUrl, playlist.name, Modifier.fillMaxSize(), snapshot.settings.offlineOnly, cover.localArtworkPath)
        else Icon(VeyraIcons.Music, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
