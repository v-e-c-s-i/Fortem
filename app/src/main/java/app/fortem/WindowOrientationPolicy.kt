// SPDX-License-Identifier: GPL-3.0-only
package app.fortem

import android.app.Activity
import android.content.pm.ActivityInfo

/** Only full-screen playing requests landscape. A split window owns its own proportions. */
internal fun Activity.applyPerformanceOrientation(inMultiWindow: Boolean) {
    val desired = if (inMultiWindow) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    if (requestedOrientation != desired) requestedOrientation = desired
}
