// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.*
import org.junit.Test

class KeyboardViewportFollowerTest {
    @Test fun stationaryFingerFollowsViewportFramesWithoutPointerEvents() {
        val started = mutableListOf<Int>(); val stopped = mutableListOf<Int>()
        val ledger = KeyboardPressLedger(started::add, stopped::add) { false }
        val follower = KeyboardViewportFollower()
        val initial = KeyboardViewport(20f, 8f)
        ledger.press(1, pianoNoteAt(50f, 350f, 800f, 400f, initial)!!)
        follower.attach(initial) { moved ->
            // Палец неподвижен: во всём тесте координаты не меняются.
            ledger.move(1, pianoNoteAt(50f, 350f, 800f, 400f, moved))
        }
        listOf(20.2f, 20.8f, 21.2f, 21.8f).forEach { start ->
            follower.update(initial.copy(startWhiteKey = start))
        }
        assertEquals(listOf(55, 57, 59), started)
        assertEquals(listOf(55, 57), stopped)
        assertEquals(setOf(59), ledger.pressedNotes())
        follower.detach(); ledger.release(1)
        follower.update(initial.copy(startWhiteKey = 25f))
        assertEquals(listOf(55, 57, 59), stopped)
        assertTrue(ledger.pressedNotes().isEmpty())
        assertEquals(3, started.size) // После отпускания нет новой атаки.
    }

    @Test fun changingWidthAndDirectionKeepsLatestCoordinates() {
        val initial = KeyboardViewport(20f, 8f)
        var x = 50f
        val notes = mutableListOf<Int?>()
        val follower = KeyboardViewportFollower()
        follower.attach(initial) { notes += pianoNoteAt(x, 350f, 800f, 400f, it) }
        follower.update(initial.copy(startWhiteKey = 21f))
        x = 250f // Получен последний образец положения пальца до инерции.
        follower.update(initial.copy(startWhiteKey = 22f))
        follower.update(initial.copy(startWhiteKey = 21f, visibleWhiteKeys = 4f))
        follower.update(initial)
        assertEquals(listOf(57, 62, 59, 59), notes)
    }

    @Test fun blackKeysHavePriorityOnlyInTheirActualArea() {
        val viewport = KeyboardViewport(20f, 8f)
        assertEquals(56, pianoNoteAt(100f, 50f, 800f, 400f, viewport))
        assertEquals(58, pianoNoteAt(100f, 50f, 800f, 400f, viewport.copy(startWhiteKey = 21f)))
        assertEquals(57, pianoNoteAt(100f, 350f, 800f, 400f, viewport))
        assertNull(pianoNoteAt(-1f, 350f, 800f, 400f, viewport))
        assertNull(pianoNoteAt(50f, 401f, 800f, 400f, viewport))
    }

    @Test fun croppedEdgeSeparatorsAreHiddenAndWholeKeySeparatorsRemain() {
        val cropped = KeyboardViewport(20.8f, 8f)
        assertFalse(shouldDrawWhiteKeyDivider(21, cropped))
        assertTrue(shouldDrawWhiteKeyDivider(22, cropped))
        assertTrue(shouldDrawWhiteKeyDivider(27, cropped))
        assertFalse(shouldDrawWhiteKeyDivider(28, cropped))
        val whole = KeyboardViewport(20f, 8f)
        assertTrue(shouldDrawWhiteKeyDivider(21, whole))
        assertTrue(shouldDrawWhiteKeyDivider(27, whole))
    }
}
