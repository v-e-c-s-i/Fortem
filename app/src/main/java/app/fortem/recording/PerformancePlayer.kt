// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import kotlinx.coroutines.delay

/** Воспроизводит события относительно монотонных часов, компенсируя задержки coroutine. */
class PerformancePlayer(private val clock: MonotonicClock) {
    suspend fun play(
        recording: PerformanceRecording,
        onEvent: (PerformanceEvent) -> Unit,
    ) {
        val startNanos = clock.nowNanos()
        recording.events.sortedBy(PerformanceEvent::timeNanos).forEach { event ->
            delayUntil(startNanos + event.timeNanos)
            onEvent(event)
        }
        delayUntil(startNanos + recording.durationNanos)
    }

    private suspend fun delayUntil(targetNanos: Long) {
        while (true) {
            val remainingNanos = targetNanos - clock.nowNanos()
            if (remainingNanos <= 0L) return
            delay((remainingNanos / 1_000_000L).coerceAtLeast(1L))
        }
    }
}
