package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun NativeTabBar(
    tabs: List<NativeTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedColor: Color,
    reserveSpace: Boolean,
    fallback: @Composable () -> Unit,
) = fallback()
