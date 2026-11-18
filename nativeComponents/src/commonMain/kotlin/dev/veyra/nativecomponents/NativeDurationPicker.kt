package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A duration picker in whole minutes. On iOS this is UIKit's countdown wheel (`UIDatePicker` in
 * count-down-timer mode: hours and minutes, up to 23 h 59 min). The toolkit's time picker selects
 * a clock time with an AM/PM column, which is the wrong control for a duration, so this adapter
 * hosts the countdown mode of the same UIKit control. Other platforms render [fallback].
 */
@Composable
expect fun NativeDurationPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
)
