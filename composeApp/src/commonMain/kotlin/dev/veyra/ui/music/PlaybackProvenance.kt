package dev.veyra.ui.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import dev.veyra.model.FirstResultKind
import dev.veyra.model.MediaItem
import dev.veyra.model.PlaybackBinding
import dev.veyra.ui.design.LocalStrings
import dev.veyra.ui.design.MetadataText
import dev.veyra.ui.design.TextKey

enum class PlaybackProvenanceContext { CURRENT, DOWNLOADED, PREVIOUS, INCOMING }

/**
 * Displays only the exact receipt supplied by its owner. Never chooses a "latest" search result
 * from a library: current playback and downloaded assets may have different immutable receipts.
 * Equal title text is deliberately not treated as proof that two recordings are the same.
 */
@Composable
fun PlaybackProvenanceCard(
    item: MediaItem,
    binding: PlaybackBinding?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    context: PlaybackProvenanceContext = PlaybackProvenanceContext.CURRENT,
) {
    val strings = LocalStrings.current
    val receipt = binding?.takeIf { it.mediaId == item.id }
    var expanded by remember(receipt?.id) { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth().testTag("playback-provenance"), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (receipt == null) {
                Text(strings.text(TextKey.SourceResolutionDescription), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(strings.text(when (context) {
                    PlaybackProvenanceContext.CURRENT -> TextKey.CurrentAudioResolution
                    PlaybackProvenanceContext.DOWNLOADED -> TextKey.DownloadedAudioResolution
                    PlaybackProvenanceContext.PREVIOUS -> TextKey.PreviousAudioResolution
                    PlaybackProvenanceContext.INCOMING -> TextKey.IncomingAudioResolution
                }), style = MaterialTheme.typography.labelLarge)
                Text(strings.text(if (receipt.selectionOrdinal == 0) TextKey.FirstSearchResult else TextKey.InvalidState), style = MaterialTheme.typography.labelSmall)
                if (!compact) {
                    Text(strings.text(TextKey.RequestedTitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MetadataText(receipt.queryTitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                }
                Text(strings.text(TextKey.ResolvedTitle), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetadataText(receipt.firstResultTitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, maxLines = if (compact) 2 else if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                if (receipt.firstResultKind == FirstResultKind.VIDEO && !receipt.selectedVideoId.isNullOrBlank()) {
                    Text(strings.format(TextKey.AudioSourceValue, strings.text(TextKey.YouTube)), style = MaterialTheme.typography.labelMedium)
                } else Text(strings.text(TextKey.FirstResultUnavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(strings.text(if (receipt.selectionOrdinal == 0 && receipt.queryTitle != receipt.firstResultTitle) TextKey.SourceMismatchDetected else TextKey.SourceMatchUnverified), style = MaterialTheme.typography.bodySmall)
                if (!compact) TextButton({ expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(if (expanded) TextKey.ShowLess else TextKey.ShowMore)) }
            }
        }
    }
}
