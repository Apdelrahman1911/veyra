package dev.veyra.ui.design

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private class FocusedInputBounds {
    var root: LayoutCoordinates? = null
    var focused: LayoutCoordinates? = null
}

/** Observe taps without consuming buttons, text selection, scrolling or player drags. */
@Composable
internal fun Modifier.dismissKeyboardOnOutsideTap(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bounds = remember { FocusedInputBounds() }
    return onGloballyPositioned { bounds.root = it }
        .onFocusedBoundsChanged { bounds.focused = it }
        .pointerInput(focus, keyboard) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var moved = false
                var multiplePointers = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.size > 1) multiplePointers = true
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed && !moved && !multiplePointers) {
                        val root = bounds.root
                        val focused = bounds.focused
                        val insideFocused = root?.isAttached == true && focused?.isAttached == true &&
                            root.localBoundingBoxOf(focused, clipBounds = false).contains(change.position)
                        if (!insideFocused) {
                            focus.clearFocus()
                            keyboard?.hide()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
}
