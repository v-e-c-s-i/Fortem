// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

/**
 * Единый источник состояния для всех пальцев на клавиатуре.
 *
 * Реестр считает, сколько пальцев удерживают каждую ноту, поэтому звук и
 * подсветка получают один и тот же итог даже при одновременном появлении
 * нескольких указателей в одном событии Android.
 */
internal class KeyboardPressLedger(
    private val onNoteOn: (Int) -> Unit,
    private val onNoteOff: (Int) -> Unit,
    private val isLatched: (Int) -> Boolean,
) {
    private val pointerNotes = mutableMapOf<Long, Int>()
    private val notePressCounts = mutableMapOf<Int, Int>()

    fun press(pointerId: Long, midiNote: Int): Boolean {
        if (pointerNotes[pointerId] == midiNote) return false
        if (pointerId in pointerNotes) move(pointerId, null)

        pointerNotes[pointerId] = midiNote
        val previousCount = notePressCounts[midiNote] ?: 0
        notePressCounts[midiNote] = previousCount + 1
        if (previousCount == 0 && !isLatched(midiNote)) onNoteOn(midiNote)
        return true
    }

    fun move(pointerId: Long, midiNote: Int?): Boolean {
        val previousNote = pointerNotes[pointerId]
        if (previousNote == midiNote) return false

        if (previousNote != null) decrement(previousNote)
        if (midiNote == null) {
            pointerNotes.remove(pointerId)
        } else {
            pointerNotes[pointerId] = midiNote
            val previousCount = notePressCounts[midiNote] ?: 0
            notePressCounts[midiNote] = previousCount + 1
            if (previousCount == 0 && !isLatched(midiNote)) onNoteOn(midiNote)
        }
        return true
    }

    fun release(pointerId: Long): Boolean = move(pointerId, null)

    fun releaseAll(): Boolean {
        if (pointerNotes.isEmpty()) return false
        pointerNotes.keys.toList().forEach(::release)
        return true
    }

    fun pressedNotes(): Set<Int> = notePressCounts.keys.toSet()

    private fun decrement(midiNote: Int) {
        val remaining = (notePressCounts[midiNote] ?: 1) - 1
        if (remaining > 0) {
            notePressCounts[midiNote] = remaining
            return
        }

        notePressCounts.remove(midiNote)
        if (!isLatched(midiNote)) onNoteOff(midiNote)
    }
}

/** Note Off допустим только после освобождения последнего владельца ноты. */
internal fun notesWithoutRemainingHold(
    released: Set<Int>, pressed: Set<Int>, retained: Set<Int>,
): Set<Int> = released - pressed - retained
