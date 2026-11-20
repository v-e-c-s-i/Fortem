// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** Написание выбранной тоники; оно может задавать отдельную высоту чёрной клавиши. */
enum class TonicSpelling { SHARP, FLAT }

/**
 * Именованные тоники относительно C4. Для цепочечных строев имена задают число
 * квинт от C; для натурального выбран и документирован один 5-предельный набор.
 * В замкнутых 12-ступенных темперациях и произвольной Scala отдельных пар нет.
 */
object TonicAnchors {
    private val sharpSteps = intArrayOf(0, 7, 2, 9, 4, -1, 6, 1, 8, 3, 10, 5)
    private val flatSteps = intArrayOf(0, -5, 2, -3, 4, -1, -6, 1, -4, 3, -2, 5)
    private val sharpNames = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private val flatNames = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
    private val justSharp = doubleArrayOf(1.0, 25.0/24.0, 9.0/8.0, 75.0/64.0, 5.0/4.0,
        4.0/3.0, 45.0/32.0, 3.0/2.0, 25.0/16.0, 5.0/3.0, 225.0/128.0, 15.0/8.0)
    private val justFlat = doubleArrayOf(1.0, 16.0/15.0, 9.0/8.0, 6.0/5.0, 5.0/4.0,
        4.0/3.0, 64.0/45.0, 3.0/2.0, 8.0/5.0, 5.0/3.0, 9.0/5.0, 15.0/8.0)

    fun distinguishesSpellings(system: TuningSystem): Boolean = when (system) {
        TuningSystem.PYTHAGOREAN, TuningSystem.JUST_INTONATION,
        TuningSystem.QUARTER_COMMA_MEANTONE -> true
        else -> false
    }

    fun isBlackKey(pitchClass: Int): Boolean = when (pitchClass) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    fun name(pitchClass: Int, spelling: TonicSpelling): String {
        require(pitchClass in 0..11)
        return if (spelling == TonicSpelling.FLAT) flatNames[pitchClass] else sharpNames[pitchClass]
    }

    fun ratio(system: TuningSystem, pitchClass: Int, spelling: TonicSpelling): Double? {
        require(pitchClass in 0..11)
        if (!distinguishesSpellings(system)) return null
        if (system == TuningSystem.JUST_INTONATION) {
            return if (spelling == TonicSpelling.FLAT) justFlat[pitchClass] else justSharp[pitchClass]
        }
        val steps = if (spelling == TonicSpelling.FLAT) flatSteps[pitchClass] else sharpSteps[pitchClass]
        val fifth = if (system == TuningSystem.PYTHAGOREAN) 1.5 else 5.0.pow(0.25)
        val raw = fifth.pow(steps)
        // Приведение высоты в октаву C4–B4 не замыкает квинтовую цепочку.
        return raw / 2.0.pow(floor(ln(raw) / ln(2.0)))
    }

    /**
     * Новая опора: диезный вариант имеет обычную высоту клавиши; бемольный
     * отличается только отношением двух написаний. Белые тоники не смещаются.
     * Это выбор общей высоты транспонированного шаблона, а не второй половины клавиши.
     */
    fun relativeMultiplier(system: TuningSystem, pitchClass: Int, spelling: TonicSpelling?): Double {
        require(pitchClass in 0..11)
        if (spelling != TonicSpelling.FLAT || !isBlackKey(pitchClass)) return 1.0
        return when (system) {
            TuningSystem.PYTHAGOREAN -> 524288.0 / 531441.0
            TuningSystem.QUARTER_COMMA_MEANTONE -> 128.0 / 125.0
            TuningSystem.JUST_INTONATION -> if (pitchClass == 6) 2048.0 / 2025.0 else 128.0 / 125.0
            else -> 1.0
        }
    }
}
