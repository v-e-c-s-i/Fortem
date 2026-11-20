// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import kotlin.math.exp

/** Frame-rate independent inertia, using the same five-per-second decay as iOS. */
internal data class MapInertia(val position: Float, val velocity: Float) {
    fun advance(seconds: Float, maximumStart: Float): MapInertia {
        if (!seconds.isFinite() || seconds <= 0f) return this
        val decay = exp(-5.0 * seconds).toFloat()
        val next = position + velocity * (1f - decay) / 5f
        val bounded = next.coerceIn(0f, maximumStart.coerceAtLeast(0f))
        return MapInertia(bounded, if (bounded != next) 0f else velocity * decay)
    }
}
