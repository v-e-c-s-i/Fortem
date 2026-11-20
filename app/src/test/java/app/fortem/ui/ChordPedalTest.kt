// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.*
import org.junit.Test

class ChordPedalTest {
    @Test fun disablingGestureLatchesReturnsHeldFingersToPedalWithoutClearingTheChord() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.release(1)
        pedal.press(2, 64, 100); pedal.manualLatch(2)
        pedal.restorePedalContacts()
        pedal.release(2)
        assertEquals(setOf(60, 64), pedal.heldNotes)
        assertEquals(setOf(60), pedal.releaseDue(250))
        assertEquals(setOf(64), pedal.heldNotes)
    }

    @Test fun shortTriadIsHeldAndNewChordStartsBeforeOldOneIsReleased() {
        val pedal = ChordPedal(true)
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val ledger = KeyboardPressLedger(started::add, stopped::add) { it in pedal.heldNotes }
        listOf(57, 60, 64).forEachIndexed { index, note ->
            pedal.press(index.toLong(), note, 0)
            ledger.press(index.toLong(), note)
        }
        for (id in 0L..2L) { pedal.release(id); ledger.release(id) }
        ledger.releaseAll()
        assertEquals(setOf(57, 60, 64), pedal.heldNotes)
        assertTrue(stopped.isEmpty())
        pedal.press(3, 59, 100); ledger.press(3, 59)
        pedal.press(4, 62, 105); ledger.press(4, 62)
        assertEquals(listOf(57, 60, 64, 59, 62), started)
        assertEquals(250L, pedal.nextReleaseAtMillis)
        assertTrue(pedal.releaseDue(249).isEmpty())
        val due = pedal.releaseDue(250)
        (due - ledger.pressedNotes()).forEach(stopped::add)
        assertEquals(setOf(57, 60, 64), stopped.toSet())
        pedal.release(3); ledger.release(3)
        pedal.release(4); ledger.release(4)
        assertEquals(setOf(59, 62), pedal.heldNotes)
        assertNull(pedal.nextReleaseAtMillis)
    }

    @Test fun commonNoteRetriggersAndItsOldDeadlineCannotStopTheNewNote() {
        val pedal = ChordPedal(true)
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val ledger = KeyboardPressLedger(started::add, stopped::add) { it in pedal.heldNotes }
        pedal.press(1, 60, 0); ledger.press(1, 60)
        pedal.press(2, 64, 0); ledger.press(2, 64)
        pedal.release(1); ledger.release(1)
        pedal.release(2); ledger.release(2)
        pedal.press(3, 60, 100); ledger.press(3, 60)
        pedal.release(3); ledger.release(3)
        assertEquals(listOf(60, 64, 60), started)
        assertEquals(setOf(64), pedal.releaseDue(250))
        assertEquals(setOf(60), pedal.heldNotes)
        assertTrue(stopped.isEmpty())
    }

    @Test fun rapidChordsDoNotKeepPostponingOlderReleaseDeadlines() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.release(1)
        pedal.press(2, 62, 100); pedal.release(2)
        pedal.press(3, 64, 200); pedal.release(3)
        assertEquals(250L, pedal.nextReleaseAtMillis)
        assertEquals(setOf(60), pedal.releaseDue(250))
        assertEquals(setOf(62, 64), pedal.heldNotes)
        assertEquals(350L, pedal.nextReleaseAtMillis)
        assertEquals(setOf(62), pedal.releaseDue(350))
        assertEquals(setOf(64), pedal.heldNotes)
    }

    @Test fun glissandoRetriggersAnOldNoteAndHoldsOnlyItsFinalNote() {
        val pedal = ChordPedal(true)
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val ledger = KeyboardPressLedger(started::add, stopped::add) { it in pedal.heldNotes }
        pedal.press(1, 60, 0); ledger.press(1, 60)
        pedal.release(1); ledger.release(1)
        pedal.press(2, 62, 100); ledger.press(2, 62)
        pedal.move(2, 60); ledger.move(2, 60)
        pedal.release(2); ledger.release(2)
        assertEquals(listOf(60, 62, 60), started)
        assertEquals(listOf(62), stopped)
        assertTrue(pedal.releaseDue(250).isEmpty())
        assertEquals(setOf(60), pedal.heldNotes)
        pedal.press(3, 64, 300); pedal.move(3, null); pedal.release(3)
        pedal.releaseDue(450)
        assertTrue(pedal.heldNotes.isEmpty())
    }

    @Test fun turningPedalOffAndCancellationClearBothHeldAndTransitioningNotes() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.release(1)
        pedal.press(2, 62, 100); pedal.release(2)
        pedal.changeEnabled(false)
        assertTrue(pedal.heldNotes.isEmpty())
        assertNull(pedal.nextReleaseAtMillis)
        assertTrue(pedal.releaseDue(1_000).isEmpty())
        pedal.press(3, 64, 200); pedal.release(3)
        assertTrue(pedal.heldNotes.isEmpty())
        pedal.changeEnabled(true)
        pedal.press(4, 65, 300); pedal.release(4)
        pedal.press(5, 67, 400)
        pedal.cancelGesture(); pedal.release(5)
        assertTrue(pedal.heldNotes.isEmpty())
        assertNull(pedal.nextReleaseAtMillis)
        assertTrue(pedal.releaseDue(1_000).isEmpty())
    }

    @Test fun manualLocksAreIndependentAndClearAllRemovesPendingRelease() {
        val pedal = ChordPedal(true)
        pedal.press(1, 60, 0); pedal.manualLatch(1); pedal.release(1)
        assertTrue(pedal.heldNotes.isEmpty())
        pedal.press(2, 64, 100); pedal.release(2)
        pedal.press(3, 67, 200); pedal.release(3)
        pedal.clearHeld()
        assertTrue(pedal.heldNotes.isEmpty())
        assertNull(pedal.nextReleaseAtMillis)
        assertTrue(pedal.releaseDue(350).isEmpty())
    }
}
