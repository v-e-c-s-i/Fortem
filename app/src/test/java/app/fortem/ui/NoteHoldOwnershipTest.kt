// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.*
import org.junit.Test

class NoteHoldOwnershipTest {
    @Test fun unlockingManualNotesPreservesPedalNotesAndPressedKeys() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.release(1)
        pedal.press(2, 64, 10); pedal.release(2)
        val before = pedal.heldNotes
        assertEquals(setOf(67), notesWithoutRemainingHold(
            released = setOf(60, 65, 67), pressed = setOf(65), retained = before))
        assertEquals(before, pedal.heldNotes)
        assertEquals(setOf(60), pedal.releaseDue(160))
        assertEquals(setOf(64), pedal.heldNotes)
    }

    @Test fun turningOffPedalDoesNotReleaseManualLocks() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.release(1)
        pedal.press(2, 64, 10); pedal.release(2)
        val released = pedal.heldNotes
        pedal.changeEnabled(false)
        assertEquals(setOf(64), notesWithoutRemainingHold(released, emptySet(), setOf(60)))
        assertTrue(pedal.heldNotes.isEmpty())
    }
}
