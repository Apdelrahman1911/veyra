package dev.veyra.nativecomponents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** [Prominent] is a screen's main action (filled); [Secondary] is any other action (gray, tinted label). */
enum class NativeButtonStyle { Prominent, Secondary }

/**
 * A labelled action button. On iOS this is a real UIButton in the standard filled or gray
 * configuration (capsule), tinted with [tint], with an optional SF Symbol [symbol] and the
 * system's Dynamic Type font. The host is at least 48 dp tall and as
 * wide as the button unless [modifier] sets a width. Other platforms render [fallback] unchanged.
 */
@Composable
expect fun NativeButton(
    title: String,
    onClick: () -> Unit,
    style: NativeButtonStyle,
    tint: Color,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    enabled: Boolean = true,
    fallback: @Composable () -> Unit,
)
