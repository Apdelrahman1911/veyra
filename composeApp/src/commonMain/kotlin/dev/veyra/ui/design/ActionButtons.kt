package dev.veyra.ui.design

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.veyra.nativecomponents.NativeButton
import dev.veyra.nativecomponents.NativeButtonStyle

/**
 * A labelled action. iOS shows a native UIButton: filled for the screen's main action
 * ([prominent]), gray with a tinted label for the rest, with the SF Symbol [symbol]. Android and desktop show
 * the Material [Button] (prominent) or [OutlinedButton] with the caller's own [content], unchanged.
 */
@Composable
fun ActionButton(
    title: String,
    symbol: String?,
    prominent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = NativeButton(
    title = title,
    onClick = onClick,
    style = if (prominent) NativeButtonStyle.Prominent else NativeButtonStyle.Secondary,
    tint = MaterialTheme.colorScheme.primary,
    modifier = modifier,
    symbol = symbol,
    enabled = enabled,
) {
    if (prominent) Button(onClick, modifier, enabled, content = content)
    else OutlinedButton(onClick, modifier, enabled, content = content)
}
