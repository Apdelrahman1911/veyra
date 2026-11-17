package dev.veyra.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The bottom area persistent chrome (bottom navigation, mini/full player transport) must keep
 * clear: system bars and display cutout only, never the software keyboard.
 *
 * The keyboard is handled exactly once, by `imePadding()` at the app root. `safeDrawing` also
 * contains the IME inset, and whether an ancestor's consumption reaches a subcomposed sheet is not
 * something chrome may rely on: when it does not, the keyboard height is added a second time and
 * the bottom sheet's peek grows over the whole screen. While the keyboard is up it already covers
 * the home-indicator area (the root pads by it), so the part the IME covers is excluded too.
 */
val WindowInsets.Companion.chromeBottom: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout).exclude(WindowInsets.ime).only(WindowInsetsSides.Bottom)

/**
 * Height of the floating chrome (mini player and tab bar) that screen content scrolls beneath.
 * The chrome itself is transparent around its floating cards, so content runs to the bottom edge
 * and each scrolling screen adds this to its own end padding to let its last row clear the cards.
 */
val LocalBottomChromeHeight = staticCompositionLocalOf { 0.dp }

/** A scrolling screen's bottom padding: its own [extra] space plus the floating chrome above it. */
@Composable
fun bottomChrome(extra: Dp): Dp = LocalBottomChromeHeight.current + extra
