package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.navigation.UIKitUIBarButtonItem
import com.mohamedrejeb.calf.ui.toolbar.ToolbarManager

actual val nativeToolbarAvailable: Boolean = true
actual val nativeToolbarClearance: Dp = 72.dp

/**
 * Drives the toolkit's UIToolbar ([ToolbarManager]) the way its `AdaptiveToolbar` does on iOS.
 * `AdaptiveToolbar` itself is not compiled here: its Material side needs the Material 3 Expressive
 * floating toolbar, which the Material 3 version pinned with Compose Multiplatform 1.10.3 lacks,
 * and Veyra's other platforms keep their in-content buttons anyway.
 */
@Composable
actual fun NativeToolbar(items: List<NativeBarButton>) {
    if (LocalNativeControlsCovered.current) return
    val viewController = LocalUIViewController.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val manager = remember { ToolbarManager() }
    // Flexible spaces spread the items across the bar, as in iOS toolbars.
    val buttons = rememberBarItems(items)
    val spaced = remember(buttons) {
        buildList {
            add(UIKitUIBarButtonItem.flexibleSpace())
            buttons.forEach { add(it); add(UIKitUIBarButtonItem.flexibleSpace()) }
        }
    }
    DisposableEffect(viewController) {
        manager.attachTo(viewController.view)
        onDispose { manager.detach() }
    }
    LaunchedEffect(layoutDirection) { manager.applyLayoutDirection(layoutDirection) }
    LaunchedEffect(spaced) { manager.update(items = spaced, density = density.density) }
}
