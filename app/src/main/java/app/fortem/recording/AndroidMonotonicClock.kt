// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import android.os.SystemClock

/** Время не меняется при синхронизации часов и переводе часового пояса. */
object AndroidMonotonicClock : MonotonicClock {
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

