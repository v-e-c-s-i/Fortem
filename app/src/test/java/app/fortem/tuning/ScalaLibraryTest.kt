// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import org.junit.Assert.*
import org.junit.Test

class ScalaLibraryTest {
    private val equal = ScalaScale("Мой строй", (0..11).map { it * 100.0 })
    private val just = equal.copy(cents = equal.cents.mapIndexed { i, c ->
        if (i == 4) 386.313713864835 else c
    })

    @Test fun importsPreserveDifferentScalesEvenWithTheSameTitle() {
        val scales = ScalaLibrary.add(ScalaLibrary.add(emptyList(), equal), just)
        assertEquals(2, scales.size)
        assertNotEquals(equal.libraryId, just.libraryId)
        assertEquals(scales, ScalaLibrary.add(scales, equal.copy()))
        assertEquals(scales, ScalaLibrary.decode(ScalaLibrary.encode(scales)))
    }

    @Test fun deletingOneScaleDoesNotChangeAnotherOrItsSnapshot() {
        val snapshot = ScalaScaleCodec.encode(just)
        val scales = listOf(equal, just).filterNot { it.libraryId == just.libraryId }
        assertEquals(listOf(equal), ScalaLibrary.decode(ScalaLibrary.encode(scales)))
        assertEquals(just, ScalaScaleCodec.decode(snapshot))
        assertEquals(emptyList<ScalaScale>(), ScalaLibrary.decode(ScalaLibrary.encode(emptyList())))
    }

    @Test fun oversizedOrDamagedLibrariesAreRejected() {
        assertTrue(runCatching { ScalaLibrary.decode("!damaged") }.isFailure)
        assertTrue(runCatching { ScalaLibrary.decode("x".repeat(131073)) }.isFailure)
        assertTrue(runCatching {
            ScalaLibrary.add((0 until ScalaLibrary.MAX_SCALES).map { equal.copy(title = "$it") }, just)
        }.isFailure)
    }
}
