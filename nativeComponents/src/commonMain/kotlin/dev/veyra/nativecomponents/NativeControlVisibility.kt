package dev.veyra.nativecomponents

import androidx.compose.runtime.compositionLocalOf

/**
 * True while Compose content above the hosted native controls (for example a full-screen player
 * sliding over the screen) covers them. On iOS those controls are UIKit views placed above the
 * Compose canvas, so Compose drawing cannot hide them; they hide themselves instead.
 */
val LocalNativeControlsCovered = compositionLocalOf { false }
