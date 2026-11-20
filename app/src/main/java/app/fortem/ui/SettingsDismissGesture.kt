// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import kotlin.math.abs

/** Закрытие панели требует намеренного свайпа вправо, а не прокрутки или касания. */
internal class SettingsDismissGesture(private val touchSlop: Float, private val distance: Float) {
    private enum class Direction { UNDECIDED, RIGHT, CANCELLED, DISMISSED }
    private var direction = Direction.UNDECIDED

    fun cancel() { direction = Direction.CANCELLED }

    fun update(dx: Float, dy: Float, consumedByChild: Boolean = false): Boolean {
        // Once a child owns the gesture, keep dismissal cancelled until every finger lifts.
        if (consumedByChild) cancel()
        if (direction == Direction.CANCELLED || direction == Direction.DISMISSED) return false
        if (direction == Direction.UNDECIDED) {
            if (maxOf(abs(dx), abs(dy)) <= touchSlop) return false
            direction = if (dx > 0f && dx > abs(dy) * 1.5f) Direction.RIGHT else Direction.CANCELLED
        }
        if (direction == Direction.RIGHT && (dx < 0f || abs(dy) > maxOf(touchSlop, dx / 1.5f))) {
            cancel()
        }
        if (direction == Direction.RIGHT && dx >= distance) {
            direction = Direction.DISMISSED
            return true
        }
        return false
    }
}
