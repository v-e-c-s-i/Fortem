// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import kotlin.math.ln
import kotlin.math.pow

/** Результат расчёта одной MIDI-ноты в выбранном музыкальном строе. */
data class TunedNote(
    val midiNote: Int,
    val frequencyHz: Double,
    val deviationCents: Double,
)

/**
 * Чистое математическое ядро настройки.
 *
 * Оно не зависит от Android, звукового движка и интерфейса. Поэтому формулы
 * можно проверять обычными модульными тестами без запуска телефона.
 */
object TuningEngine {
    const val DEFAULT_REFERENCE_NOTE = 69 // A4 в стандарте MIDI.
    const val DEFAULT_REFERENCE_FREQUENCY_HZ = 440.0

    private const val NOTES_PER_OCTAVE = 12
    private const val TONIC_BASE_MIDI_NOTE = 60 // C4 служит базовой октавой тоники.

    private val pitchClassNames = listOf(
        "C", "C♯", "D", "D♯", "E", "F",
        "F♯", "G", "G♯", "A", "A♯", "B",
    )

    /**
     * Рассчитывает частоту MIDI-ноты и её отклонение от 12-TET в центах.
     * В 12-TET опорная A4 имеет выбранную пользователем частоту. В остальных
     * строях эта частота задаёт общую высоту, а точной опорой 1/1 становится
     * тоника. Новая модель переносит шаблон на равномерную высоту тоники,
     * учитывая энгармонический сдвиг бемольного варианта. Архивные модели
     * выбираются явно: сохранённая запись не переходит на новый расчёт.
     */
    fun tune(
        midiNote: Int,
        tuningSystem: TuningSystem,
        tonicPitchClass: Int = 0,
        referenceNote: Int = DEFAULT_REFERENCE_NOTE,
        referenceFrequencyHz: Double = DEFAULT_REFERENCE_FREQUENCY_HZ,
        scalaScale: ScalaScale? = null,
        tonicSpelling: TonicSpelling? = null,
        tuningModel: TuningModel = TuningModel.LEGACY_EQUAL_TONIC,
    ): TunedNote {
        require(midiNote in 0..127) { "MIDI note must be between 0 and 127" }
        require(referenceNote in 0..127) { "Reference MIDI note must be between 0 and 127" }
        require(tonicPitchClass in 0 until NOTES_PER_OCTAVE) { "Tonic pitch class must be between 0 and 11" }
        require(referenceFrequencyHz.isFinite() && referenceFrequencyHz > 0.0) { "Reference frequency must be positive" }

        val equalFrequency = equalTemperamentFrequency(
            midiNote = midiNote,
            referenceNote = referenceNote,
            referenceFrequencyHz = referenceFrequencyHz,
        )

        val frequency = when (tuningSystem) {
            TuningSystem.EQUAL_TEMPERAMENT -> equalFrequency
            else -> ratioBasedFrequency(
                midiNote = midiNote,
                referenceNote = referenceNote,
                referenceFrequencyHz = referenceFrequencyHz,
                tonicPitchClass = tonicPitchClass,
                tonicSpelling = tonicSpelling,
                tuningSystem = tuningSystem,
                tuningModel = tuningModel,
                ratios = if (tuningSystem == TuningSystem.SCALA) {
                    requireNotNull(scalaScale) { "Scala scale is missing" }.ratios
                } else if (tuningModel == TuningModel.TONIC_RELATIVE) {
                    TuningIntervals.ratios(tuningSystem)
                } else requireNotNull(tuningSystem.ratios),
            )
        }

        return TunedNote(
            midiNote = midiNote,
            frequencyHz = frequency,
            deviationCents = 1200.0 * log2(frequency / equalFrequency),
        )
    }

    fun pitchClassName(pitchClass: Int): String {
        require(pitchClass in 0 until NOTES_PER_OCTAVE)
        return pitchClassNames[pitchClass]
    }

    fun midiNoteName(midiNote: Int): String {
        require(midiNote in 0..127)
        val pitchClass = Math.floorMod(midiNote, NOTES_PER_OCTAVE)
        val octave = Math.floorDiv(midiNote, NOTES_PER_OCTAVE) - 1
        return "${pitchClassNames[pitchClass]}$octave"
    }

    private fun equalTemperamentFrequency(
        midiNote: Int,
        referenceNote: Int,
        referenceFrequencyHz: Double,
    ): Double {
        val semitoneDistance = (midiNote - referenceNote) / 12.0
        return referenceFrequencyHz * 2.0.pow(semitoneDistance)
    }

    private fun ratioBasedFrequency(
        midiNote: Int,
        referenceNote: Int,
        referenceFrequencyHz: Double,
        tonicPitchClass: Int,
        tonicSpelling: TonicSpelling?,
        tuningSystem: TuningSystem,
        tuningModel: TuningModel,
        ratios: DoubleArray,
    ): Double {
        check(ratios.size == NOTES_PER_OCTAVE)

        // Тоника помещается в октаву C4–B4; floorDiv корректно работает и ниже неё.
        val tonicMidiNote = TONIC_BASE_MIDI_NOTE + tonicPitchClass
        // Общая C4 применяется только к исполнениям формата 2. В живой модели
        // равномерная опора каждой тоники не компенсирует её интервальный шаблон.
        val anchorRatio = if (tuningModel == TuningModel.LEGACY_C4_TONIC) {
            tonicSpelling?.let { TonicAnchors.ratio(tuningSystem, tonicPitchClass, it) }
        } else null
        val enharmonicMultiplier = if (tuningModel == TuningModel.TONIC_RELATIVE) {
            TonicAnchors.relativeMultiplier(tuningSystem, tonicPitchClass, tonicSpelling)
        } else 1.0
        val tonicFrequency = equalTemperamentFrequency(
            midiNote = if (anchorRatio == null) tonicMidiNote else TONIC_BASE_MIDI_NOTE,
            referenceNote = referenceNote,
            referenceFrequencyHz = referenceFrequencyHz,
        ) * (anchorRatio ?: 1.0) * enharmonicMultiplier
        val noteMultiplier = multiplierFromTonic(midiNote, tonicMidiNote, ratios)
        return tonicFrequency * noteMultiplier
    }

    private fun multiplierFromTonic(
        midiNote: Int,
        tonicMidiNote: Int,
        ratios: DoubleArray,
    ): Double {
        val distance = midiNote - tonicMidiNote
        val pitchClassDistance = Math.floorMod(distance, NOTES_PER_OCTAVE)
        val octaveDistance = Math.floorDiv(distance, NOTES_PER_OCTAVE)
        return ratios[pitchClassDistance] * 2.0.pow(octaveDistance.toDouble())
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)
}
