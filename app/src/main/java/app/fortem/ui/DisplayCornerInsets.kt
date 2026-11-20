// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.WindowInsets
import kotlin.math.ceil

internal fun View.displayCornerInsets(inMultiWindow: Boolean = false): WindowInsets =
    // Split-window corner masks belong to the system compositor. Reserving the
    // physical display radius at the divider would shorten the entire playing area.
    if (inMultiWindow) WindowInsets(0, 0, 0, 0)
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) readDisplayCornerInsets()
    else WindowInsets(0, 0, 0, 0)

@RequiresApi(31)
private fun View.readDisplayCornerInsets(): WindowInsets {
    val insets = rootWindowInsets
    // Protect the inner rectangle, not the whole corner radius. At 45° the
    // corner meets this rectangle; cutouts and bars still take precedence.
    fun inset(position: Int): Int {
        val radius = insets?.getRoundedCorner(position)?.radius ?: return 0
        return ceil(radius * (1.0 - 1.0 / kotlin.math.sqrt(2.0))).toInt()
    }
    val topLeft = inset(RoundedCorner.POSITION_TOP_LEFT)
    val topRight = inset(RoundedCorner.POSITION_TOP_RIGHT)
    val bottomLeft = inset(RoundedCorner.POSITION_BOTTOM_LEFT)
    val bottomRight = inset(RoundedCorner.POSITION_BOTTOM_RIGHT)
    return WindowInsets(
        left = maxOf(topLeft, bottomLeft), top = maxOf(topLeft, topRight),
        right = maxOf(topRight, bottomRight), bottom = maxOf(bottomLeft, bottomRight),
    )
}
