// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the verticalScroll path used by Settings, including a real touch interruption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsGlideDeceleratesAndANewTouchStopsIt() {
        lateinit var state: ScrollState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                state = rememberScrollState()
                Column(Modifier.width(300.dp).height(210.dp).testTag("settings-scroll")
                    .verticalScroll(state, flingBehavior = rememberSmoothScrollFlingBehavior())) {
                    repeat(60) { Text("Setting $it", Modifier.height(48.dp)) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val node = compose.onNodeWithTag("settings-scroll")
        node.performTouchInput { swipeUp(durationMillis = 180) }
        compose.mainClock.advanceTimeBy(64)
        val a = state.value
        compose.mainClock.advanceTimeBy(100)
        val b = state.value
        compose.mainClock.advanceTimeBy(100)
        val c = state.value
        assertTrue("The list keeps gliding after release", b > a && c > b)
        assertTrue("The glide slows smoothly", c - b <= b - a + 2)
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(64)
        val stopped = state.value
        compose.mainClock.advanceTimeBy(300)
        assertEquals("A new touch interrupts momentum", stopped, state.value)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(stopped, state.value)
    }
}
