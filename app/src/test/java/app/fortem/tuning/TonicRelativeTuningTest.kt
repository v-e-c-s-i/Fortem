// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import app.fortem.recording.ConfigurationPerformanceEvent
import app.fortem.recording.PerformanceCodec
import app.fortem.recording.PerformanceConfiguration
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Регрессия для удерживаемой C4 и музыкальные ограничения во всех тониках. */
class TonicRelativeTuningTest {
    private val builtins = TuningSystem.entries.filter { it != TuningSystem.SCALA }
    private fun tune(note: Int, system: TuningSystem, tonic: Int, spelling: TonicSpelling = TonicSpelling.SHARP,
        reference: Double = 440.0) = TuningEngine.tune(note, system, tonic,
        referenceFrequencyHz = reference, tonicSpelling = spelling,
        tuningModel = TuningModel.TONIC_RELATIVE).frequencyHz

    @Test fun c4InDifferentJustTonicsUsesTheirIntervalsWithoutC4Compensation() {
        // Независимые примеры для диезного/белого выбора: E4 × 4/5 и D♯4 × 5/6.
        val expected = mapOf(0 to 261.625565300599, 2 to 264.298291125667,
            3 to 259.272486434234, 4 to 263.702045530296,
            5 to 261.921173574651, 7 to 261.330290654500, 9 to 264.0)
        expected.forEach { (tonic, hz) -> assertEquals(hz, tune(60, TuningSystem.JUST_INTONATION, tonic), 1e-7) }
        // E♭ использует отдельную опору, выше D♯ на выбранное отношение 128/125.
        assertEquals(265.495026108656, tune(60, TuningSystem.JUST_INTONATION, 3, TonicSpelling.FLAT), 1e-7)
        assertTrue(tune(60, TuningSystem.JUST_INTONATION, 4) != tune(60, TuningSystem.JUST_INTONATION, 3))
    }

    @Test fun majorScaleRetainsItsMusicalRatiosForEveryTonicAndSpelling() {
        val degrees = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
        val just = doubleArrayOf(1.0, 9.0/8, 5.0/4, 4.0/3, 3.0/2, 5.0/3, 15.0/8, 2.0)
        val pyth = doubleArrayOf(1.0, 9.0/8, 81.0/64, 4.0/3, 3.0/2, 27.0/16, 243.0/128, 2.0)
        for (pc in 0..11) for (spelling in TonicSpelling.entries) {
            listOf(TuningSystem.JUST_INTONATION to just, TuningSystem.PYTHAGOREAN to pyth).forEach { (system, expected) ->
                val root = tune(60 + pc, system, pc, spelling)
                degrees.forEachIndexed { index, degree ->
                    assertEquals(expected[index], tune(60 + pc + degree, system, pc, spelling) / root, 1e-12)
                }
            }
            val root = tune(60 + pc, TuningSystem.QUARTER_COMMA_MEANTONE, pc, spelling)
            assertEquals(5.0/4, tune(64 + pc, TuningSystem.QUARTER_COMMA_MEANTONE, pc, spelling) / root, 1e-12)
            assertEquals(5.0.pow(0.25), tune(67 + pc, TuningSystem.QUARTER_COMMA_MEANTONE, pc, spelling) / root, 1e-12)
        }
    }

    @Test fun whiteTonicsAndSharpTonicsPreserveEqualGridAnchor() {
        for (system in builtins) for (pc in 0..11) {
            val expected = 440.0 * 2.0.pow((60 + pc - 69) / 12.0)
            assertEquals(expected, tune(60 + pc, system, pc), 1e-10)
            if (!TonicAnchors.isBlackKey(pc)) assertEquals(expected, tune(60 + pc, system, pc, TonicSpelling.FLAT), 1e-10)
        }
    }

    @Test fun enharmonicDifferenceIsConstantAcrossTheRetunedKeyboard() {
        for (system in builtins) for (pc in listOf(1, 3, 6, 8, 10)) for (note in 0..127) {
            val expected = when (system) {
                TuningSystem.PYTHAGOREAN -> 524288.0/531441
                TuningSystem.QUARTER_COMMA_MEANTONE -> 128.0/125
                TuningSystem.JUST_INTONATION -> if (pc == 6) 2048.0/2025 else 128.0/125
                else -> 1.0
            }
            assertEquals(expected, tune(note, system, pc, TonicSpelling.FLAT) / tune(note, system, pc), 1e-12)
        }
    }

    @Test fun completeMidiRangeIsOrderedOctavePeriodicAndScalesWithReferencePitch() {
        for (system in builtins) for (pc in 0..11) for (spelling in TonicSpelling.entries) {
            for (note in 0..127) {
                val hz = tune(note, system, pc, spelling)
                assertTrue(hz.isFinite() && hz > 0.0)
                if (note < 127) assertTrue(tune(note + 1, system, pc, spelling) > hz)
                if (note < 116) assertEquals(2.0, tune(note + 12, system, pc, spelling) / hz, 1e-12)
                assertEquals(432.0/440, tune(note, system, pc, spelling, 432.0) / hz, 1e-12)
            }
        }
    }

    @Test fun historicalTemperamentFifthsMatchTheirDefinitionsAndCloseAtSevenOctaves() {
        val circle = listOf(0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5)
        val pythComma = 531441.0/524288
        val syntonicComma = 81.0/80
        for (system in listOf(TuningSystem.WERCKMEISTER_III, TuningSystem.KIRNBERGER_III)) {
            var product = 1.0
            circle.forEachIndexed { index, note ->
                val fifth = tune(60 + note + 7, system, 0) / tune(60 + note, system, 0)
                val expected = when (system) {
                    TuningSystem.WERCKMEISTER_III -> if (index in listOf(0, 1, 2, 5)) 1.5 / pythComma.pow(0.25) else 1.5
                    else -> when (index) {
                        0, 1, 2, 3 -> 1.5 / syntonicComma.pow(0.25)
                        8 -> 1.5 / (32805.0/32768)
                        else -> 1.5
                    }
                }
                assertEquals(expected, fifth, 1e-12)
                product *= fifth
            }
            assertEquals(128.0, product, 1e-10)
        }
        assertEquals(5.0/4, tune(64, TuningSystem.KIRNBERGER_III, 0) / tune(60, TuningSystem.KIRNBERGER_III, 0), 1e-12)
    }

    @Test fun versionOneAndTwoAreAudibleWithTheirOriginalRulesAfterReencoding() {
        fun frequency(c: PerformanceConfiguration, note: Int) = TuningEngine.tune(note, c.tuningSystem,
            c.tonicPitchClass, referenceFrequencyHz = c.referenceFrequencyHz, scalaScale = c.scalaScale,
            tonicSpelling = c.tonicSpelling, tuningModel = c.tuningModel).frequencyHz
        val base = "CREATED\t1\nDURATION\t100\nCOMPLETED\t1\n"
        val old1 = requireNotNull(PerformanceCodec.decode("FORTEM_PERFORMANCE\t1\n" + base +
            "INITIAL\tJUST_INTONATION\t4\t440.0\tPIANO\t-\n"))
        val old2 = requireNotNull(PerformanceCodec.decode("FORTEM_PERFORMANCE\t2\n" + base +
            "INITIAL\tJUST_INTONATION\t4\t440.0\tPIANO\t-\tSHARP\n" +
            "CONFIG\t50\tPYTHAGOREAN\t1\t440.0\tPIANO\t-\tFLAT\n"))
        assertEquals(263.702045530296, frequency(old1.initialConfiguration, 60), 1e-7)
        assertEquals(261.625565300599, frequency(old2.initialConfiguration, 60), 1e-7)
        listOf(old1, old2).forEach { old ->
            val restored = requireNotNull(PerformanceCodec.decode(PerformanceCodec.encode(old)))
            assertEquals(old, restored)
            val states = listOf(old.initialConfiguration) + old.events.filterIsInstance<ConfigurationPerformanceEvent>().map { it.configuration }
            states.forEach { c -> for (note in 0..127) assertTrue(frequency(c, note).isFinite()) }
        }
    }
}
