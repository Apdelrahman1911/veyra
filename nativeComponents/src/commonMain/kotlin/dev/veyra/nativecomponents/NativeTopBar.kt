package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** An icon-only top-bar button: [symbol] names an SF Symbol, [label] is what VoiceOver reads. */
@Immutable
data class NativeBarButton(
    val label: String,
    val symbol: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The screen's top bar. On iOS this is a real UINavigationBar (Liquid Glass on iOS 26) pinned by
 * UIKit to the top of the safe area, with [leading] and [trailing] bar button items; the composable
 * reserves exactly the bar's own height, because the caller already sits below the safe area.
 * While [visible] is false (for example while a full-screen surface covers the bar's area) the
 * native bar is removed and the reserved space stays, so the layout never jumps. Other platforms
 * render [fallback] unchanged.
 */
@Composable
expect fun NativeTopBar(
    title: String,
    leading: List<NativeBarButton>,
    trailing: List<NativeBarButton>,
    titleColor: Color,
    tintColor: Color,
    visible: Boolean = true,
    fallback: @Composable () -> Unit,
)
