// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import org.junit.Assert.assertEquals
import org.junit.Test

class TuningEngineTest {
    private val tolerance = 1e-8

    @Test
    fun `A4 remains 440 hertz in equal temperament`() {
        val result = TuningEngine.tune(
            midiNote = 69,
            tuningSystem = TuningSystem.EQUAL_TEMPERAMENT,
        )

        assertEquals(440.0, result.frequencyHz, tolerance)
    }

    @Test
    fun `custom A4 remains the reference in equal temperament`() {
        val result = TuningEngine.tune(
            midiNote = 69,
            tuningSystem = TuningSystem.EQUAL_TEMPERAMENT,
            referenceFrequencyHz = 432.0,
        )

        assertEquals(432.0, result.frequencyHz, tolerance)
    }

    @Test
    fun `tonic keeps equal tempered pitch while tuning changes`() {
        repeat(12) { tonicPitchClass ->
            val tonicMidiNote = 60 + tonicPitchClass
            val equalTonic = TuningEngine.tune(
                midiNote = tonicMidiNote,
                tuningSystem = TuningSystem.EQUAL_TEMPERAMENT,
                referenceFrequencyHz = 432.0,
            )

            TuningSystem.entries.filter { it != TuningSystem.EQUAL_TEMPERAMENT && it != TuningSystem.SCALA }.forEach { tuning ->
                val tunedTonic = TuningEngine.tune(
                    midiNote = tonicMidiNote,
                    tuningSystem = tuning,
                    tonicPitchClass = tonicPitchClass,
                    referenceFrequencyHz = 432.0,
                )
                assertEquals(equalTonic.frequencyHz, tunedTonic.frequencyHz, tolerance)
            }
        }
    }

    @Test
    fun `every ratio tuning contains one ordered chromatic octave`() {
        TuningSystem.entries.filter { it.ratios != null }.forEach { tuning ->
            val ratios = requireNotNull(tuning.ratios)
            assertEquals(12, ratios.size)
            assertEquals(1.0, ratios.first(), tolerance)
            ratios.asList().zipWithNext().forEach { (lower, upper) ->
                check(upper > lower) { "${tuning.name} is not ordered" }
            }
            check(ratios.last() < 2.0) { "${tuning.name} exceeds its octave" }
        }
    }

    @Test
    fun `equal temperament doubles frequency after one octave`() {
        val a4 = TuningEngine.tune(69, TuningSystem.EQUAL_TEMPERAMENT)
        val a5 = TuningEngine.tune(81, TuningSystem.EQUAL_TEMPERAMENT)

        assertEquals(2.0, a5.frequencyHz / a4.frequencyHz, tolerance)
        assertEquals(0.0, a5.deviationCents, tolerance)
    }

    @Test
    fun `pythagorean fifth has ratio three to two`() {
        val c4 = TuningEngine.tune(60, TuningSystem.PYTHAGOREAN, tonicPitchClass = 0)
        val g4 = TuningEngine.tune(67, TuningSystem.PYTHAGOREAN, tonicPitchClass = 0)

        assertEquals(3.0 / 2.0, g4.frequencyHz / c4.frequencyHz, tolerance)
    }

    @Test
    fun `just major third has ratio five to four`() {
        val c4 = TuningEngine.tune(60, TuningSystem.JUST_INTONATION, tonicPitchClass = 0)
        val e4 = TuningEngine.tune(64, TuningSystem.JUST_INTONATION, tonicPitchClass = 0)

        assertEquals(5.0 / 4.0, e4.frequencyHz / c4.frequencyHz, tolerance)
    }

    @Test
    fun `note names follow scientific pitch notation`() {
        assertEquals("C4", TuningEngine.midiNoteName(60))
        assertEquals("A4", TuningEngine.midiNoteName(69))
        assertEquals("C5", TuningEngine.midiNoteName(72))
    }
}
