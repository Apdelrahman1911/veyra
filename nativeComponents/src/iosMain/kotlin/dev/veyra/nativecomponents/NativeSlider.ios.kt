package dev.veyra.nativecomponents

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import io.github.apdelrahman1911.kira.ui.utils.toUIColor
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.roundToInt

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NativeSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier,
    enabled: Boolean, valueRange: ClosedFloatingPointRange<Float>, steps: Int,
    onValueChangeFinished: (() -> Unit)?, colors: SliderColors, interactionSource: MutableInteractionSource) {
    val layoutDirection = LocalLayoutDirection.current
    val background = MaterialTheme.colorScheme.background
    val covered = LocalNativeControlsCovered.current
    val accessibility = LocalNativeControlAccessibility.current
    val callback = rememberUpdatedState(onValueChange)
    val finished = rememberUpdatedState(onValueChangeFinished)
    val range = rememberUpdatedState(valueRange)
    val increments = rememberUpdatedState(steps)
    val target = remember {
        SliderTarget { raw ->
            val count = increments.value
            val fraction = if (count > 0) (raw * (count + 1)).roundToInt().toFloat() / (count + 1) else raw
            val current = range.value
            callback.value(current.start + fraction * (current.endInclusive - current.start))
        }.also { it.onFinish = { finished.value?.invoke() } }
    }
    UIKitView(
        factory = { nativeControlContainer(UISlider().apply {
            minimumValue = 0f; maximumValue = 1f
            addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged)
            addTarget(target, NSSelectorFromString("finished:"), UIControlEventTouchUpInside or UIControlEventTouchUpOutside or UIControlEventTouchCancel)
        }, fill = true) },
        modifier = modifier,
        update = { container ->
            container.hidden = covered
            val it = container.subviews.first() as UISlider
            if (!it.tracking) it.value = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            it.enabled = enabled
            it.applyControlAppearance(layoutDirection, background)
            // The filled part follows the app's theme colour rather than the system blue.
            it.minimumTrackTintColor = colors.activeTrackColor.toUIColor()
            it.accessibilityLabel = accessibility?.label
            it.accessibilityValue = accessibility?.value
            it.accessibilityLanguage = accessibility?.languageTag
        },
        properties = nativeControlProperties(accessible = true),
    )
}

@OptIn(BetaInteropApi::class)
private class SliderTarget(val onChange: (Float) -> Unit) : NSObject() {
    var onFinish: () -> Unit = {}
    @ObjCAction fun changed(sender: UISlider) {
        onChange(sender.value)
        // Accessibility and keyboard adjustments do not emit touch-up events.
        if (!sender.tracking) onFinish()
    }
    @ObjCAction fun finished(sender: UISlider) { onFinish() }
}
