// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Local phone time, chronological ordering uses the epoch ID (not the name).
// Milliseconds distinguish takes begun within the same second.
fun recordingName(id: Long): String = "REC_" +
    SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(id))

fun recordingDuration(nanos: Long): String {
    val seconds = nanos.coerceAtLeast(0L) / 1_000_000_000L
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}
