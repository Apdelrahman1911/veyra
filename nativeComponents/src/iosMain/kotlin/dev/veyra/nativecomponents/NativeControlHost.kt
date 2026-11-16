@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.veyra.nativecomponents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.*

/** The color behind native controls: the provided local, else the theme background. */
@Composable
fun nativeControlBackground(): Color = LocalNativeControlBackground.current.takeIf { it.isSpecified }
    ?: MaterialTheme.colorScheme.background

fun Color.toUIColor(): UIColor = UIColor(red = red.toDouble(), green = green.toDouble(), blue = blue.toDouble(), alpha = alpha.toDouble())

/**
 * A container that fills the whole interop area with [Color] and hosts [control]. When
 * [fill] is false the control keeps its own size, centred (UISwitch draws at a fixed 51×31).
 */
fun nativeControlContainer(control: UIView, fill: Boolean): UIView {
    val container = UIView(frame = CGRectMake(0.0, 0.0, 52.0, 48.0))
    container.clipsToBounds = true
    control.backgroundColor = UIColor.clearColor
    if (fill) {
        control.setFrame(container.bounds)
        control.autoresizingMask = UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
    } else {
        control.sizeToFit()
        container.bounds.useContents { control.setCenter(platform.CoreGraphics.CGPointMake(size.width / 2, size.height / 2)) }
        control.autoresizingMask = UIViewAutoresizingFlexibleLeftMargin or UIViewAutoresizingFlexibleRightMargin or
            UIViewAutoresizingFlexibleTopMargin or UIViewAutoresizingFlexibleBottomMargin
    }
    container.addSubview(control)
    return container
}
