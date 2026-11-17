@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSSelectorFromString
import platform.UIKit.NSForegroundColorAttributeName
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIControlStateSelected
import platform.UIKit.UISegmentedControl
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleBottomMargin
import platform.UIKit.UIViewAutoresizingFlexibleTopMargin
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.darwin.NSObject

private const val HOST_HEIGHT = 48.0

@Composable
actual fun NativeSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val covered = LocalNativeControlsCovered.current
    val callback = rememberUpdatedState(onSelect)
    val target = remember { SegmentTarget { callback.value(it) } }
    // UIKit's own segment height on this release, centred in a 48 dp touch row.
    val controlHeight = remember { intrinsicControlSize(UISegmentedControl(listOf("A", "B"))).height }
    UIKitView(
        factory = {
            val container = UIView(frame = CGRectMake(0.0, 0.0, 320.0, HOST_HEIGHT)).apply {
                backgroundColor = UIColor.clearColor
                opaque = false
            }
            val control = UISegmentedControl(options).apply {
                setFrame(CGRectMake(0.0, (HOST_HEIGHT - controlHeight.value) / 2, 320.0, controlHeight.value.toDouble()))
                autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleTopMargin or
                    UIViewAutoresizingFlexibleBottomMargin
                addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged)
            }
            container.addSubview(control)
            container
        },
        modifier = modifier.fillMaxWidth().height(controlHeight.coerceAtLeast(HOST_HEIGHT.dp)),
        update = { container ->
            container.hidden = covered
            val control = container.subviews.first() as UISegmentedControl
            if (control.numberOfSegments.toInt() != options.size) {
                control.removeAllSegments()
                options.forEachIndexed { index, title -> control.insertSegmentWithTitle(title, index.toULong(), false) }
            } else options.forEachIndexed { index, title ->
                if (control.titleForSegmentAtIndex(index.toULong()) != title) control.setTitle(title, index.toULong())
            }
            // Four or more labels share the width by their length so none is cut short.
            control.apportionsSegmentWidthsByContent = options.size >= 4
            if (control.selectedSegmentIndex != selectedIndex.toLong()) control.selectedSegmentIndex = selectedIndex.toLong()
            control.selectedSegmentTintColor = scheme.primary.toUIColor()
            control.setTitleTextAttributes(mapOf<Any?, Any?>(NSForegroundColorAttributeName to scheme.onPrimary.toUIColor()),
                UIControlStateSelected)
            control.setTitleTextAttributes(mapOf<Any?, Any?>(NSForegroundColorAttributeName to scheme.onSurface.toUIColor()),
                UIControlStateNormal)
            control.overrideUserInterfaceStyle =
                if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            control.semanticContentAttribute = if (layoutDirection == LayoutDirection.Rtl)
                UISemanticContentAttributeForceRightToLeft else UISemanticContentAttributeForceLeftToRight
        },
        properties = nativeControlProperties(accessible = true),
    )
}

private fun Color.toUIColor() = UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

private class SegmentTarget(val onSelect: (Int) -> Unit) : NSObject() {
    @ObjCAction fun changed(sender: UISegmentedControl) {
        sender.selectedSegmentIndex.toInt().takeIf { it >= 0 }?.let(onSelect)
    }
}
