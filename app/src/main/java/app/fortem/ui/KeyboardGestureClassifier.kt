// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import kotlin.math.abs

/** Намерение одного пальца после начала движения по клавиатуре. */
internal enum class KeyboardGestureIntent {
    UNDECIDED,
    LATCH,
    GLISSANDO,
}

/** Решение для нового вертикального участка после проведения по клавишам. */
internal enum class GlissandoTurnDecision {
    WAIT,
    RESET_ORIGIN,
    START_LATCH,
}

/**
 * Отделяет вертикальное закрепление от проведения по нескольким клавишам.
 * Направление фиксируется по системному touch slop, поэтому правило одинаково
 * работает при разной плотности экрана и ширине клавиш.
 */
internal object KeyboardGestureClassifier {
    // Наклон до 30° от вертикали считается естественной неточностью свайпа.
    private const val LATCH_CONE_TANGENT = 0.58f

    // После глиссандо требуется более явный поворот вверх: это не позволяет
    // непрерывной диагональной траектории снова создавать случайные замки.
    private const val GLISSANDO_TURN_CONE_TANGENT = 0.42f

    fun classify(
        currentIntent: KeyboardGestureIntent,
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        crossedKeyBoundary: Boolean,
        insideStartingKeyLane: Boolean,
    ): KeyboardGestureIntent {
        // Проведение по клавишам остаётся проведением до снятия этого пальца.
        if (currentIntent == KeyboardGestureIntent.GLISSANDO) {
            return KeyboardGestureIntent.GLISSANDO
        }

        val horizontalDistance = abs(deltaX)
        val upwardDistance = -deltaY
        val movedHorizontally = horizontalDistance > touchSlop
        val movedUpward = upwardDistance > touchSlop
        val isVerticalEnough = movedUpward &&
            horizontalDistance <= upwardDistance * LATCH_CONE_TANGENT

        return when (currentIntent) {
            KeyboardGestureIntent.LATCH -> {
                if (!insideStartingKeyLane || (movedHorizontally && !isVerticalEnough)) {
                    // Выход из исходной клавиши или слишком большой наклон
                    // отменяет ещё не завершённое закрепление.
                    KeyboardGestureIntent.GLISSANDO
                } else {
                    KeyboardGestureIntent.LATCH
                }
            }

            KeyboardGestureIntent.UNDECIDED -> when {
                !insideStartingKeyLane -> KeyboardGestureIntent.GLISSANDO
                isVerticalEnough -> KeyboardGestureIntent.LATCH
                // Движение, вышедшее из системного допуска, но не попавшее
                // в вертикальный конус, считается проведением по клавишам.
                movedHorizontally -> KeyboardGestureIntent.GLISSANDO
                // Движение вниз через границу белой и чёрной клавиши — обычное
                // проведение, а не начало жеста закрепления.
                crossedKeyBoundary && deltaY >= 0f -> KeyboardGestureIntent.GLISSANDO

                else -> KeyboardGestureIntent.UNDECIDED
            }

            KeyboardGestureIntent.GLISSANDO -> KeyboardGestureIntent.GLISSANDO
        }
    }

    fun classifyGlissandoTurn(
        deltaX: Float,
        deltaY: Float,
        touchSlop: Float,
        crossedKeyBoundary: Boolean,
        insideCurrentKeyLane: Boolean,
    ): GlissandoTurnDecision {
        if (crossedKeyBoundary || !insideCurrentKeyLane) {
            return GlissandoTurnDecision.RESET_ORIGIN
        }

        val horizontalDistance = abs(deltaX)
        val upwardDistance = -deltaY
        val movedBeyondSlop = horizontalDistance > touchSlop || abs(deltaY) > touchSlop
        if (!movedBeyondSlop) return GlissandoTurnDecision.WAIT

        val isDeliberateVerticalTurn = upwardDistance > touchSlop &&
            horizontalDistance <= upwardDistance * GLISSANDO_TURN_CONE_TANGENT
        return if (isDeliberateVerticalTurn) {
            GlissandoTurnDecision.START_LATCH
        } else {
            // Диагональное или нисходящее продолжение переносит точку отсчёта
            // вперёд. Замок станет возможен только после отдельного поворота вверх.
            GlissandoTurnDecision.RESET_ORIGIN
        }
    }
}
