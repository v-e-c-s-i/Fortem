// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiNoteLedgerTest {
    @Test fun simultaneousChordAndRepeatedCommonNoteLeaveNoStuckNotes() {
        val ledger = MidiNoteLedger()
        listOf(60, 64, 67).forEach { ledger.noteOn(it, .8f) }
        val repeat = ledger.noteOn(60, .8f)
        assertEquals(2, repeat.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 60, 0), repeat[0])
        assertArrayEquals(byteArrayOf(0x90.toByte(), 60, 102), repeat[1])
        assertEquals(1, ledger.noteOff(64).size)
        assertTrue(ledger.noteOff(64).isEmpty())
        val released = ledger.releaseAll()
        assertEquals(setOf(60, 67), released.map { it[1].toInt() }.toSet())
        assertTrue(ledger.releaseAll().isEmpty())
    }

    @Test fun byteMessagesStayInsideMidiRangeAndNeverProduceZeroVelocityNoteOn() {
        for (note in 0..127) {
            assertArrayEquals(byteArrayOf(0x90.toByte(), note.toByte(), 1),
                MidiNoteMessages.noteOn(note, 0f))
            assertArrayEquals(byteArrayOf(0x90.toByte(), note.toByte(), 127),
                MidiNoteMessages.noteOn(note, 2f))
            assertArrayEquals(byteArrayOf(0x80.toByte(), note.toByte(), 0),
                MidiNoteMessages.noteOff(note))
        }
    }
}
