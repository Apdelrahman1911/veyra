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
