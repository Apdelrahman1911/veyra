package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun NativeDurationPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) = fallback()
