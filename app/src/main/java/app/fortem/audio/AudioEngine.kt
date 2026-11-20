// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.audio

/** Контракт между экранной клавиатурой и конкретной реализацией звука. */
interface AudioEngine {
    fun start(): Boolean
    fun stop()
    fun isRunning(): Boolean
    fun setTimbre(timbre: AudioTimbre)
    fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float = 0.8f)
    fun retune(midiNote: Int, frequencyHz: Double)
    fun noteOff(midiNote: Int)
}
