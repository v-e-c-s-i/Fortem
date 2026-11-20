// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import kotlin.math.roundToInt

/** Обычный MIDI 1.0, канал 1. Тембр и строй определяет принимающий инструмент. */
object MidiNoteMessages {
    fun noteOn(note: Int, velocity: Float): ByteArray {
        require(note in 0..127 && velocity.isFinite())
        return byteArrayOf(0x90.toByte(), note.toByte(), (velocity.coerceIn(0f, 1f) * 127f)
            .roundToInt().coerceIn(1, 127).toByte())
    }

    fun noteOff(note: Int): ByteArray {
        require(note in 0..127)
        return byteArrayOf(0x80.toByte(), note.toByte(), 0)
    }
}

/** Повторный удар закрывает предыдущую MIDI-ноту, не создавая зависшие дубликаты. */
class MidiNoteLedger {
    private val active = linkedSetOf<Int>()

    fun noteOn(note: Int, velocity: Float): List<ByteArray> {
        val message = MidiNoteMessages.noteOn(note, velocity)
        return buildList {
            if (note in active) add(MidiNoteMessages.noteOff(note))
            active += note
            add(message)
        }
    }

    fun noteOff(note: Int): List<ByteArray> =
        if (active.remove(note)) listOf(MidiNoteMessages.noteOff(note)) else emptyList()

    // Только наши ноты: общий All Notes Off мог бы погасить чужую дорожку на канале 1.
    fun releaseAll(): List<ByteArray> = active.map(MidiNoteMessages::noteOff).also { active.clear() }
}
