package com.mohamedrejeb.calf.ui.toggle

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import com.mohamedrejeb.calf.ui.utils.applyLayoutDirection
import com.mohamedrejeb.calf.ui.utils.applyTheme
import com.mohamedrejeb.calf.ui.utils.isDark
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import dev.veyra.nativecomponents.nativeControlBackground
import dev.veyra.nativecomponents.nativeControlContainer
import dev.veyra.nativecomponents.toUIColor
import platform.darwin.NSObject

/** Compatibility actual for Veyra's compiler; the control itself is UIKit's UISwitch. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AdaptiveSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier,
    thumbContent: (@Composable () -> Unit)?, enabled: Boolean, colors: SwitchColors,
    interactionSource: MutableInteractionSource) {
    val layoutDirection = LocalLayoutDirection.current
    val dark = isDark(MaterialTheme.colorScheme.background)
    val callback = rememberUpdatedState(onCheckedChange)
    val target = remember { SwitchTarget { callback.value?.invoke(it) } }
    val background = nativeControlBackground()
    // The interop area is cut through Compose; the container paints all of it with the colour
    // behind the row, so no black shows around or under the fixed-size (51×31) UISwitch.
    UIKitView(
        factory = {
            val control = UISwitch().apply { addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged) }
            nativeControlContainer(control, fill = false)
        },
        modifier = modifier.size(52.dp, 48.dp),
        update = { container ->
            container.backgroundColor = background.toUIColor()
            val it = container.subviews.first() as UISwitch
            it.setOn(checked, animated = false)
            it.enabled = enabled
            it.userInteractionEnabled = onCheckedChange != null
            it.applyLayoutDirection(layoutDirection)
            it.applyTheme(dark)
        },
        properties = UIKitInteropProperties(isNativeAccessibilityEnabled = onCheckedChange != null),
    )
}

@OptIn(BetaInteropApi::class)
private class SwitchTarget(val onChange: (Boolean) -> Unit) : NSObject() {
    @ObjCAction fun changed(sender: UISwitch) { onChange(sender.on) }
}
