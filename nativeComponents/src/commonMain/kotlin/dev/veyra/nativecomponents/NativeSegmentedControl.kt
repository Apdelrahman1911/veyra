package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One choice among a few mutually exclusive views of the same screen. On iOS this is UIKit's
 * `UISegmentedControl` (the toolkit has no segmented control), stretched to the given width, with
 * the selected segment in the theme's primary colour. Other platforms render [fallback].
 */
@Composable
expect fun NativeSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
)
