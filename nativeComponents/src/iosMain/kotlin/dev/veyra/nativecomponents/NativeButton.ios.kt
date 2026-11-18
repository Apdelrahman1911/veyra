@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
import platform.UIKit.UIButtonConfigurationCornerStyleCapsule
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIUserInterfaceStyle
import platform.darwin.NSObject

/**
 * The standard UIKit button styles: filled for the main action, gray (tinted label) otherwise,
 * capsule-shaped as iOS 26 draws them. Not the glass configurations: Liquid Glass renders a
 * backdrop copy across the button's whole frame, and that copy cannot capture the Compose Metal
 * canvas behind the button, so glass in content shows a flat capsule on a gray rectangle. Glass
 * stays on the bars (tab bar, navigation bar, toolbar), where UIKit composes it correctly.
 */
private fun configuration(title: String, symbol: String?, style: NativeButtonStyle): UIButtonConfiguration {
    val base = if (style == NativeButtonStyle.Prominent) UIButtonConfiguration.filledButtonConfiguration()
        else UIButtonConfiguration.grayButtonConfiguration()
    return base.apply {
        setTitle(title)
        setImage(symbol?.let { UIImage.systemImageNamed(it) })
        setImagePadding(6.0)
        setCornerStyle(UIButtonConfigurationCornerStyleCapsule)
    }
}

private fun button(title: String, symbol: String?, style: NativeButtonStyle) =
    UIButton.buttonWithConfiguration(configuration(title, symbol, style), primaryAction = null)

@Composable
actual fun NativeButton(
    title: String,
    onClick: () -> Unit,
    style: NativeButtonStyle,
    tint: Color,
    modifier: Modifier,
    symbol: String?,
    enabled: Boolean,
    fallback: @Composable () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val covered = LocalNativeControlsCovered.current
    val callback = rememberUpdatedState(onClick)
    val target = remember { ButtonTarget { callback.value() } }
    // UIKit's own size for this label (its intrinsic content size), never wider than the screen.
    val intrinsic = remember(title, symbol, style) {
        val screen = UIScreen.mainScreen.bounds.useContents { size.width } - 40.0
        button(title, symbol, style).intrinsicContentSize.useContents { minOf(width, screen).dp to height.dp }
    }
    UIKitView(
        factory = {
            nativeControlContainer(button(title, symbol, style).apply {
                addTarget(target, NSSelectorFromString("tapped:"), UIControlEventTouchUpInside)
            }, fill = true)
        },
        modifier = modifier.defaultMinSize(minWidth = intrinsic.first, minHeight = intrinsic.second.coerceAtLeast(48.dp)),
        update = { container ->
            container.hidden = covered
            val button = container.subviews.first() as UIButton
            button.configuration = configuration(title, symbol, style)
            button.enabled = enabled
            button.tintColor = tint.toUIColor()
            button.overrideUserInterfaceStyle =
                if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            button.semanticContentAttribute = if (layoutDirection == LayoutDirection.Rtl)
                UISemanticContentAttributeForceRightToLeft else UISemanticContentAttributeForceLeftToRight
        },
        properties = nativeControlProperties(accessible = true),
    )
}

private fun Color.toUIColor() = UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

private class ButtonTarget(val onTap: () -> Unit) : NSObject() {
    @ObjCAction fun tapped(sender: UIButton) { onTap() }
}
