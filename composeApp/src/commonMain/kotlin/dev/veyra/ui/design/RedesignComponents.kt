package dev.veyra.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared atmosphere rather than separate backgrounds on every scrolling surface. */
@Composable
fun Modifier.veyraAmbient(): Modifier {
    val colors = LocalVeyraColors.current
    return drawWithCache {
        val glow = Brush.radialGradient(
            colors = listOf(colors.primary.copy(alpha = .22f), colors.primary.copy(alpha = .05f), Color.Transparent),
            center = Offset(size.width * .66f, -size.width * .22f),
            radius = size.width.coerceAtLeast(600f) * 1.08f,
        )
        onDrawBehind { drawRect(colors.canvas); drawRect(glow) }
    }
}

val VeyraAccentGradient: Brush
    get() = Brush.linearGradient(listOf(Color(0xFF8446AE), Color(0xFFB9502A)))

@Composable
fun AccentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick, modifier.heightIn(min = 48.dp).background(VeyraAccentGradient, CircleShape),
        enabled = enabled, shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SearchShortcut(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = .85f),
        border = BorderStroke(1.dp, LocalVeyraColors.current.outlineSubtle),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(VeyraIcons.Search, null, Modifier.size(22.dp))
            Text(strings.text(TextKey.SearchHint), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(42.dp).background(VeyraAccentGradient, CircleShape), contentAlignment = Alignment.Center) {
                Icon(VeyraIcons.ChevronForward, null, Modifier.size(22.dp), tint = Color.White)
            }
        }
    }
}

@Composable
fun ScreenIntro(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun CollectionLink(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, LocalVeyraColors.current.outlineSubtle.copy(alpha = .65f))) {
        Row(Modifier.padding(16.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(VeyraIcons.ChevronForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
