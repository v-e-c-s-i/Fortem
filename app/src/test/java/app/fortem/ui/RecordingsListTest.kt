// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.fortem.recording.RecordingSummary
import app.fortem.settings.AppLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose layout and scrolling, with a small landscape viewport and no audio engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingsListTest {
    @get:Rule val compose = createComposeRule()
    private val firstId = 946685100001L
    private val takes = List(15) { RecordingSummary(firstId + it, 8_000_000_000L) }
    private var playing by mutableStateOf<Long?>(null)
    private var position by mutableLongStateOf(0L)
    private lateinit var state: LazyListState

    private fun showList(language: AppLanguage = AppLanguage.ENGLISH, fontScale: Float = 1f) {
        // Marquee/drawing animations must not auto-advance the test clock forever.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppLanguageProvider(language, {}) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    state = rememberLazyListState()
                    RecordingsList(takes, state, playing, { position }, false, true,
                        onPlay = { playing = if (playing == it) null else it },
                        onExport = {}, onRerecord = {}, onRerecordHint = {}, onDelete = {},
                        modifier = Modifier.width(440.dp).height(210.dp))
                }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun finalRowRemainsFullyVisibleWhilePlaybackStartsTicksAndStops() {
        showList()
        val id = takes.last().id
        compose.onNodeWithTag("recording-list").performScrollToIndex(14)
        compose.mainClock.advanceTimeByFrame()
        val before = compose.onNodeWithTag("recording-row-$id").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("recording-list").fetchSemanticsNode().boundsInRoot
        assertTrue(before.top >= viewport.top && before.bottom <= viewport.bottom)
        val anchor = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
        compose.onNodeWithTag("recording-play-$id").performClick()
        compose.runOnIdle { assertEquals(id, playing) }
        // A paused clock needs time for snapshot delivery and the next composition frame.
        // Short production titles do not keep a marquee frame scheduled as test demos did.
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithTag("recording-play-$id").assertContentDescriptionEquals("Stop")
        repeat(30) { tick ->
            compose.runOnIdle { position = tick * 100_000_000L }
            compose.mainClock.advanceTimeByFrame()
        }
        val after = compose.onNodeWithTag("recording-row-$id").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        assertEquals(anchor, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
        compose.onNodeWithTag("recording-play-$id").performClick()
        compose.runOnIdle { assertNull(playing) }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        compose.onNodeWithTag("recording-play-$id").assertContentDescriptionEquals("Play")
        assertEquals(before, compose.onNodeWithTag("recording-row-$id").fetchSemanticsNode().boundsInRoot)
        assertFalse(state.canScrollForward)
    }

    @Test fun draggingDuringPlaybackCanReachTheEndWithoutJumpingBack() {
        showList()
        compose.runOnIdle { playing = takes.first().id }
        repeat(8) {
            compose.onNodeWithTag("recording-list").performTouchInput { swipeUp(durationMillis = 350) }
            compose.mainClock.advanceTimeBy(600)
            compose.runOnIdle { position += 100_000_000L }
        }
        val last = compose.onNodeWithTag("recording-row-${takes.last().id}").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("recording-list").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top >= viewport.top && last.bottom <= viewport.bottom)
        assertFalse(state.canScrollForward)
    }

    @Test fun longRussianTitleStaysBeforePlayAtLargeFontWhileTimeChanges() {
        showList(AppLanguage.RUSSIAN, fontScale = 1.5f)
        val id = takes.last().id
        compose.onNodeWithTag("recording-list").performScrollToIndex(14)
        compose.mainClock.advanceTimeByFrame()
        val title = compose.onNodeWithTag("recording-title-$id", useUnmergedTree = true).fetchSemanticsNode()
        val button = compose.onNodeWithTag("recording-play-$id").fetchSemanticsNode()
        assertTrue(title.boundsInRoot.right <= button.boundsInRoot.left)
        assertTrue(title.boundsInRoot.width > 0f)
        compose.onNodeWithTag("recording-play-$id").performClick()
        compose.runOnIdle { position = 3_000_000_000L }
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(title.boundsInRoot, compose.onNodeWithTag("recording-title-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        assertEquals(button.boundsInRoot, compose.onNodeWithTag("recording-play-$id").fetchSemanticsNode().boundsInRoot)
    }
}
