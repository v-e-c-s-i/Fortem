// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPressLedgerTest {
    private val started = mutableListOf<Int>()
    private val stopped = mutableListOf<Int>()
    private val latched = mutableSetOf<Int>()
    private val ledger = KeyboardPressLedger(
        onNoteOn = started::add,
        onNoteOff = stopped::add,
        isLatched = latched::contains,
    )

    @Test
    fun threeSimultaneousPointersProduceThreeVisibleAndAudibleNotes() {
        ledger.press(pointerId = 1, midiNote = 60)
        ledger.press(pointerId = 2, midiNote = 64)
        ledger.press(pointerId = 3, midiNote = 67)

        assertEquals(setOf(60, 64, 67), ledger.pressedNotes())
        assertEquals(listOf(60, 64, 67), started)
    }

    @Test
    fun fourPointersCanBeReleasedIndependently() {
        ledger.press(pointerId = 1, midiNote = 60)
        ledger.press(pointerId = 2, midiNote = 62)
        ledger.press(pointerId = 3, midiNote = 64)
        ledger.press(pointerId = 4, midiNote = 65)

        ledger.release(pointerId = 3)

        assertEquals(setOf(60, 62, 65), ledger.pressedNotes())
        assertEquals(listOf(64), stopped)
    }

    @Test
    fun twoPointersOnOneKeyUseOneAudioVoiceUntilBothAreReleased() {
        ledger.press(pointerId = 1, midiNote = 60)
        ledger.press(pointerId = 2, midiNote = 60)
        ledger.release(pointerId = 1)

        assertEquals(setOf(60), ledger.pressedNotes())
        assertEquals(listOf(60), started)
        assertEquals(emptyList<Int>(), stopped)

        ledger.release(pointerId = 2)
        assertEquals(emptySet<Int>(), ledger.pressedNotes())
        assertEquals(listOf(60), stopped)
    }

    @Test
    fun movingOneFingerDoesNotDisturbOtherHeldNotes() {
        ledger.press(pointerId = 1, midiNote = 60)
        ledger.press(pointerId = 2, midiNote = 64)
        ledger.press(pointerId = 3, midiNote = 67)

        ledger.move(pointerId = 2, midiNote = 65)

        assertEquals(setOf(60, 65, 67), ledger.pressedNotes())
        assertEquals(listOf(64), stopped)
        assertEquals(listOf(60, 64, 67, 65), started)
    }
}
