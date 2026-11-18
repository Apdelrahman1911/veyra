package dev.veyra.nativecomponents

import androidx.compose.runtime.staticCompositionLocalOf

/** Localized information for native accessibility elements hosted by toolkit controls. */
data class NativeControlAccessibility(
    val label: String,
    val value: String,
    val languageTag: String,
)

val LocalNativeControlAccessibility = staticCompositionLocalOf<NativeControlAccessibility?> { null }

