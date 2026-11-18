package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun NativeButton(
    title: String,
    onClick: () -> Unit,
    style: NativeButtonStyle,
    tint: Color,
    modifier: Modifier,
    symbol: String?,
    enabled: Boolean,
    fallback: @Composable () -> Unit,
) = fallback()
