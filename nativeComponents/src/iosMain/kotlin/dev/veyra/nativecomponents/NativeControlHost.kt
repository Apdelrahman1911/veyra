@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.veyra.nativecomponents

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.*

/**
 * Interop properties for hosted UIKit controls.
 *
 * By default Compose Multiplatform places an interop view *below* its Metal canvas and cuts a
 * transparent hole through the canvas; every pixel the native control leaves unpainted then
 * shows whatever lies under the canvas (black), never the Compose surface around it. Placing the
 * control *above* the canvas lets it keep a transparent background, so it blends with any
 * surface, in light and dark themes alike. Compose still clips it to its ancestors' bounds.
 */
fun nativeControlProperties(accessible: Boolean): UIKitInteropProperties =
    UIKitInteropProperties(isNativeAccessibilityEnabled = accessible, placedAsOverlay = true)

/**
 * A transparent container hosting [control]. When [fill] is false the control keeps its own
 * intrinsic size, centred: UISwitch draws at a fixed size that differs between iOS releases
 * (51×31 before iOS 26, wider since), so it must never be squeezed or clipped by its host.
 */
fun nativeControlContainer(control: UIView, fill: Boolean): UIView {
    val container = UIView(frame = CGRectMake(0.0, 0.0, 64.0, 48.0))
    container.backgroundColor = UIColor.clearColor
    container.opaque = false
    control.backgroundColor = UIColor.clearColor
    if (fill) {
        control.setFrame(container.bounds)
        control.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    } else {
        control.sizeToFit()
        container.bounds.useContents { control.setCenter(CGPointMake(size.width / 2, size.height / 2)) }
        control.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin or UIViewAutoresizingFlexibleRightMargin or
            UIViewAutoresizingFlexibleTopMargin or UIViewAutoresizingFlexibleBottomMargin
    }
    container.addSubview(control)
    return container
}

/** The size UIKit draws [control] at, in points (1 pt = 1 dp in Compose on iOS). */
fun intrinsicControlSize(control: UIView): DpSize =
    control.sizeThatFits(CGSizeMake(0.0, 0.0)).useContents { DpSize(width.dp, height.dp) }
