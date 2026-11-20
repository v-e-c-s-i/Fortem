// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.tuning.ScalaScale
import app.fortem.tuning.TuningSystem
import org.junit.Assert.*
import org.junit.Test

class TuningVisibilityTest {
    private val equal = ScalaScale("Первый", (0..11).map { it * 100.0 })
    private val other = equal.copy(title = "Второй")

    @Test fun hidingActiveBuiltinImmediatelySelectsAnAvailableTuning() {
        val result = resolveTuningVisibility(setOf(TuningSystem.JUST_INTONATION), emptySet(),
            emptyList(), TuningSystem.PYTHAGOREAN, null)
        assertEquals(TuningSystem.JUST_INTONATION, result.selectedTuning)
        assertTrue(result.selectedTuning in result.tunings)
    }

    @Test fun hidingActiveScalaSelectsTheRemainingProfile() {
        val result = resolveTuningVisibility(setOf(TuningSystem.SCALA), setOf(other.libraryId),
            listOf(equal, other), TuningSystem.SCALA, equal)
        assertEquals(TuningSystem.SCALA, result.selectedTuning)
        assertEquals(other, result.selectedScale)
    }

    @Test fun deletingTheLastScalaRestoresAUsableBuiltin() {
        val result = resolveTuningVisibility(setOf(TuningSystem.SCALA), setOf(equal.libraryId),
            emptyList(), TuningSystem.SCALA, null)
        assertEquals(setOf(TuningSystem.EQUAL_TEMPERAMENT), result.tunings)
        assertEquals(TuningSystem.EQUAL_TEMPERAMENT, result.selectedTuning)
        assertTrue(result.scalaIds.isEmpty())
    }

    @Test fun invalidIdsNeverCreateASelectionWithoutASnapshot() {
        val result = resolveTuningVisibility(emptySet(), setOf("missing"), listOf(equal),
            TuningSystem.SCALA, equal)
        assertEquals(TuningSystem.EQUAL_TEMPERAMENT, result.selectedTuning)
        assertTrue(result.scalaIds.isEmpty())
    }
}
