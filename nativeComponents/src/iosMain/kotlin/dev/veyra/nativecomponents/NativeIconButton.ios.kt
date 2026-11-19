package dev.veyra.nativecomponents

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.apdelrahman1911.kira.ui.button.CupertinoButton
import io.github.apdelrahman1911.kira.ui.button.CupertinoButtonColors

@Composable
actual fun NativeIconButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    colors: IconButtonColors,
    interactionSource: MutableInteractionSource,
    content: @Composable () -> Unit,
) {
    CupertinoButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        colors = CupertinoButtonColors(
            colors.containerColor, colors.contentColor, colors.disabledContainerColor, colors.disabledContentColor,
        ),
        contentPadding = PaddingValues(0.dp),
        interactionSource = interactionSource,
    ) { content() }
}
