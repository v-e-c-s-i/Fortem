// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalaScaleTest {
    private fun equalText(description: String = "12 equal"): String =
        "! example\r\n$description\r\n12\r\n" + (1..12).joinToString("\r\n") { "${it * 100}.0" }

    @Test fun equalScalePreservesEveryPianoFrequencyAndTonic() {
        val scale = ScalaScaleCodec.parse(equalText())
        repeat(12) { tonic ->
            (21..108).forEach { note ->
                assertEquals(TuningEngine.tune(note, TuningSystem.EQUAL_TEMPERAMENT,
                    referenceFrequencyHz = 432.0).frequencyHz,
                    TuningEngine.tune(note, TuningSystem.SCALA, tonic,
                        referenceFrequencyHz = 432.0, scalaScale = scale).frequencyHz, 1e-8)
            }
        }
    }

    @Test fun commentsBlankDescriptionAndIntegerRatioAreParsed() {
        val text = equalText("").replace("400.0", "! comment\r\n5/4 E")
            .replace("1200.0", "2 octave")
        val scale = ScalaScaleCodec.parse(text, "sample")
        assertEquals("sample", scale.title)
        val c = TuningEngine.tune(60, TuningSystem.SCALA, scalaScale = scale).frequencyHz
        val e = TuningEngine.tune(64, TuningSystem.SCALA, scalaScale = scale).frequencyHz
        val bass = TuningEngine.tune(48, TuningSystem.SCALA, scalaScale = scale).frequencyHz
        assertEquals(1.25, e / c, 1e-12)
        assertEquals(2.0, c / bass, 1e-12)
        assertEquals(scale, ScalaScaleCodec.decode(ScalaScaleCodec.encode(scale)))
    }

    @Test fun wrongCountPeriodAndDamagedRatiosAreRejected() {
        val input = equalText()
        listOf(input.replace("\r\n12\r\n", "\r\n19\r\n"),
            input.replace("1200.0", "3/1"), input.replace("400.0", "5/0"),
            input.replace("400.0", "-5/4"), input.replace("400.0", "NaN"),
            input.replace("400.0", "1e100.0"), input.replace("400.0", "200.0"),
            "! empty", input + "x".repeat(ScalaScaleCodec.MAX_FILE_BYTES)).forEach {
            assertTrue(runCatching { ScalaScaleCodec.parse(it) }.isFailure)
        }
    }

    @Test fun missingScalaNeverFallsBackSilentlyToEqualTemperament() {
        assertTrue(runCatching { TuningEngine.tune(60, TuningSystem.SCALA) }.isFailure)
    }
}
