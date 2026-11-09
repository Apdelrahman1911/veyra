package dev.veyra.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Production uses the owned metadata artwork cache, never a source request from a row. */
val LocalRemoteArtworkAllowed = staticCompositionLocalOf { false }

/** Only wrap media transport/seek geometry. Do not wrap the entire Arabic player. */
@Composable
fun MediaDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector = VeyraIcons.Brand,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(VeyraSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Medium),
    ) {
        Surface(
            shape = RoundedCornerShape(VeyraRadii.Card),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp).semantics { heading() },
        )
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.heightIn(min = VeyraSpacing.TouchTarget)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(VeyraRadii.Artwork),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(
            modifier = Modifier.padding(VeyraSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Small),
        ) {
            Icon(VeyraIcons.Warning, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = VeyraSpacing.TouchTarget)) {
                    Text(strings.text(TextKey.Retry))
                }
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(VeyraSpacing.Section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VeyraSpacing.Medium),
    ) {
        // One verbal status. The progress decoration is not separately announced.
        CircularProgressIndicator(modifier = Modifier.size(28.dp).clearAndSetSemantics { })
        Text(label ?: LocalStrings.current.text(TextKey.Loading), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/**
 * Consistent minimum target, selected semantics, visible keyboard focus and hover/long-press label.
 * A null [selected] is a plain command, not a toggle that announces "not selected" unnecessarily.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeyraIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    stateLabel: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focusColor = MaterialTheme.colorScheme.primary
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactions,
            modifier = modifier
                .sizeIn(minWidth = VeyraSpacing.TouchTarget, minHeight = VeyraSpacing.TouchTarget)
                .then(if (focused) Modifier.border(2.dp, focusColor, CircleShape) else Modifier)
                .semantics {
                    if (selected != null) this.selected = selected
                    if (stateLabel != null) stateDescription = stateLabel
                },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (selected == true) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected == true) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
    }
}

enum class StatusTone { Neutral, Success, Warning, Error }

/** Non-interactive status: text and optional shape/icon, not hue alone. */
@Composable
fun StatusBadge(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: StatusTone = StatusTone.Neutral,
) {
    val palette = LocalVeyraColors.current
    val foreground = when (tone) {
        StatusTone.Neutral -> palette.onSurfaceVariant
        StatusTone.Success -> palette.success
        StatusTone.Warning -> palette.warning
        StatusTone.Error -> palette.error
    }
    Surface(
        modifier = modifier,
        color = palette.surface,
        contentColor = foreground,
        shape = RoundedCornerShape(VeyraRadii.Small),
        border = BorderStroke(1.dp, palette.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Short metadata aligns with the outer row, while its internal word order follows its content.
 * Long prose can request [paragraph] to align with the content's own language.
 */
@Composable
fun MetadataText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    paragraph: Boolean = false,
) {
    val alignment = if (paragraph) TextAlign.Start
    else if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    Text(
        text = displayMetadata(text),
        modifier = modifier,
        style = style.copy(textDirection = TextDirection.Content),
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = alignment,
    )
}

/**
 * Metadata artwork is decorative when its title is already exposed by the containing row/screen.
 * Uses the app's shared Coil loader for owned local files, never a per-row network loader.
 * Remote models require an explicit isolated-test opt-in; production leaves that permission
 * false and supplies persisted paths from its policy-enforcing metadata artwork cache.
 * Offline always wins over the test opt-in, including URLs accidentally passed as localPath.
 * A content provider can fetch cloud data, so it is subject to the same remote permission.
 * Callers must supply an app-owned persisted file, not a network-mounted directory. A remote-
 * only image falls back until its owned path exists. No fabricated album artwork.
 */
@Composable
fun Artwork(
    url: String?,
    title: String,
    modifier: Modifier = Modifier,
    offlineOnly: Boolean = false,
    localPath: String? = null,
) {
    val remoteAllowed = LocalRemoteArtworkAllowed.current
    val model = remember(url, offlineOnly, localPath, remoteAllowed) {
        artworkModel(url, offlineOnly, localPath, remoteAllowed)
    }
    var loaded by remember(model) { mutableStateOf(false) }
    val palette = LocalVeyraColors.current
    val initial = remember(title) { displayMetadata(title).firstOrNull { it.isLetterOrDigit() }?.toString() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(VeyraRadii.Artwork))
            .background(palette.surfaceContainerHighest)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            if (initial != null) {
                Text(initial, style = MaterialTheme.typography.headlineSmall, color = palette.onSurfaceVariant)
            } else {
                Icon(VeyraIcons.Brand, contentDescription = null, tint = palette.onSurfaceVariant, modifier = Modifier.size(28.dp))
            }
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { loaded = false },
            )
        }
    }
}

/** Pure resolver so offline/no-network and private-file handling can be tested without an image loader. */
internal fun artworkModel(url: String?, offlineOnly: Boolean, localPath: String?, remoteAllowed: Boolean = false): String? {
    val blockNetwork = offlineOnly || !remoteAllowed
    val local = localPath?.trim()?.takeIf { it.isNotEmpty() }
    if (local != null && isLocalArtworkPath(local, blockNetwork)) return local
    if (blockNetwork) return null
    val remote = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return remote.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

private fun isLocalArtworkPath(value: String, blockNetwork: Boolean): Boolean {
    // Decode only for policy checks; the loader still receives the unchanged model. This also
    // catches file:///%5C%5Cserver/share and percent-encoded network-path prefixes.
    val decoded = Regex("%([0-9A-Fa-f]{2})").replace(value) {
        it.groupValues[1].toInt(16).toChar().toString()
    }
    if (decoded.any { it < ' ' || it == '\u007F' } || hasNetworkPathPrefix(decoded)) return false
    if (decoded.length >= 3 && (decoded[0] in 'A'..'Z' || decoded[0] in 'a'..'z')) {
        if (decoded[1] == ':' && (decoded[2] == '\\' || decoded[2] == '/')) return true
    }
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(decoded)?.groupValues?.get(1)?.lowercase()
    return when (scheme) {
        null -> !decoded.contains("://")
        "file" -> {
            val rest = decoded.substringAfter(':')
            val path = if (rest.startsWith("//")) {
                val authorityAndPath = rest.drop(2)
                val authority = authorityAndPath.substringBefore('/')
                if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) return false
                authorityAndPath.substringAfter('/', missingDelimiterValue = "").let { "/$it" }
            } else rest
            path.startsWith('/') && !hasNetworkPathPrefix(path)
        }
        "content" -> !blockNetwork && decoded.startsWith("content://", ignoreCase = true)
        "android.resource" -> decoded.startsWith("android.resource://", ignoreCase = true)
        else -> false
    }
}

private fun hasNetworkPathPrefix(value: String): Boolean =
    value.length >= 2 && value[0] in "/\\" && value[1] in "/\\"

@Composable
fun VeyraWordmark(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VeyraSpacing.Small),
    ) {
        Icon(VeyraIcons.Brand, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        Text(LocalStrings.current.text(TextKey.AppName), style = MaterialTheme.typography.titleLarge)
    }
}
