// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import app.fortem.audio.AudioEngine
import app.fortem.audio.AudioTimbre
import org.junit.Assert.*
import org.junit.Test

class MidiMirroringAudioEngineTest {
    private class Local : AudioEngine {
        val attacks = mutableListOf<Triple<Int, Double, Float>>()
        val tuning = mutableListOf<Pair<Int, Double>>()
        val released = mutableListOf<Int>()
        override fun start() = true
        override fun stop() {}
        override fun isRunning() = true
        override fun setTimbre(timbre: AudioTimbre) {}
        override fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float) {
            attacks += Triple(midiNote, frequencyHz, velocity)
        }
        override fun retune(midiNote: Int, frequencyHz: Double) { tuning += midiNote to frequencyHz }
        override fun noteOff(midiNote: Int) { released += midiNote }
    }
    private class Output : MidiNoteOutput {
        override var connected = true
        val attacks = mutableListOf<Pair<Int, Float>>()
        val released = mutableListOf<Int>()
        override fun noteOn(note: Int, velocity: Float) { if (connected) attacks += note to velocity }
        override fun noteOff(note: Int) { if (connected) released += note }
    }

    @Test fun oldMicrotonalPlaybackSendsUnchangedKeysAndUsesEqualLocalPitch() {
        val local = Local(); val midi = Output()
        val engine = MidiMirroringAudioEngine(local, midi)
        engine.noteOn(69, 432.0, .4f)
        engine.retune(69, 442.0)
        assertEquals(listOf(Triple(69, 440.0, .4f)), local.attacks)
        assertEquals(listOf(69 to 440.0), local.tuning)
        assertEquals(listOf(69 to .4f), midi.attacks)
        engine.noteOff(69)
        assertEquals(listOf(69), local.released)
        assertEquals(listOf(69), midi.released)
    }

    @Test fun disconnectRestoresCustomFrequencyWithoutANewAttack() {
        val local = Local(); val midi = Output()
        val engine = MidiMirroringAudioEngine(local, midi)
        engine.noteOn(69, 432.0, .8f)
        midi.connected = false
        engine.retune(69, 432.0)
        assertEquals(listOf(69 to 432.0), local.tuning)
        assertEquals(1, local.attacks.size)
        engine.noteOn(60, 250.0, .8f)
        assertEquals(250.0, local.attacks.last().second, 0.0)
        assertEquals(1, midi.attacks.size)
    }
}
