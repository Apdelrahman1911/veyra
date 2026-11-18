@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIDatePicker
import platform.UIKit.UIDatePickerMode
import platform.UIKit.UIDatePickerStyle
import platform.UIKit.UISemanticContentAttributeForceLeftToRight
import platform.UIKit.UISemanticContentAttributeForceRightToLeft
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.setAccessibilityLabel
import platform.darwin.NSObject

private const val MAX_MINUTES = 23 * 60 + 59

private fun countdownPicker() = UIDatePicker().apply {
    datePickerMode = UIDatePickerMode.UIDatePickerModeCountDownTimer
    preferredDatePickerStyle = UIDatePickerStyle.UIDatePickerStyleWheels
    minuteInterval = 1
}

@Composable
actual fun NativeDurationPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val callback = rememberUpdatedState(onMinutesChange)
    val target = remember { DurationTarget { callback.value(it) } }
    val size = remember { intrinsicControlSize(countdownPicker()) }
    UIKitView(
        factory = {
            nativeControlContainer(countdownPicker().apply {
                addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged)
            }, fill = false)
        },
        modifier = modifier.size(size),
        update = { container ->
            val picker = container.subviews.first() as UIDatePicker
            val seconds = minutes.coerceIn(1, MAX_MINUTES) * 60.0
            if (picker.countDownDuration != seconds) picker.countDownDuration = seconds
            picker.overrideUserInterfaceStyle =
                if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            picker.semanticContentAttribute = if (layoutDirection == LayoutDirection.Rtl)
                UISemanticContentAttributeForceRightToLeft else UISemanticContentAttributeForceLeftToRight
            picker.setAccessibilityLabel(accessibilityLabel)
        },
        properties = nativeControlProperties(accessible = true),
    )
}

private class DurationTarget(val onChange: (Int) -> Unit) : NSObject() {
    // The countdown wheel never reports less than one minute.
    @ObjCAction fun changed(sender: UIDatePicker) {
        onChange((sender.countDownDuration / 60).toInt().coerceIn(1, MAX_MINUTES))
    }
}
