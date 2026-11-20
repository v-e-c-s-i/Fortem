// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardGestureClassifierTest {
    private val touchSlop = 8f

    @Test
    fun verticalSwipeBecomesLatchGesture() {
        val result = classify(deltaX = 2f, deltaY = -24f, crossedKey = false)

        assertEquals(KeyboardGestureIntent.LATCH, result)
    }

    @Test
    fun chromaticDiagonalBecomesGlissando() {
        val result = classify(deltaX = 20f, deltaY = -20f, crossedKey = true)

        assertEquals(KeyboardGestureIntent.GLISSANDO, result)
    }

    @Test
    fun diagonalWithinWideWhiteKeyBecomesGlissandoBeforeBoundary() {
        val result = classify(deltaX = 30f, deltaY = -40f, crossedKey = false)

        assertEquals(KeyboardGestureIntent.GLISSANDO, result)
    }

    @Test
    fun naturalThirtyDegreeDriftCanStillLatch() {
        val result = classify(deltaX = 30f, deltaY = -60f, crossedKey = false)

        assertEquals(KeyboardGestureIntent.LATCH, result)
    }

    @Test
    fun leavingStartingKeyLaneAlwaysBecomesGlissando() {
        val result = classify(
            deltaX = 20f,
            deltaY = -60f,
            crossedKey = true,
            insideStartingKeyLane = false,
        )

        assertEquals(KeyboardGestureIntent.GLISSANDO, result)
    }

    @Test
    fun verticalOverlapWithBlackKeyRemainsLatchGesture() {
        val result = classify(deltaX = 2f, deltaY = -24f, crossedKey = true)

        assertEquals(KeyboardGestureIntent.LATCH, result)
    }

    @Test
    fun smallHorizontalDriftDoesNotCancelLatchGesture() {
        val result = classify(
            currentIntent = KeyboardGestureIntent.LATCH,
            deltaX = 7f,
            deltaY = -60f,
            crossedKey = true,
        )

        assertEquals(KeyboardGestureIntent.LATCH, result)
    }

    @Test
    fun deliberateVerticalTurnAfterGlissandoCanStartLatch() {
        val result = KeyboardGestureClassifier.classifyGlissandoTurn(
            deltaX = 2f,
            deltaY = -24f,
            touchSlop = touchSlop,
            crossedKeyBoundary = false,
            insideCurrentKeyLane = true,
        )

        assertEquals(GlissandoTurnDecision.START_LATCH, result)
    }

    @Test
    fun slightlyImperfectVerticalTurnAfterGlissandoCanStartLatch() {
        val result = KeyboardGestureClassifier.classifyGlissandoTurn(
            deltaX = 15f,
            deltaY = -40f,
            touchSlop = touchSlop,
            crossedKeyBoundary = false,
            insideCurrentKeyLane = true,
        )

        assertEquals(GlissandoTurnDecision.START_LATCH, result)
    }

    @Test
    fun continuousDiagonalGlissandoCannotStartLatch() {
        val result = KeyboardGestureClassifier.classifyGlissandoTurn(
            deltaX = 20f,
            deltaY = -30f,
            touchSlop = touchSlop,
            crossedKeyBoundary = false,
            insideCurrentKeyLane = true,
        )

        assertEquals(GlissandoTurnDecision.RESET_ORIGIN, result)
    }

    @Test
    fun crossingAnotherKeyResetsPostGlissandoOrigin() {
        val result = KeyboardGestureClassifier.classifyGlissandoTurn(
            deltaX = 2f,
            deltaY = -24f,
            touchSlop = touchSlop,
            crossedKeyBoundary = true,
            insideCurrentKeyLane = true,
        )

        assertEquals(GlissandoTurnDecision.RESET_ORIGIN, result)
    }

    @Test
    fun smallPostGlissandoJitterKeepsWaiting() {
        val result = KeyboardGestureClassifier.classifyGlissandoTurn(
            deltaX = 3f,
            deltaY = -5f,
            touchSlop = touchSlop,
            crossedKeyBoundary = false,
            insideCurrentKeyLane = true,
        )

        assertEquals(GlissandoTurnDecision.WAIT, result)
    }

    @Test
    fun latchCandidateTurnsIntoGlissandoWhenDirectionChanges() {
        val result = classify(
            currentIntent = KeyboardGestureIntent.LATCH,
            deltaX = 30f,
            deltaY = -20f,
            crossedKey = true,
        )

        assertEquals(KeyboardGestureIntent.GLISSANDO, result)
    }

    @Test
    fun steepDiagonalCannotLatchAfterCrossingARealKeyBoundary() {
        val result = classify(
            currentIntent = KeyboardGestureIntent.LATCH,
            deltaX = 24f,
            deltaY = -30f,
            crossedKey = true,
        )

        assertEquals(KeyboardGestureIntent.GLISSANDO, result)
    }

    private fun classify(
        currentIntent: KeyboardGestureIntent = KeyboardGestureIntent.UNDECIDED,
        deltaX: Float,
        deltaY: Float,
        crossedKey: Boolean,
        insideStartingKeyLane: Boolean = true,
    ): KeyboardGestureIntent = KeyboardGestureClassifier.classify(
        currentIntent = currentIntent,
        deltaX = deltaX,
        deltaY = deltaY,
        touchSlop = touchSlop,
        crossedKeyBoundary = crossedKey,
        insideStartingKeyLane = insideStartingKeyLane,
    )
}
