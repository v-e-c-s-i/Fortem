// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import app.fortem.ui.KeyboardViewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReleaseDefaultsTest {
    @Test fun freshInstallUsesPianoEqualTemperamentCAndNoGestureLatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(UserSettings.FILE_NAME, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val settings = UserSettings(context)
            assertEquals(TuningSystem.EQUAL_TEMPERAMENT, settings.loadTuningSystem())
            assertEquals(0, settings.loadTonicPitchClass())
            assertEquals(AudioTimbre.PIANO, settings.loadAudioTimbre())
            assertFalse(settings.loadLatchGesturesEnabled())
            assertEquals(KeyboardViewport.DEFAULT, settings.loadKeyboardViewport())
            val personalRange = KeyboardViewport(10f, 6f)
            settings.saveKeyboardViewport(personalRange)
            settings.saveLatchGesturesEnabled(true)
            settings.saveTonicPitchClass(7)
            settings.saveTuningSystem(TuningSystem.PYTHAGOREAN)
            settings.saveAudioTimbre(AudioTimbre.PURE_TONE)
            val reloaded = UserSettings(context)
            assertTrue(reloaded.loadLatchGesturesEnabled())
            assertEquals(personalRange, reloaded.loadKeyboardViewport())
            assertEquals(7, reloaded.loadTonicPitchClass())
            assertEquals(TuningSystem.PYTHAGOREAN, reloaded.loadTuningSystem())
            assertEquals(AudioTimbre.PURE_TONE, reloaded.loadAudioTimbre())
        } finally { preferences.edit().clear().commit() }
    }
}
