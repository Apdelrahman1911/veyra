package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** One destination of a [NativeTabBar]; [symbol] names an SF Symbol used on iOS. */
@Immutable
data class NativeTab(val title: String, val symbol: String, val selectedSymbol: String = symbol)

/**
 * A primary-destination bar. On iOS this is a real UITabBar (Liquid Glass on iOS 26), laid out by
 * UIKit at the bottom of the window; the composable reserves its height so content above it, such
 * as a mini player, sits on top of the bar. Other platforms render [fallback] unchanged.
 *
 * [selectedIndex] outside [tabs] (a destination that is not a tab) shows no selected tab. With
 * [reserveSpace] false the iOS bar stays in place (for example behind the keyboard) but the
 * composable takes no height.
 */
@Composable
expect fun NativeTabBar(
    tabs: List<NativeTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedColor: Color,
    reserveSpace: Boolean = true,
    fallback: @Composable () -> Unit,
)
