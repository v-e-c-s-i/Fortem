// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.fortem.BuildConfig
import app.fortem.midi.MidiOutputEngine
import app.fortem.settings.AppLanguage
import app.fortem.settings.UserSettings
import app.fortem.tuning.TuningSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise the real narrow settings panel without loading samples or starting audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSectionsTest {
    @get:Rule val compose = createComposeRule()

    private fun showPanel(cutout: WindowInsets = WindowInsets(0, 0, 0, 0)) {
        compose.setContent {
            AppLanguageProvider(AppLanguage.RUSSIAN, {}) {
                MaterialTheme {
                    val context = LocalContext.current
                    val settings = remember { UserSettings(context) }
                    val midi = remember { MidiOutputEngine(context) }
                    DisposableEffect(midi) { onDispose { midi.close() } }
                    SettingsPanel(userSettings = settings, isVisible = true,
                        scalaScale = null, scalaScales = emptyList(), visibleScalaIds = emptySet(),
                        onScalaImported = {}, onScalaDeleted = {}, onVisibilityChanged = { _, _ -> },
                        midiOutput = midi, midiConnected = false, midiLocalSound = true, onMidiLocalSoundChanged = {},
                        visibleTunings = setOf(TuningSystem.EQUAL_TEMPERAMENT), referenceFrequencyHz = 440.0,
                        retriggerHeldNotesDuringPlayback = false, interfaceScale = 1f, onInterfaceScaleChanged = {},
                        latchGesturesEnabled = true, onLatchGesturesChanged = {}, onOpenTrash = {},
                        onReferenceFrequencyChanged = {}, onRetriggerChanged = {}, cutoutInsets = cutout,
                        modifier = Modifier.width(320.dp).height(370.dp))
                }
            }
        }
    }

    @Test fun leftScreenNotchDoesNotPushRightDrawerContentOffCenter() {
        showPanel(WindowInsets(left = 48, top = 0, right = 0, bottom = 0))
        val panel = compose.onNodeWithTag("settings-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("settings-section-interface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Content margins are balanced inside the drawer, irrespective of a left-screen notch",
            card.left - panel.left, panel.right - card.right, 1f)
    }

    @Test fun distinctCategoriesRemainReachableAndPianoCreditsEndBesideTheVersion() {
        showPanel()
        compose.onNodeWithText("Gear: tap for Settings; hold until the ring fills to exit.").assertDoesNotExist()
        compose.onNodeWithText("Шестерёнка: нажмите для настроек; удерживайте до заполнения круга для выхода.").assertDoesNotExist()
        for (id in listOf("interface", "tunings", "scala", "reference", "midi", "held", "recordings", "symbols")) {
            compose.onNodeWithTag("settings-section-$id", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Fortem · ${BuildConfig.VERSION_NAME}", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ФОРТЕПИАНО", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Salamander Grand Piano", substring = true, useUnmergedTree = true).assertIsDisplayed()
    }
}
