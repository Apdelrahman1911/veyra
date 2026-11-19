@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.apdelrahman1911.kira.ui.ExperimentalKiraUiApi
import io.github.apdelrahman1911.kira.ui.navigation.AdaptiveTopBar
import io.github.apdelrahman1911.kira.ui.navigation.UIKitNavigationBarConfiguration
import io.github.apdelrahman1911.kira.ui.navigation.UIKitUIBarButtonItem
import io.github.apdelrahman1911.kira.ui.uikit.UIKitImage
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UINavigationBar
import platform.UIKit.UIScreen

@OptIn(ExperimentalKiraUiApi::class, ExperimentalMaterial3Api::class)
@Composable
actual fun NativeTopBar(
    title: String,
    leading: List<NativeBarButton>,
    trailing: List<NativeBarButton>,
    titleColor: Color,
    tintColor: Color,
    visible: Boolean,
    fallback: @Composable () -> Unit,
) {
    // The height UIKit gives a standard (non-large-title) navigation bar on this OS release.
    val barHeight = remember {
        val width = UIScreen.mainScreen.bounds.useContents { size.width }
        UINavigationBar().sizeThatFits(CGSizeMake(width, 0.0)).useContents { height }.dp
    }
    val leadingItems = rememberBarItems(leading)
    val trailingItems = rememberBarItems(trailing)
    val configuration = remember(titleColor, tintColor) {
        UIKitNavigationBarConfiguration(titleColor = titleColor, tintColor = tintColor)
    }
    // AdaptiveTopBar reserves the safe area plus the bar; the caller is already below the safe
    // area, so only the bar's own height is kept here.
    Box(Modifier.fillMaxWidth().height(barHeight)) {
        if (visible) AdaptiveTopBar(
            iosTitle = title,
            iosLeadingItems = leadingItems,
            iosTrailingItems = trailingItems,
            iosConfiguration = configuration,
        )
    }
}

/**
 * UIKit items rebuilt only when what they show changes. Rebuilding them on every recomposition
 * would replace the bar's items (and drop a tap in flight) whenever the caller recomposes; the
 * click handlers always call the caller's latest callbacks.
 */
@Composable
internal fun rememberBarItems(buttons: List<NativeBarButton>): List<UIKitUIBarButtonItem> {
    val current by rememberUpdatedState(buttons)
    val shown = buttons.map { Triple(it.label, it.symbol, it.enabled) }
    return remember(shown) {
        shown.mapIndexed { index, (label, symbol, enabled) ->
            UIKitUIBarButtonItem(
                image = UIKitImage.SystemName(symbol),
                enabled = enabled,
                accessibilityLabel = label,
                onClick = { current.getOrNull(index)?.onClick?.invoke() },
            )
        }
    }
}
