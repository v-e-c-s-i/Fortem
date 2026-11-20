// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * Точные 12-ступенные шаблоны для новой модели. Старые округлённые таблицы
 * TuningSystem сохранены для воспроизведения архивных исполнений.
 * Имена ступеней здесь относительные: весь шаблон переносится на выбранную тонику.
 */
internal object TuningIntervals {
    private val meantone = fifthChain(intArrayOf(0, 7, 2, -3, 4, -1, 6, 1, 8, 3, -2, 5), 5.0.pow(0.25))
    private val pythagoreanComma = 531441.0 / 524288.0
    private val syntonicComma = 81.0 / 80.0
    private val schisma = 32805.0 / 32768.0

    // Веркмейстер III: четыре квинты сужены на 1/4 пифагорейской коммы.
    private val werckmeister = closedFifths(DoubleArray(12) { index ->
        if (index == 0 || index == 1 || index == 2 || index == 5) pythagoreanComma.pow(0.25) else 1.0
    })
    // Вариант Кирнбергера III с шизмой на G♯–E♭, соответствующий архиву Scala
    // и прежней таблице Fortem. Четыре первые квинты сужены на 1/4 синтонической коммы.
    private val kirnberger = closedFifths(DoubleArray(12) { index ->
        when (index) {
            0, 1, 2, 3 -> syntonicComma.pow(0.25)
            8 -> schisma
            else -> 1.0
        }
    })

    fun ratios(system: TuningSystem): DoubleArray = when (system) {
        TuningSystem.QUARTER_COMMA_MEANTONE -> meantone
        TuningSystem.WERCKMEISTER_III -> werckmeister
        TuningSystem.KIRNBERGER_III -> kirnberger
        else -> requireNotNull(system.ratios) // Пифагорейский и натуральный уже заданы точными дробями.
    }

    private fun normalize(raw: Double): Double = raw / 2.0.pow(floor(ln(raw) / ln(2.0)))

    private fun fifthChain(steps: IntArray, fifth: Double): DoubleArray =
        DoubleArray(12) { normalize(fifth.pow(steps[it])) }

    /** Цепочка C–G–D–A–E–B–F♯–C♯–G♯–E♭–B♭–F обязана замкнуться через семь октав. */
    private fun closedFifths(narrowing: DoubleArray): DoubleArray {
        val pitchClasses = intArrayOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)
        val result = DoubleArray(12)
        var raw = 1.0
        pitchClasses.forEachIndexed { index, pitchClass ->
            result[pitchClass] = normalize(raw)
            raw *= 1.5 / narrowing[index]
        }
        check(abs(raw / 128.0 - 1.0) < 1e-12) { "Circle of fifths must close at seven octaves" }
        return result
    }
}
