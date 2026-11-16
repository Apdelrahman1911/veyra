package dev.veyra.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.veyra.model.LanguagePreference
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class KeyboardDismissalTest {
    @get:Rule val ui = createComposeRule()

    @Test fun outsideTapsDismissWithoutSwallowingButtonsOrBreakingFieldSwitches() {
        var hides = 0
        var actions = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hides++ }
        }
        ui.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                VeyraTheme(true, LanguagePreference.ENGLISH) {
                    Column(Modifier.size(390.dp, 720.dp).dismissKeyboardOnOutsideTap()) {
                        var first by remember { mutableStateOf("First") }
                        var second by remember { mutableStateOf("Second") }
                        OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth().testTag("first"))
                        OutlinedTextField(second, { second = it }, Modifier.fillMaxWidth().testTag("second"))
                        Button({ actions++ }, Modifier.testTag("action")) { Text("Search") }
                        Box(Modifier.fillMaxSize().testTag("outside"))
                    }
                }
            }
        }
        fun tap(tag: String) = ui.onNodeWithTag(tag).performTouchInput { click() }
        tap("first")
        ui.onNodeWithTag("first").assertIsFocused()
        tap("first")
        ui.onNodeWithTag("first").assertIsFocused()
        tap("second")
        ui.onNodeWithTag("second").assertIsFocused()
        val before = hides
        tap("outside")
        ui.onNodeWithTag("second").assertIsNotFocused()
        assertTrue(hides > before)
        tap("first")
        tap("action")
        ui.onNodeWithTag("first").assertIsNotFocused()
        assertEquals(1, actions, "The original button still receives the same tap")
        tap("first")
        ui.onNodeWithTag("first").performTouchInput {
            swipe(Offset(width * .2f, height / 2f), Offset(width * .8f, height / 2f), durationMillis = 500)
        }
        ui.onNodeWithTag("first").assertIsFocused()
    }
}
