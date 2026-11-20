// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import app.fortem.audio.AudioEngine
import app.fortem.tuning.TuningEngine
import app.fortem.tuning.TuningSystem

/** Нотный выход отделён от Android-портов, чтобы проверить маршрутизацию без телефона. */
interface MidiNoteOutput {
    val connected: Boolean
    fun noteOn(note: Int, velocity: Float)
    fun noteOff(note: Int)
}

/** MIDI получает только номера нот и velocity, без тембра, тоники и перестройки. */
class MidiMirroringAudioEngine(private val local: AudioEngine, private val midi: MidiNoteOutput) : AudioEngine by local {
    private fun localFrequency(note: Int, requested: Double): Double =
        if (midi.connected) TuningEngine.tune(note, TuningSystem.EQUAL_TEMPERAMENT).frequencyHz
        else requested

    override fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float) {
        // Даже старая микротональная запись звучит локально в 12-TET при MIDI.
        local.noteOn(midiNote, localFrequency(midiNote, frequencyHz), velocity)
        midi.noteOn(midiNote, velocity)
    }
    override fun retune(midiNote: Int, frequencyHz: Double) {
        local.retune(midiNote, localFrequency(midiNote, frequencyHz))
    }
    override fun noteOff(midiNote: Int) {
        local.noteOff(midiNote)
        midi.noteOff(midiNote)
    }
}
