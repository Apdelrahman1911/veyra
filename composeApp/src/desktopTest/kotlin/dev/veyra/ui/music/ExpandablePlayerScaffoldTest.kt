package dev.veyra.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import dev.veyra.model.LanguagePreference
import dev.veyra.ui.design.VeyraTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real sheet input over authored slots; no network or audio ownership is fabricated. */
@OptIn(ExperimentalTestApi::class)
class ExpandablePlayerScaffoldTest {
    @get:Rule val ui = createComposeRule()

    private class Probe {
        var expanded by mutableStateOf(false)
        var changes = 0
        val rootCompositions = AtomicInteger()
        val bodyCompositions = AtomicInteger()
        fun change(open: Boolean) { expanded = open; changes++ }
    }

    private fun scene(language: LanguagePreference = LanguagePreference.ENGLISH): Probe {
        val probe = Probe()
        ui.setContent {
            SideEffect { probe.rootCompositions.incrementAndGet() }
            VeyraTheme(true, language) {
                ExpandablePlayerScaffold(
                    expanded = probe.expanded, hasPlayer = true, dragEnabled = true,
                    onExpandedChange = probe::change, modifier = Modifier.size(390.dp, 720.dp),
                    compactPlayer = {
                        Button({ probe.change(true) }, Modifier.fillMaxWidth().height(80.dp).testTag("compact-player")) { Text("Open") }
                    },
                    bottomBar = { Box(Modifier.fillMaxWidth().height(72.dp)) { Text("Tabs") } },
                    expandedPlayer = {
                        Box(Modifier.fillMaxSize().testTag("expanded-player")) {
                            Button({ probe.change(false) }, Modifier.align(Alignment.Center)) { Text("Close") }
                        }
                    },
                ) {
                    var count by remember { mutableStateOf(0) }
                    SideEffect { probe.bodyCompositions.incrementAndGet() }
                    Column {
                        Button({ count++ }) { Text("Keep $count") }
                    }
                }
            }
        }
        ui.waitForIdle()
        return probe
    }

    @Test fun tappingPlayerRetainsTheUnderlyingScreenAndHidesItsAccessibilityUntilClosed() {
        val probe = scene()
        ui.onNodeWithText("Keep 0").performClick()
        ui.onNodeWithText("Keep 1").performClick()
        ui.onNodeWithText("Open").performClick()
        ui.waitForIdle()
        ui.onNodeWithTag("expanded-player").assertIsDisplayed()
        ui.onNodeWithText("Keep 2").assertDoesNotExist()
        ui.onNodeWithText("Close").performClick()
        ui.waitForIdle()
        ui.onNodeWithText("Keep 2").assertIsDisplayed()
        assertFalse(probe.expanded)
        assertEquals(2, probe.changes)
    }

    @Test fun railWithoutAnActivePlayerLeavesNoReservedBottomGap() {
        ui.setContent {
            VeyraTheme(true, LanguagePreference.ENGLISH) {
                ExpandablePlayerScaffold(
                    expanded = false, hasPlayer = false, dragEnabled = false,
                    onExpandedChange = {}, modifier = Modifier.size(800.dp, 720.dp),
                    compactPlayer = {}, bottomBar = {}, expandedPlayer = {},
                ) { Box(Modifier.fillMaxSize().testTag("empty-player-body")) }
            }
        }
        ui.waitForIdle()
        val body = ui.onNodeWithTag("empty-player-body").fetchSemanticsNode().boundsInRoot
        val host = ui.onNodeWithTag("player-sheet-host").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(body.bottom - host.bottom) < 1f, "An empty rail player must reserve zero pixels")
    }

    @Test fun dragCancellationAndRepeatedMovementOnlyPublishSettledTransitions() = assertDragSession(LanguagePreference.ENGLISH)
    @Test fun verticalPlayerGesturesAlsoWorkInArabic() = assertDragSession(LanguagePreference.ARABIC)

    private fun assertDragSession(language: LanguagePreference) {
        val probe = scene(language)
        val compact = ui.onNodeWithTag("compact-player").fetchSemanticsNode().boundsInRoot
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        ui.onRoot().performTouchInput {
            swipe(compact.center, compact.center - Offset(0f, 20f), durationMillis = 800)
        }
        ui.waitForIdle()
        assertFalse(probe.expanded)
        assertEquals(0, probe.changes)
        val rootBefore = probe.rootCompositions.get()
        val bodyBefore = probe.bodyCompositions.get()
        val start = compact.center
        val end = Offset(start.x, root.top + 20f)
        ui.onRoot().performTouchInput {
            down(start)
            repeat(120) { index ->
                val fraction = (index + 1) / 120f
                moveTo(start + (end - start) * fraction, delayMillis = 8)
            }
            up()
        }
        ui.waitForIdle()
        assertTrue(probe.expanded)
        ui.onNodeWithTag("expanded-player").assertIsDisplayed()
        ui.onRoot().performTouchInput {
            swipe(Offset(root.center.x, root.top + 40f), Offset(root.center.x, root.bottom - 20f), durationMillis = 800)
        }
        ui.waitForIdle()
        assertFalse(probe.expanded)
        assertEquals(2, probe.changes)
        val rootDelta = probe.rootCompositions.get() - rootBefore
        val bodyDelta = probe.bodyCompositions.get() - bodyBefore
        println("PLAYER_DRAG language=$language moves=120 rootCompositions=$rootDelta bodyCompositions=$bodyDelta settledChanges=${probe.changes}")
        assertTrue(rootDelta <= 6, "Pointer moves must not recompose the application root: $rootDelta")
        assertTrue(bodyDelta <= 6, "Pointer moves must not recompose the underlying screen: $bodyDelta")
    }
}
