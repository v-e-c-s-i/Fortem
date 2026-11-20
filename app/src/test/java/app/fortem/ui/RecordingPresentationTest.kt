// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.fortem.recording.RecordingSummary
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
class RecordingPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingRevealsPlayingRowOnceAndIdleOpeningStartsAtTop() {
        val takes = List(15) { RecordingSummary(it.toLong() + 1, 8_000_000_000L) }
        lateinit var state: LazyListState
        var open by mutableStateOf(false)
        var ready by mutableStateOf(false)
        var playing by mutableStateOf<Long?>(12L)
        compose.setContent {
            state = rememberLazyListState()
            if (open) {
                RevealRecordingOnOpen(if (ready) takes else emptyList(), ready, playing, state)
                LazyColumn(Modifier.height(200.dp).testTag("list"), state = state) {
                    items(takes, key = { it.id }) { Text("Take ${it.id}", Modifier.height(56.dp)) }
                }
            }
        }
        compose.runOnIdle { open = true }
        compose.runOnIdle { ready = true }
        compose.waitForIdle()
        assertEquals(11, state.firstVisibleItemIndex)
        compose.onNodeWithTag("list").performScrollToIndex(2)
        compose.runOnIdle { playing = 14L }
        compose.waitForIdle()
        assertEquals("Changing playback while open must not take over scrolling", 2, state.firstVisibleItemIndex)
        compose.runOnIdle { open = false }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertTrue(state.layoutInfo.visibleItemsInfo.any { it.key == 14L })
        compose.runOnIdle { open = false; playing = null }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
    }

    @Test fun outsideTapClosesRecordingsWhileInsideTapDoesNot() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                RecordingsDialogFrame(true, { dismissed = true }) { Text("Recordings") }
            }
        }
        compose.onNodeWithTag("recordings-surface").performTouchInput { click(center) }
        assertFalse(dismissed)
        compose.onNodeWithTag("recordings-backdrop").performTouchInput { click(Offset(2f, center.y)) }
        assertTrue(dismissed)
    }

    @Test fun progressRedrawsBetweenClockTicksWithoutRecomposingTheRow() {
        var nanos by mutableLongStateOf(0L)
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SideEffect { compositions++ }
            PlaybackProgress({ nanos }, 1_000_000_000L, Color.Red,
                Modifier.size(200.dp, 40.dp).background(Color.Black).testTag("progress"))
        }
        compose.mainClock.advanceTimeByFrame()
        val before = compositions
        // Six successive display frames, all inside the former 100 ms update interval.
        repeat(6) { index ->
            compose.runOnIdle { nanos = (index + 1) * 16_000_000L }
            compose.mainClock.advanceTimeByFrame()
            val pixels = compose.onNodeWithTag("progress").captureToImage().toPixelMap()
            val expectedEdge = (pixels.width * nanos / 1_000_000_000L).toInt()
            assertTrue(pixels[expectedEdge / 2, pixels.height / 2].red > .9f)
            assertTrue(pixels[(expectedEdge + 4).coerceAtMost(pixels.width - 1), pixels.height / 2].red < .1f)
        }
        assertEquals("Clock reads stay in drawing", before, compositions)
    }
}
