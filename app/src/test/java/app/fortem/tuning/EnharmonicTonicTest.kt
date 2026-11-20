// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Проверяем музыкальные интервалы, наследуемые записи и всю MIDI-область. */
class EnharmonicTonicTest {
    private val blackKeys = listOf(1, 3, 6, 8, 10)
    private fun frequency(system: TuningSystem, tonic: Int, spelling: TonicSpelling?, note: Int = 60 + tonic) =
        TuningEngine.tune(note, system, tonic, tonicSpelling = spelling,
            tuningModel = TuningModel.LEGACY_C4_TONIC).frequencyHz

    @Test fun pythagoreanSharpExceedsFlatByTwelveFifthsMinusSevenOctaves() {
        val comma = 3.0.pow(12) / 2.0.pow(19)
        blackKeys.forEach { tonic ->
            assertEquals(comma, frequency(TuningSystem.PYTHAGOREAN, tonic, TonicSpelling.SHARP) /
                frequency(TuningSystem.PYTHAGOREAN, tonic, TonicSpelling.FLAT), 1e-12)
        }
        assertEquals(23.460010384649, 1200.0 * ln(comma) / ln(2.0), 1e-9)
    }

    @Test fun quarterCommaMeantoneHasPureMajorThirdAndFlatAboveSharp() {
        val system = TuningSystem.QUARTER_COMMA_MEANTONE
        val c = frequency(system, 0, TonicSpelling.SHARP)
        assertEquals(5.0 / 4.0, frequency(system, 4, TonicSpelling.SHARP) / c, 1e-12)
        blackKeys.forEach { tonic ->
            assertEquals(128.0 / 125.0, frequency(system, tonic, TonicSpelling.FLAT) /
                frequency(system, tonic, TonicSpelling.SHARP), 1e-12)
        }
    }

    @Test fun chosenJustLatticePreservesPureThirdAndItsTwoEnharmonicCommas() {
        val system = TuningSystem.JUST_INTONATION
        val c = frequency(system, 0, TonicSpelling.SHARP)
        assertEquals(5.0 / 4.0, frequency(system, 4, TonicSpelling.SHARP) / c, 1e-12)
        blackKeys.forEach { tonic ->
            val expected = if (tonic == 6) 2048.0 / 2025.0 else 128.0 / 125.0
            assertEquals(expected, frequency(system, tonic, TonicSpelling.FLAT) /
                frequency(system, tonic, TonicSpelling.SHARP), 1e-12)
        }
    }

    @Test fun whiteTonicSpellingsAgreeAndEveryKeyboardNoteRepeatsAtTheOctave() {
        TuningSystem.entries.filter(TonicAnchors::distinguishesSpellings).forEach { system ->
            (0..11).filterNot { it in blackKeys }.forEach { tonic ->
                assertEquals(frequency(system, tonic, TonicSpelling.SHARP),
                    frequency(system, tonic, TonicSpelling.FLAT), 1e-10)
            }
            for (tonic in 0..11) for (spelling in TonicSpelling.entries) for (note in 0..115) {
                assertEquals(2.0, frequency(system, tonic, spelling, note + 12) /
                    frequency(system, tonic, spelling, note), 1e-12)
            }
        }
    }

    @Test fun legacyTonicRemainsEqualGridAnchoredAndClosedTemperamentsIgnoreSpelling() {
        TuningSystem.entries.filter { it != TuningSystem.SCALA }.forEach { system ->
            for (tonic in 0..11) {
                assertEquals(440.0 * 2.0.pow((60 + tonic - 69) / 12.0),
                    frequency(system, tonic, null), 1e-10)
                if (!TonicAnchors.distinguishesSpellings(system)) {
                    for (note in 0..127) assertEquals(frequency(system, tonic, TonicSpelling.SHARP, note),
                        frequency(system, tonic, TonicSpelling.FLAT, note), 0.0)
                }
            }
        }
    }

    @Test fun importedScalaDoesNotInventEnharmonicVariants() {
        val scala = ScalaScale("12 ступеней", (0..11).map { it * 100.0 })
        for (tonic in blackKeys) for (note in 0..127) {
            val sharp = TuningEngine.tune(note, TuningSystem.SCALA, tonic,
                scalaScale = scala, tonicSpelling = TonicSpelling.SHARP)
            val flat = TuningEngine.tune(note, TuningSystem.SCALA, tonic,
                scalaScale = scala, tonicSpelling = TonicSpelling.FLAT)
            assertEquals(sharp, flat)
        }
    }
}
