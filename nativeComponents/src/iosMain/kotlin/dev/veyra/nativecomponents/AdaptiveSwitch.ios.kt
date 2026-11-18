package com.mohamedrejeb.calf.ui.toggle

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.coerceAtLeast
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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import dev.veyra.nativecomponents.intrinsicControlSize
import dev.veyra.nativecomponents.nativeControlContainer
import dev.veyra.nativecomponents.nativeControlProperties
import dev.veyra.nativecomponents.LocalNativeControlsCovered
import platform.darwin.NSObject

/** Compatibility actual for Veyra's compiler; the control itself is UIKit's UISwitch. */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun AdaptiveSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier,
    thumbContent: (@Composable () -> Unit)?, enabled: Boolean, colors: SwitchColors,
    interactionSource: MutableInteractionSource) {
    val layoutDirection = LocalLayoutDirection.current
    val dark = isDark(MaterialTheme.colorScheme.background)
    val covered = LocalNativeControlsCovered.current
    val callback = rememberUpdatedState(onCheckedChange)
    val target = remember { SwitchTarget { callback.value?.invoke(it) } }
    // The host is exactly as wide as the switch UIKit draws on this iOS release (never a fixed
    // guess that would clip it), and at least 48 dp tall/wide as a touch target.
    val intrinsic = remember { intrinsicControlSize(UISwitch()) }
    UIKitView(
        factory = {
            val control = UISwitch().apply { addTarget(target, NSSelectorFromString("changed:"), UIControlEventValueChanged) }
            nativeControlContainer(control, fill = false)
        },
        modifier = modifier.size(intrinsic.width.coerceAtLeast(48.dp), intrinsic.height.coerceAtLeast(48.dp)),
        update = { container ->
            container.hidden = covered
            val it = container.subviews.first() as UISwitch
            it.setOn(checked, animated = false)
            it.enabled = enabled
            it.userInteractionEnabled = onCheckedChange != null
            it.applyLayoutDirection(layoutDirection)
            it.applyTheme(dark)
        },
        properties = nativeControlProperties(accessible = onCheckedChange != null),
    )
}

@OptIn(BetaInteropApi::class)
private class SwitchTarget(val onChange: (Boolean) -> Unit) : NSObject() {
    @ObjCAction fun changed(sender: UISwitch) { onChange(sender.on) }
}
