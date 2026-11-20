// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import org.junit.Assert.*
import org.junit.Test

class SettingsDismissGestureTest {
    private fun gesture() = SettingsDismissGesture(touchSlop = 8f, distance = 72f)

    @Test fun sliderDragCannotBecomeDismissalEvenAfterTheChildStopsConsuming() {
        val candidate = gesture()
        assertFalse(candidate.update(20f, 0f))
        assertFalse(candidate.update(90f, 0f, consumedByChild = true))
        assertFalse(candidate.update(150f, 0f))
        assertFalse(gesture().update(100f, 0f, consumedByChild = true))
    }

    @Test fun rightSwipeClosesOnceAfterThreshold() {
        val gesture = gesture()
        assertFalse(gesture.update(5f, 3f))
        assertFalse(gesture.update(60f, 15f))
        assertTrue(gesture.update(72f, 18f))
        assertFalse(gesture.update(95f, 20f))
    }

    @Test fun verticalScrollCannotTurnIntoDismissal() {
        val gesture = gesture()
        assertFalse(gesture.update(3f, 12f))
        assertFalse(gesture.update(110f, 20f))
    }

    @Test fun leftSwipeAndDiagonalDoNotClose() {
        val left = gesture()
        assertFalse(left.update(-12f, 0f))
        assertFalse(left.update(90f, 0f))
        assertFalse(gesture().update(90f, 80f))
    }

    @Test fun turningVerticalCancelsCandidate() {
        val gesture = gesture()
        assertFalse(gesture.update(20f, 2f))
        assertFalse(gesture.update(25f, 35f))
        assertFalse(gesture.update(100f, 35f))
    }

    @Test fun SecondFingerCancelsAndSmallTapDoesNotClose() {
        val gesture = gesture()
        assertFalse(gesture.update(25f, 0f))
        gesture.cancel()
        assertFalse(gesture.update(90f, 0f))
        assertFalse(gesture().update(4f, 4f))
    }
}
