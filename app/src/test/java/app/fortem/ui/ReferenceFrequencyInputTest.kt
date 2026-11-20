// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.*
import org.junit.Test

class ReferenceFrequencyInputTest {
    @Test fun acceptsDecimalCommaAndBoundaryValues() {
        assertEquals(440.5, requireNotNull(parseReferenceFrequency(" 440,5 ")), 0.0)
        assertEquals(300.0, requireNotNull(parseReferenceFrequency("300")), 0.0)
        assertEquals(500.0, requireNotNull(parseReferenceFrequency("500")), 0.0)
    }

    @Test fun incompleteOrInvalidInputDoesNotReplaceStoredValue() {
        var stored = 440.0
        listOf("", ".", "44", "NaN", "Infinity", "299.9", "500.1", "ошибка").forEach {
            assertNull(parseReferenceFrequency(it))
            parseReferenceFrequency(it)?.let { value -> stored = value }
        }
        assertEquals(440.0, stored, 0.0)
    }
}
