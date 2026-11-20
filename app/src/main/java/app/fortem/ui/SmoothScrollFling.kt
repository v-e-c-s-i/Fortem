// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1

// UIKit's normal list deceleration retains 0.998 of velocity per millisecond.
// Convert it to continuous time: -1000 * ln(0.998), so refresh rate does not change the glide.
private const val LIST_DECAY_PER_SECOND = 2.0020026706730793

/** The piano map's exponential principle, tuned for the longer glide of iOS lists. */
internal data class ScrollDecayStep(val distance: Float, val velocity: Float)

internal fun scrollDecayStep(velocity: Float, seconds: Float): ScrollDecayStep {
    if (!velocity.isFinite() || !seconds.isFinite() || seconds <= 0f) {
        return ScrollDecayStep(0f, if (velocity.isFinite()) velocity else 0f)
    }
    val exponent = -LIST_DECAY_PER_SECOND * seconds
    // expm1 retains precision for small frame deltas and the final, slow part of the glide.
    return ScrollDecayStep((-velocity * expm1(exponent) / LIST_DECAY_PER_SECOND).toFloat(),
        (velocity * exp(exponent)).toFloat())
}

/** Remembering the behavior prevents playback/recomposition from restarting an active fling. */
@Composable
internal fun rememberSmoothScrollFlingBehavior(): FlingBehavior {
    val stopVelocity = with(LocalDensity.current) { 5.dp.toPx() }
    return remember(stopVelocity) { SmoothScrollFlingBehavior(stopVelocity) }
}

/** Compose owns touch cancellation and nested scrolling; only free-flight damping is replaced. */
internal class SmoothScrollFlingBehavior(
    private val stopVelocity: Float,
    private val nextFrame: suspend () -> Long = { withFrameNanos { it } },
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (!initialVelocity.isFinite()) return 0f
        if (abs(initialVelocity) <= stopVelocity) return initialVelocity
        var velocity = initialVelocity
        var previous = nextFrame()
        while (abs(velocity) > stopVelocity) {
            val frame = nextFrame()
            val seconds = ((frame - previous) / 1_000_000_000.0).toFloat()
            previous = frame
            val step = scrollDecayStep(velocity, seconds)
            val consumed = scrollBy(step.distance)
            velocity = step.velocity
            // Forward unconsumed momentum at a boundary; never run a second animation/job.
            if (abs(step.distance - consumed) > .5f) return velocity
        }
        return 0f
    }
}
