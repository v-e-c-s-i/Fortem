// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.audio

/** Thread-confined offline instance: shares immutable samples, never the live voices. */
class NativeOfflineRenderer : AudioEngine, AutoCloseable {
    private var handle = nativeCreate().also { check(it != 0L) { "Звуковой движок не готов" } }
    override fun start() = true
    override fun stop() = Unit
    override fun isRunning() = handle != 0L
    override fun setTimbre(timbre: AudioTimbre) = nativeTimbre(handle, timbre.ordinal)
    override fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float) =
        nativeNote(handle, 1, midiNote, frequencyHz, velocity)
    override fun noteOff(midiNote: Int) = nativeNote(handle, 0, midiNote, 0.0, 0f)
    override fun retune(midiNote: Int, frequencyHz: Double) = nativeNote(handle, 2, midiNote, frequencyHz, 0f)
    fun render(buffer: FloatArray, frames: Int) {
        check(handle != 0L)
        require(frames >= 0 && frames <= buffer.size / 2)
        nativeRender(handle, buffer, frames)
    }
    override fun close() {
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeTimbre(handle: Long, timbre: Int)
    private external fun nativeNote(handle: Long, kind: Int, note: Int, frequency: Double, velocity: Float)
    private external fun nativeRender(handle: Long, buffer: FloatArray, frames: Int)
    private companion object {
        init { System.loadLibrary("fortem-audio") }
    }
}
