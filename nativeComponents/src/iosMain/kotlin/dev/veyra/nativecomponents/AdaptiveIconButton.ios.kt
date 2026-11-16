package com.mohamedrejeb.calf.ui.button

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.IconButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun AdaptiveIconButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    colors: IconButtonColors, liquidGlassColors: LiquidGlassButtonColors?,
    interactionSource: MutableInteractionSource, content: @Composable () -> Unit) {
    CupertinoButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), enabled,
        colors = CupertinoButtonColors(colors.containerColor, colors.contentColor, colors.disabledContainerColor, colors.disabledContentColor),
        contentPadding = PaddingValues(0.dp), interactionSource = interactionSource) { content() }
}
