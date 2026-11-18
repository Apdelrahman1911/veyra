package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** True where [NativeToolbar] is a native bar drawn by the platform (iOS). */
expect val nativeToolbarAvailable: Boolean

/** Room a scrolling list should leave at its end so its last rows clear the native toolbar. */
expect val nativeToolbarClearance: Dp

/**
 * Contextual actions for a full-screen surface. On iOS this is a UIToolbar (Liquid Glass on iOS 26)
 * pinned by UIKit to the bottom of the safe area while this composable is composed and not covered
 * ([LocalNativeControlsCovered]); call it outside any lazy list so scrolling never removes it.
 * Each [NativeBarButton] becomes an SF Symbol item with its label for VoiceOver. Elsewhere this
 * renders nothing: those platforms keep their in-content buttons.
 */
@Composable
expect fun NativeToolbar(items: List<NativeBarButton>)
