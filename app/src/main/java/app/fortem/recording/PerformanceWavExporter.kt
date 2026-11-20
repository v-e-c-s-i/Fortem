// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.NativeOfflineRenderer
import app.fortem.tuning.TuningEngine
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Offline rendering at 48 kHz, stereo PCM16. Events land on sample boundaries;
 * a 1.1 s tail includes the piano's release and the limiter's 1 ms lookahead.
 * Caller writes to a private temporary file first; failures never alter the take.
 */
object PerformanceWavExporter {
    const val SAMPLE_RATE = 48_000
    private const val BLOCK_FRAMES = 1024
    fun export(
        recording: PerformanceRecording,
        file: File,
        retriggerHeldNotes: Boolean,
        checkCancelled: () -> Unit = {},
        progress: (Float) -> Unit = {},
    ) {
        val take = recording.recovered()
        val tailFrames = 52_800L
        val contentFrames = nanosToFrames(take.durationNanos)
        val totalFrames = contentFrames + tailFrames
        require(totalFrames <= (0xffffffffL - 36L) / 4L) { "Запись слишком длинная для WAV" }
        try {
            NativeOfflineRenderer().use { engine ->
                file.outputStream().buffered().use { out ->
                    writeHeader(out, totalFrames)
                    val samples = FloatArray(BLOCK_FRAMES * 2)
                    val pcm = ByteArray(BLOCK_FRAMES * 4)
                    var currentFrame = 0L
                    var configuration = take.initialConfiguration
                    val notes = linkedMapOf<Int, Float>()
                    engine.setTimbre(configuration.timbre)
                    fun frequency(note: Int) = TuningEngine.tune(
                        midiNote = note, tuningSystem = configuration.tuningSystem,
                        tonicPitchClass = configuration.tonicPitchClass,
                        referenceFrequencyHz = configuration.referenceFrequencyHz,
                        scalaScale = configuration.scalaScale,
                        tonicSpelling = configuration.tonicSpelling,
                        tuningModel = configuration.tuningModel,
                    ).frequencyHz
                    fun renderUntil(target: Long) {
                        while (currentFrame < target) {
                            checkCancelled()
                            val count = minOf(BLOCK_FRAMES.toLong(), target - currentFrame).toInt()
                            engine.render(samples, count)
                            repeat(count * 2) { index ->
                                val value = (samples[index].coerceIn(-1f, 1f) * 32767f).roundToInt()
                                pcm[index * 2] = value.toByte()
                                pcm[index * 2 + 1] = (value shr 8).toByte()
                            }
                            out.write(pcm, 0, count * 4)
                            currentFrame += count
                            progress(currentFrame.toFloat() / totalFrames)
                        }
                    }
                    take.events.sortedBy { it.timeNanos }.forEach { event ->
                        renderUntil(nanosToFrames(event.timeNanos).coerceIn(currentFrame, contentFrames))
                        when (event) {
                            is NotePerformanceEvent -> if (event.isNoteOn) {
                                engine.noteOn(event.midiNote, frequency(event.midiNote), event.velocity)
                                notes[event.midiNote] = event.velocity
                            } else {
                                engine.noteOff(event.midiNote)
                                notes.remove(event.midiNote)
                            }
                            is ConfigurationPerformanceEvent -> {
                                val next = event.configuration
                                val retrigger = retriggerHeldNotes && next.timbre == configuration.timbre &&
                                    (next.tuningSystem != configuration.tuningSystem ||
                                        next.scalaScale != configuration.scalaScale ||
                                        next.tonicPitchClass != configuration.tonicPitchClass ||
                                        next.tonicSpelling != configuration.tonicSpelling ||
                                        next.tuningModel != configuration.tuningModel)
                                val changeTimbre = next.timbre != configuration.timbre
                                configuration = next
                                if (changeTimbre) engine.setTimbre(configuration.timbre)
                                notes.forEach { (note, velocity) ->
                                    if (retrigger) engine.noteOn(note, frequency(note), velocity)
                                    else engine.retune(note, frequency(note))
                                }
                            }
                            is LatchPerformanceEvent -> Unit // Sound duration is in NOTE events.
                            is PedalPerformanceEvent -> Unit // Не применять педаль дважды: NOTE уже содержит удержание.
                        }
                    }
                    renderUntil(contentFrames)
                    notes.keys.forEach(engine::noteOff)
                    renderUntil(totalFrames)
                }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    internal fun nanosToFrames(nanos: Long): Long {
        require(nanos >= 0L)
        // Split to avoid overflow on long takes.
        return (nanos / 1_000_000_000L) * SAMPLE_RATE +
            ((nanos % 1_000_000_000L) * SAMPLE_RATE / 1_000_000_000L)
    }
    internal fun writeHeader(out: OutputStream, frames: Long) {
        val bytes = frames * 4L
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((bytes + 36L).toInt())
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1.toShort()).putShort(2.toShort()).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 4)
        header.putShort(4.toShort()).putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes.toInt())
        out.write(header.array())
    }
}
