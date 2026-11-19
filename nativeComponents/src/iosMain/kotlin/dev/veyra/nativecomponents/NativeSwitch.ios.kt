package dev.veyra.nativecomponents

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun NativeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier,
    enabled: Boolean,
) {
    val layoutDirection = LocalLayoutDirection.current
    val background = MaterialTheme.colorScheme.background
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
            it.applyControlAppearance(layoutDirection, background)
        },
        properties = nativeControlProperties(accessible = onCheckedChange != null),
    )
}

@OptIn(BetaInteropApi::class)
private class SwitchTarget(val onChange: (Boolean) -> Unit) : NSObject() {
    @ObjCAction fun changed(sender: UISwitch) { onChange(sender.on) }
}
