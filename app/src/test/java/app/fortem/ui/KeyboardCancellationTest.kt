// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
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
class KeyboardCancellationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelledUpwardGestureDoesNotLeaveANewLatchButCompletedGestureDoes() {
        var latched = emptySet<Int>()
        val sounding = mutableSetOf<Int>()
        compose.setContent {
            AppLanguageProvider(AppLanguage.ENGLISH, {}) {
            MaterialTheme {
                Box(Modifier.size(700.dp, 340.dp)) {
                    PianoKeyboard(KeyboardViewport(23f, 7f), { sounding += it }, { sounding -= it }, {},
                        onLatchedNotesChanged = { latched = it }, modifier = Modifier.testTag("piano"))
                }
            }
            }
        }
        val piano = compose.onNodeWithTag("piano")
        piano.performTouchInput {
            down(Offset(width * .5f, height * .88f))
            moveTo(Offset(width * .5f, height * .66f), delayMillis = 150)
        }
        assertTrue("The upward movement activates a latch before release", latched.isNotEmpty())
        piano.performTouchInput { cancel() }
        compose.waitForIdle()
        assertTrue("Android cancellation removes the new lock", latched.isEmpty())
        assertTrue("Cancelled contacts do not keep sounding", sounding.isEmpty())
        piano.performTouchInput {
            down(Offset(width * .5f, height * .88f))
            moveTo(Offset(width * .5f, height * .66f), delayMillis = 150)
            up()
        }
        assertTrue("Normal release preserves an intentional lock", latched.isNotEmpty())
        assertEquals(latched, sounding)
        val previousLock = latched
        piano.performTouchInput {
            down(Offset(width * .22f, height * .88f))
            moveTo(Offset(width * .22f, height * .66f), delayMillis = 150)
        }
        assertTrue(latched.size > previousLock.size)
        piano.performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals("Older intentional locks survive a later cancelled contact", previousLock, latched)
        assertEquals(previousLock, sounding)
    }
}
