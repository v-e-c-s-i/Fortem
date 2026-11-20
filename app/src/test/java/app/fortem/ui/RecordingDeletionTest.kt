// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Slow storage exposes transient layout/disabled-header regressions that fast I/O can hide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w840dp-h393dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingDeletionTest {
    @get:Rule val compose = createComposeRule()
    private val storage = TestStorage()
    private val deleted = CopyOnWriteArrayList<Long>()
    private var restored = 0
    private var open by mutableStateOf(true)

    private class TestStorage : PerformanceStorage {
        val ids = CopyOnWriteArrayList(listOf(1L, 2L, 3L))
        val trash = CopyOnWriteArrayList<Long>()
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var slow = false
        override fun loadLast(): PerformanceRecording? = null
        override fun saveLast(recording: PerformanceRecording) = Unit
        override fun listRecordings() = ids.map { RecordingSummary(it, 8_000_000_000L) }
        override fun moveToTrash(id: Long) {
            started.countDown()
            if (slow) check(proceed.await(5, TimeUnit.SECONDS)) { "Test did not release storage" }
            ids.remove(id)
            trash.add(id)
        }
        override fun restoreFromTrash(id: Long) { trash.remove(id); ids.add(id) }
    }

    private fun showList() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppLanguageProvider(AppLanguage.ENGLISH, {}) {
                MaterialTheme {
                    val context = LocalContext.current
                    val settings = remember { UserSettings(context) }
                    val recorder = remember { PerformanceRecorder(storage, AndroidMonotonicClock) }
                    val state = rememberLazyListState()
                    if (open) RecordingsDialog(recorder, settings, true, false, true, null,
                        state, { 0L }, false, {}, {},
                        { id, _ -> deleted.add(id) }, { restored++ }, { open = false })
                }
            }
        }
        awaitNode("recording-delete-3")
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun deletionKeepsHeaderAndViewportStableAndUndoLivesInTheScreenCorner() {
        storage.slow = true
        showList()
        val list = compose.onNodeWithTag("recording-list", useUnmergedTree = true)
        val before = list.fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("recordings-sort", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("recording-delete-3", useUnmergedTree = true).performClick()
        try {
            compose.waitUntil(5_000) { storage.started.count == 0L }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("recordings-sort", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithTag("recordings-close", useUnmergedTree = true).assertIsEnabled()
            compose.onNodeWithTag("recordings-loading", useUnmergedTree = true).assertDoesNotExist()
            assertEquals(before, list.fetchSemanticsNode().boundsInRoot)
        } finally { storage.proceed.countDown() }
        awaitNode("recording-undo")
        compose.onNodeWithTag("recording-delete-3", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(listOf(3L), deleted.toList())
        assertEquals(before, list.fetchSemanticsNode().boundsInRoot)
        assertEquals(header, compose.onNodeWithTag("recordings-sort", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        val undo = compose.onNodeWithTag("recording-undo", useUnmergedTree = true)
        undo.assertIsDisplayed()
        compose.onNode(hasTestTag("recording-undo") and
            hasAnyAncestor(hasTestTag("recordings-surface")), useUnmergedTree = true).assertDoesNotExist()
        val surface = compose.onNodeWithTag("recordings-surface", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Undo is at the screen edge, beyond the list window", undo.fetchSemanticsNode().boundsInRoot.right > surface.right + 40f)
        undo.performClick()
        awaitNode("recording-delete-3")
        assertEquals(1, restored)
        assertEquals(setOf(1L, 2L, 3L), storage.ids.toSet())
        assertTrue(storage.trash.isEmpty())
        assertEquals(before, list.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun twoRequestsInOneFrameCannotQueueASecondDeletion() {
        storage.slow = true
        showList()
        val first = compose.onNodeWithTag("recording-delete-3", useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val second = compose.onNodeWithTag("recording-delete-2", useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        try {
            // Deliberately invoke both before Compose can refresh the disabled row state.
            compose.runOnIdle { first(); second() }
            compose.waitUntil(5_000) { storage.started.count == 0L }
        } finally { storage.proceed.countDown() }
        awaitNode("recording-undo")
        assertEquals(listOf(3L), deleted.toList())
        assertTrue(storage.ids.contains(2L))
    }

    @Test fun closingTheListDuringAnAcceptedDeleteStillFinishesTheOperation() {
        storage.slow = true
        showList()
        compose.onNodeWithTag("recording-delete-3", useUnmergedTree = true).performClick()
        try {
            compose.waitUntil(5_000) { storage.started.count == 0L }
            compose.onNodeWithTag("recordings-close", useUnmergedTree = true).performClick()
            compose.runOnIdle { assertFalse("Close is available during the disk operation", open) }
            // Dialog removal is posted to Android's window queue after recomposition.
            compose.waitUntil(2_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithTag("recordings-surface", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            }
        } finally { storage.proceed.countDown() }
        compose.waitUntil(5_000) { deleted.isNotEmpty() }
        assertEquals(listOf(3L), deleted.toList())
        assertFalse(storage.ids.contains(3L))
        assertTrue(storage.trash.contains(3L))
    }
}
