// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.gestures.ScrollScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual fling with deterministic frame clocks; no device or audio engine. */
class SmoothScrollFlingTest {
    @Test fun differentRefreshRatesAndLongFramesMatchNormalIosListDamping() {
        fun advance(times: List<Float>): ScrollDecayStep {
            var position = 0f
            var velocity = 5000f
            for (seconds in times) {
                val step = scrollDecayStep(velocity, seconds)
                position += step.distance
                velocity = step.velocity
            }
            return ScrollDecayStep(position, velocity)
        }
        val sixty = advance(List(60) { 1f / 60 })
        val oneTwenty = advance(List(120) { 1f / 120 })
        val stalledFrame = advance(List(24) { 1f / 30 } + listOf(.2f))
        val retained = Math.pow(.998, 1000.0)
        val ios = ScrollDecayStep((5000 * (1 - retained) / -Math.log(.998) / 1000).toFloat(),
            (5000 * retained).toFloat())
        for (value in listOf(oneTwenty, stalledFrame, ios)) {
            assertEquals(sixty.distance, value.distance, .002f)
            assertEquals(sixty.velocity, value.velocity, .002f)
        }
        val backwards = scrollDecayStep(-5000f, 1f)
        assertEquals(-sixty.distance, backwards.distance, .002f)
        assertEquals(-sixty.velocity, backwards.velocity, .002f)
    }

    @Test fun flingSlowsMonotonicallyAndStopsWithoutReversing() = runBlocking {
        var frame = 0L
        val increments = mutableListOf<Float>()
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float { increments += pixels; return pixels }
        }
        val behavior = SmoothScrollFlingBehavior(5f) { frame += 16_666_667L; frame }
        val remaining = with(behavior) { scope.performFling(5000f) }
        assertEquals(0f, remaining, .0001f)
        assertTrue(increments.all { it > 0f })
        assertTrue(increments.zipWithNext().all { (a, b) -> b < a })
        assertEquals(2497.5f, increments.sum(), 2.6f)
        assertTrue(increments.size < 240)
    }

    @Test fun boundaryReturnsVelocityImmediatelyInsteadOfFightingTheListEnd() = runBlocking {
        var frame = 0L
        var calls = 0
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float { calls++; return 0f }
        }
        val behavior = SmoothScrollFlingBehavior(5f) { frame += 16_666_667L; frame }
        val remaining = with(behavior) { scope.performFling(-5000f) }
        assertEquals(1, calls)
        assertTrue(remaining < 0f && remaining > -5000f)
    }

    @Test fun touchCancellationPropagatesAndDoesNotLeaveAnotherScrollJob() = runBlocking {
        var frames = 0
        var scrolls = 0
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float { scrolls++; return pixels }
        }
        val behavior = SmoothScrollFlingBehavior(5f) {
            if (++frames == 4) throw CancellationException("new touch")
            frames * 16_666_667L
        }
        try {
            with(behavior) { scope.performFling(5000f) }
            fail("Touch cancellation must terminate the fling")
        } catch (_: CancellationException) {
            assertEquals(2, scrolls)
            assertEquals(4, frames)
        }
    }
}
