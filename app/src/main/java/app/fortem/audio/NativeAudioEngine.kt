// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.audio

import android.content.res.AssetManager

/**
 * Тонкая JNI-обёртка над движком Oboe на C++.
 *
 * Движок воспроизводит исходные 24-битные WAV рояля без промежуточного
 * перекодирования. Чистый синус остаётся отдельным доступным тембром.
 */
class NativeAudioEngine : AudioEngine {
    /** Открывает встроенные WAV как ресурсы APK; аудиоданные при этом не копируются. */
    fun loadPianoSamples(assetManager: AssetManager): Boolean =
        nativeLoadPianoSamples(assetManager)

    override fun start(): Boolean = nativeStart()

    override fun stop() = nativeStop()

    override fun isRunning(): Boolean = nativeIsRunning()

    override fun setTimbre(timbre: AudioTimbre) {
        nativeSetTimbre(timbre.ordinal)
    }

    /** Меняет только живой выход телефона; голоса и WAV-экспорт продолжают работать. */
    fun setOutputEnabled(enabled: Boolean) = nativeSetOutputEnabled(enabled)

    override fun noteOn(midiNote: Int, frequencyHz: Double, velocity: Float) {
        nativeNoteOn(midiNote, frequencyHz, velocity.coerceIn(0f, 1f))
    }

    override fun retune(midiNote: Int, frequencyHz: Double) {
        nativeRetune(midiNote, frequencyHz)
    }

    override fun noteOff(midiNote: Int) = nativeNoteOff(midiNote)

    private external fun nativeLoadPianoSamples(assetManager: AssetManager): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativeSetTimbre(timbre: Int)
    private external fun nativeSetOutputEnabled(enabled: Boolean)
    private external fun nativeNoteOn(midiNote: Int, frequencyHz: Double, velocity: Float)
    private external fun nativeRetune(midiNote: Int, frequencyHz: Double)
    private external fun nativeNoteOff(midiNote: Int)

    private companion object {
        init {
            // Имя совпадает с add_library в CMakeLists.txt.
            System.loadLibrary("fortem-audio")
        }
    }
}
