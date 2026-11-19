package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.apdelrahman1911.kira.ui.ExperimentalKiraUiApi
import io.github.apdelrahman1911.kira.ui.navigation.UIKitUIBarButtonItem
import io.github.apdelrahman1911.kira.ui.toolbar.AdaptiveToolbar

actual val nativeToolbarAvailable: Boolean = true
actual val nativeToolbarClearance: Dp = 72.dp

@OptIn(ExperimentalKiraUiApi::class)
@Composable
actual fun NativeToolbar(items: List<NativeBarButton>) {
    if (LocalNativeControlsCovered.current) return
    // Flexible spaces spread the items across the bar, as in iOS toolbars.
    val buttons = rememberBarItems(items)
    val spaced = remember(buttons) {
        buildList {
            add(UIKitUIBarButtonItem.flexibleSpace())
            buttons.forEach { add(it); add(UIKitUIBarButtonItem.flexibleSpace()) }
        }
    }
    AdaptiveToolbar(expanded = true, iosItems = spaced)
}
