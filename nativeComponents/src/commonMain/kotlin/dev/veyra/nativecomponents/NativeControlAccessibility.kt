package dev.veyra.nativecomponents

import androidx.compose.runtime.staticCompositionLocalOf

/** Localized information for native accessibility elements hosted by toolkit controls. */
data class NativeControlAccessibility(
    val label: String,
    val value: String,
    val languageTag: String,
)

val LocalNativeControlAccessibility = staticCompositionLocalOf<NativeControlAccessibility?> { null }

/**
 * The color directly behind a hosted native control. UIKit controls are drawn in an area cut
 * through the Compose layer; whatever the control does not paint must be filled with this color,
 * or it shows black. Unspecified means the theme background.
 */
val LocalNativeControlBackground = androidx.compose.runtime.compositionLocalOf { androidx.compose.ui.graphics.Color.Unspecified }
