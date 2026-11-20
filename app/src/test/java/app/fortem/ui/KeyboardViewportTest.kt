// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardViewportTest {
    @Test
    fun standardPianoRangeContainsAllEightyEightKeys() {
        assertEquals(88, LAST_PIANO_MIDI_NOTE - FIRST_PIANO_MIDI_NOTE + 1)
        assertEquals(52, pianoWhiteNotes.size)
        assertEquals(36, pianoBlackKeys.size)
        assertEquals(21, pianoWhiteNotes.first())
        assertEquals(108, pianoWhiteNotes.last())
    }

    @Test
    fun defaultViewportStartsAtMiddleC() {
        val firstVisibleNote = pianoWhiteNotes[KeyboardViewport.DEFAULT.startWhiteKey.toInt()]

        assertEquals(60, firstVisibleNote)
        assertEquals(10f, KeyboardViewport.DEFAULT.visibleWhiteKeys)
        val lastIndex = (KeyboardViewport.DEFAULT.startWhiteKey +
            KeyboardViewport.DEFAULT.visibleWhiteKeys).toInt() - 1
        assertEquals(76, pianoWhiteNotes[lastIndex]) // E5
    }

    @Test
    fun pinchKeepsAnchorUnderFingers() {
        val viewport = KeyboardViewport.DEFAULT
        val anchorBefore = viewport.startWhiteKey + viewport.visibleWhiteKeys * 0.75f
        val transformed = viewport.transformed(
            mapWidthPx = 1000f,
            centroidXPx = 750f,
            panXPx = 0f,
            zoomFactor = 2f,
        )
        val anchorAfter = transformed.startWhiteKey + transformed.visibleWhiteKeys * 0.75f

        assertEquals(anchorBefore, anchorAfter, 0.001f)
        assertEquals(5f, transformed.visibleWhiteKeys, 0.001f)
    }

    @Test
    fun oneFingerPanMovesWindowAcrossMap() {
        val transformed = KeyboardViewport.DEFAULT.transformed(
            mapWidthPx = 520f,
            centroidXPx = 260f,
            panXPx = 52f,
            zoomFactor = 1f,
        )

        assertEquals(
            KeyboardViewport.DEFAULT.startWhiteKey + 5.2f,
            transformed.startWhiteKey,
            0.001f,
        )
    }

    @Test
    fun mapTapCentersViewportAtSelectedPosition() {
        val viewport = KeyboardViewport(startWhiteKey = 0f, visibleWhiteKeys = 8f)
        val centered = viewport.centeredAtMapFraction(0.5f)

        assertEquals(
            pianoWhiteNotes.size / 2f,
            centered.startWhiteKey + centered.visibleWhiteKeys / 2f,
            0.001f,
        )
    }

    @Test
    fun mapTapNearEdgesKeepsViewportInsidePiano() {
        val viewport = KeyboardViewport.DEFAULT
        val left = viewport.centeredAtMapFraction(-1f)
        val right = viewport.centeredAtMapFraction(2f)

        assertEquals(0f, left.startWhiteKey, 0.001f)
        assertEquals(
            pianoWhiteNotes.size - right.visibleWhiteKeys,
            right.startWhiteKey,
            0.001f,
        )
    }

    @Test
    fun resizeKeepsGeometricCenterFixed() {
        val viewport = KeyboardViewport(startWhiteKey = 20f, visibleWhiteKeys = 12f)
        val centerBefore = viewport.startWhiteKey + viewport.visibleWhiteKeys / 2f
        val resized = viewport.resizedAroundCenter(spanScale = 1.5f)
        val centerAfter = resized.startWhiteKey + resized.visibleWhiteKeys / 2f

        assertEquals(centerBefore, centerAfter, 0.001f)
        assertEquals(18f, resized.visibleWhiteKeys, 0.001f)
    }

    @Test
    fun spreadingFingersExpandsSelectedRange() {
        val viewport = KeyboardViewport(startWhiteKey = 20f, visibleWhiteKeys = 8f)
        val expanded = viewport.resizedAroundCenter(spanScale = 1.25f)
        val contracted = viewport.resizedAroundCenter(spanScale = 0.75f)

        assertEquals(10f, expanded.visibleWhiteKeys, 0.001f)
        assertEquals(6f, contracted.visibleWhiteKeys, 0.001f)
    }

    @Test
    fun resizeAtPianoEdgeStaysInsideRange() {
        val resized = KeyboardViewport(startWhiteKey = 0f, visibleWhiteKeys = 8f)
            .resizedAroundCenter(spanScale = 4f)

        assertEquals(0f, resized.startWhiteKey, 0.001f)
        assertTrue(resized.startWhiteKey + resized.visibleWhiteKeys <= pianoWhiteNotes.size)
    }

    @Test
    fun viewportCannotLeavePianoOrBecomeUnplayablySmall() {
        val transformed = KeyboardViewport(
            startWhiteKey = -100f,
            visibleWhiteKeys = 1f,
        ).clamped()

        assertEquals(0f, transformed.startWhiteKey, 0.001f)
        assertEquals(KeyboardViewport.MIN_VISIBLE_WHITE_KEYS, transformed.visibleWhiteKeys, 0.001f)
        assertTrue(transformed.startWhiteKey + transformed.visibleWhiteKeys <= pianoWhiteNotes.size)
    }

    @Test
    fun playbackOverviewUsesWidestAvailableRange() {
        val overview = KeyboardViewport.DEFAULT.overviewForPlayback(setOf(36, 84))

        assertEquals(KeyboardViewport.MAX_VISIBLE_WHITE_KEYS, overview.visibleWhiteKeys, 0.001f)
        assertTrue(overview.startWhiteKey >= 0f)
        assertTrue(overview.startWhiteKey + overview.visibleWhiteKeys <= pianoWhiteNotes.size)
    }
}
