// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.fortem.audio.AudioEngine
import app.fortem.audio.AudioTimbre
import app.fortem.midi.MidiOutputEngine
import app.fortem.recording.*
import app.fortem.settings.AppLanguage
import app.fortem.settings.UserSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the real toolbar, hidden drawer and keyboard, without native audio or samples. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsExitIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private var exits = 0

    private var compactWindow by mutableStateOf(false)
    private var splitHeight by mutableStateOf(250)
    private var simulatedCornerPadding by mutableStateOf(0)

    private fun showScreen() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppLanguageProvider(AppLanguage.ENGLISH, {}) {
                MaterialTheme {
                    val context = LocalContext.current
                    val settings = remember { UserSettings(context) }
                    val midi = remember { MidiOutputEngine(context) }
                    DisposableEffect(midi) { onDispose { midi.close() } }
                    val recorder = remember {
                        PerformanceRecorder(object : PerformanceStorage {
                            override fun loadLast(): PerformanceRecording? = null
                            override fun saveLast(recording: PerformanceRecording) = Unit
                        }, AndroidMonotonicClock)
                    }
                    val silentAudio = remember { object : AudioEngine {
                        override fun start() = true
                        override fun stop() = Unit
                        override fun isRunning() = true
                        override fun setTimbre(timbre: AudioTimbre) = Unit
                        override fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float) = Unit
                        override fun retune(midiNote: Int, frequencyHz: Double) = Unit
                        override fun noteOff(midiNote: Int) = Unit
                    } }
                    Box(Modifier.size(
                        width = if (compactWindow) 390.dp else 840.dp,
                        height = if (compactWindow) splitHeight.dp else 393.dp,
                    ).testTag("window-host")) {
                        FortemScreen(silentAudio, {}, settings, midi, 0, true, recorder, { exits++ },
                            inMultiWindow = compactWindow,
                            cornerInsets = WindowInsets(0, 0, 0, simulatedCornerPadding))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(250)
    }

    @Test fun stationaryToolbarHoldShowsHintInsideTheSameWindowAndExitsOnce() {
        showScreen()
        val gear = compose.onNodeWithContentDescription("Settings")
        val originalBounds = gear.fetchSemanticsNode().boundsInRoot
        gear.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(900)
        val hint = compose.onNodeWithTag("exit-hold", useUnmergedTree = true)
        hint.assertIsDisplayed()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        val bounds = hint.fetchSemanticsNode().boundsInRoot
        assertTrue("The hint stays beside, rather than over, the gear", bounds.right < originalBounds.left)
        assertTrue(hint.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current > 0f)
        assertEquals(originalBounds, gear.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.advanceTimeBy(750)
        assertEquals(1, exits)
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(1, exits)
        gear.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("exit-hold", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("settings-panel", useUnmergedTree = true).assertIsNotDisplayed()
    }

    @Test fun compactSplitWindowMovesTheMapBelowTheKeysAndKeepsTheNoteInTheToolbar() {
        showScreen()
        val map = compose.onNodeWithTag("keyboard-map", useUnmergedTree = true)
        val piano = compose.onNodeWithContentDescription("Piano keyboard from A0 to C8")
        val note = compose.onNodeWithTag("active-note", useUnmergedTree = true)
        val pedal = compose.onNodeWithTag("pedal-control", useUnmergedTree = true)

        assertTrue(map.fetchSemanticsNode().boundsInRoot.bottom < piano.fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { compactWindow = true }
        compose.mainClock.advanceTimeBy(250)
        val pianoBounds = piano.fetchSemanticsNode().boundsInRoot
        val mapBounds = map.fetchSemanticsNode().boundsInRoot
        val noteBounds = note.fetchSemanticsNode().boundsInRoot
        val pedalBounds = pedal.fetchSemanticsNode().boundsInRoot
        assertTrue(pianoBounds.bottom < mapBounds.top)
        assertEquals(pianoBounds.width, mapBounds.width, 1f)
        assertTrue(mapBounds.height >= pianoBounds.width * .14f)
        assertTrue(noteBounds.bottom < pianoBounds.top)
        assertEquals(pedalBounds.center.y, noteBounds.center.y, 1f)

        compose.runOnIdle { compactWindow = false }
        compose.mainClock.advanceTimeBy(250)
        assertTrue(map.fetchSemanticsNode().boundsInRoot.bottom < piano.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun splitWindowFillsItsNewHeightWhenGrowingAndShrinking() {
        compactWindow = true
        simulatedCornerPadding = 72
        showScreen()
        val map = compose.onNodeWithTag("keyboard-map", useUnmergedTree = true)
        val host = compose.onNodeWithTag("window-host", useUnmergedTree = true)
        var previous = map.fetchSemanticsNode().boundsInRoot.bottom
        for (height in listOf(350, 190, 330)) {
            compose.runOnIdle { splitHeight = height }
            compose.mainClock.advanceTimeBy(250)
            val hostBounds = host.fetchSemanticsNode().boundsInRoot
            val mapBounds = map.fetchSemanticsNode().boundsInRoot
            // Only the 2 dp surface gutter remains, never a synthetic display-corner inset.
            assertEquals(hostBounds.bottom - hostBounds.width / 390f * 2f, mapBounds.bottom, 1f)
            assertTrue(mapBounds.bottom != previous)
            previous = mapBounds.bottom
        }
    }

    @Test fun gearIsCenteredAboveTheGapBetweenPedalAndUnlock() {
        showScreen()
        val gear = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val pedal = compose.onNodeWithTag("pedal-control", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val unlock = compose.onNodeWithTag("unlock-control", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals((pedal.center.x + unlock.center.x) / 2f, gear.center.x, 1f)
        assertTrue(gear.bottom <= pedal.top)
    }

    @Test fun anotherFingerOnTheKeyboardDoesNotSuppressTheExitHold() {
        showScreen()
        val gearCenter = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot.center
        val root = compose.onRoot()
        root.performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(width * .5f, height - 40f))
            down(1, gearCenter)
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("exit-hold", useUnmergedTree = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(700)
        assertEquals(1, exits)
        root.performTouchInput { up(1); up(0) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("exit-hold", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun cancelledToolbarHoldCanBeRetriedAndATapStillOpensSettings() {
        showScreen()
        val gear = compose.onNodeWithContentDescription("Settings")
        repeat(2) {
            gear.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(900)
            compose.onNodeWithTag("exit-hold", useUnmergedTree = true).assertIsDisplayed()
            gear.performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(1100)
            compose.onNodeWithTag("exit-hold", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(0, exits)
        }
        gear.performTouchInput { click() }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("settings-panel", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(0, exits)
    }
}
