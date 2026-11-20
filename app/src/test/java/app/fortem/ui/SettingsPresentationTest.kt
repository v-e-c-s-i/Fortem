// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.fortem.settings.AppLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingSlidesWithoutRelayingOutSettingsContent() {
        var visible by mutableStateOf(false)
        var measures = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(800.dp, 360.dp)) {
                    SettingsDrawer(visible, { visible = false }) {
                        androidx.compose.ui.layout.Layout(content = { Text("Settings") }) { measurables, constraints ->
                            measures++
                            val child = measurables.single().measure(constraints)
                            layout(child.width, child.height) { child.place(0, 0) }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible = true }
        compose.mainClock.advanceTimeBy(64)
        val first = compose.onNodeWithTag("settings-drawer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        val before = measures
        compose.mainClock.advanceTimeBy(96)
        val middle = compose.onNodeWithTag("settings-drawer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue("No repeated layout while moving", measures <= before + 1)
        compose.mainClock.advanceTimeBy(200)
        val last = compose.onNodeWithTag("settings-drawer", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        assertTrue("The panel slides left progressively", first > middle && middle > last)
        // A modal child insertion/removal may require one pass; per-frame remeasurement is forbidden.
        assertTrue("No repeated layout during animation", measures <= before + 1)
    }

    private var opens = 0
    private var exits = 0

    private fun showGear() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppLanguageProvider(AppLanguage.ENGLISH, {}) {
                MaterialTheme { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopEnd) {
                    SettingsControl({ opens++ }, { exits++ })
                } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    @Test fun gearTapOpensSettingsWithoutExiting() {
        showGear()
        compose.onNodeWithContentDescription("Settings").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, opens)
        assertEquals(0, exits)
        compose.onNodeWithTag("exit-hold").assertDoesNotExist()
    }

    @Test fun quickTapHasNoExitTimerAndAReleaseInTheNeutralIntervalDoesNothing() {
        showGear()
        val gear = compose.onNodeWithContentDescription("Settings")
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("exit-hold").assertDoesNotExist()
        gear.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, opens)
        assertEquals(0, exits)
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("exit-hold").assertDoesNotExist()
        gear.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals("An interrupted hold must not open settings", 1, opens)
        assertEquals(0, exits)
    }

    @Test fun partialHoldReleaseAndSystemCancellationNeverExitLater() {
        showGear()
        val gear = compose.onNodeWithContentDescription("Settings")
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(900)
        val info = compose.onNodeWithTag("exit-hold").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo]
        assertTrue("The ring visibly fills before completion", info.current in .2f.. .9f)
        gear.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("exit-hold").assertDoesNotExist()
        assertEquals(0, opens)
        assertEquals(0, exits)
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        gear.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithTag("exit-hold").assertDoesNotExist()
        assertEquals(0, opens)
        assertEquals(0, exits)
    }

    @Test fun slightFingerMovementStillCompletesTheRingExactlyOnce() {
        showGear()
        val gear = compose.onNodeWithContentDescription("Settings")
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(250)
        gear.performTouchInput {
            moveTo(center + androidx.compose.ui.geometry.Offset(width * .3f, -height * .2f), delayMillis = 250)
        }
        compose.mainClock.advanceTimeBy(1400)
        assertEquals(0, opens)
        assertEquals(1, exits)
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("Holding longer cannot trigger exit twice", 1, exits)
        gear.performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("A completed exit must not also open settings", 0, opens)
    }
}
