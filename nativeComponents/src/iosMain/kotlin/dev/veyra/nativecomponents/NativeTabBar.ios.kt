package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.apdelrahman1911.kira.ui.ExperimentalKiraUiApi
import io.github.apdelrahman1911.kira.ui.navigation.AdaptiveNavigationBar
import io.github.apdelrahman1911.kira.ui.navigation.UIKitTabBarConfiguration
import io.github.apdelrahman1911.kira.ui.navigation.UIKitUITabBarItem
import io.github.apdelrahman1911.kira.ui.uikit.UIKitImage

@OptIn(ExperimentalKiraUiApi::class)
@Composable
actual fun NativeTabBar(
    tabs: List<NativeTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    selectedColor: Color,
    reserveSpace: Boolean,
    fallback: @Composable () -> Unit,
) {
    val items = remember(tabs) {
        tabs.map { UIKitUITabBarItem(it.title, UIKitImage.SystemName(it.symbol), UIKitImage.SystemName(it.selectedSymbol)) }
    }
    // The UITabBar is laid out by UIKit; this box only decides how much room Compose reserves for it.
    Box(if (reserveSpace) Modifier else Modifier.height(0.dp)) {
        AdaptiveNavigationBar(
            iosItems = items,
            iosSelectedIndex = selectedIndex,
            iosOnItemSelected = onSelect,
            iosConfiguration = UIKitTabBarConfiguration(selectedItemColor = selectedColor),
        )
    }
}
