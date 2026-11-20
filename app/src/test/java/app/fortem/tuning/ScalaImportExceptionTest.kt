// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalaImportExceptionTest {
    private fun equalText(): String = "Test\n12\n" +
        (1..12).joinToString("\n") { "${it * 100}.0" }

    private fun failure(text: String): ScalaImportException {
        val error = runCatching { ScalaScaleCodec.parse(text) }.exceptionOrNull()
        assertTrue(error is ScalaImportException)
        return error as ScalaImportException
    }

    @Test fun validationErrorsExposeReasonsInsteadOfLocalizedSentences() {
        assertEquals(ScalaProblem.MISSING_HEADER, failure("! empty").problem)
        assertEquals(ScalaProblem.UNSUPPORTED_STEPS, failure(equalText().replace("\n12\n", "\n19\n")).problem)
        assertEquals(ScalaProblem.INVALID_OCTAVE, failure(equalText().replace("1200.0", "3/1")).problem)
        assertEquals(ScalaProblem.POSITIVE_RATIO, failure(equalText().replace("400.0", "5/0")).problem)
        assertEquals(ScalaProblem.FILE_TOO_LARGE, failure("x".repeat(65_537)).problem)
    }

    @Test fun invalidTokenIsKeptForTheTranslatedErrorMessage() {
        val error = failure(equalText().replace("400.0", "400.0x"))
        assertEquals(ScalaProblem.INVALID_INTERVAL, error.problem)
        assertEquals("400.0x", error.detail)
    }

    @Test fun validScaleSnapshotAndNameAreNotTranslated() {
        val scale = ScalaScaleCodec.parse(equalText().replace("Test", "Мой строй"))
        assertEquals("Мой строй", scale.title)
        assertEquals(scale, ScalaScaleCodec.decode(ScalaScaleCodec.encode(scale)))
    }
}
