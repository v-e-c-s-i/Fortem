// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.fortem.recording.AndroidMonotonicClock

/** The display clock follows vsync; the musical event scheduler remains independent. */
@Composable
internal fun rememberTransportFrameTime(active: Boolean): LongState {
    val time = remember { mutableLongStateOf(AndroidMonotonicClock.nowNanos()) }
    LaunchedEffect(active) {
        time.longValue = AndroidMonotonicClock.nowNanos()
        while (active) withFrameNanos { time.longValue = AndroidMonotonicClock.nowNanos() }
    }
    return time
}

/** Read the clock in drawing, not composition/layout: even a long list stays still. */
@Composable
internal fun PlaybackProgress(positionNanos: () -> Long, durationNanos: Long,
    color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val progress = if (durationNanos > 0L)
            (positionNanos().toDouble() / durationNanos).toFloat().coerceIn(0f, 1f) else 0f
        drawRect(color, size = Size(size.width * progress, size.height))
    }
}
