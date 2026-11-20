// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

internal data class PerformanceLayout(
    val mapBelowPiano: Boolean,
    val controlScale: Float,
    val bottomMapHeightDp: Float,
)

/** Fit the two toolbar rows as a unit, leaving the rest of the window to the piano. */
internal fun performanceLayout(
    safeWidthDp: Float,
    safeHeightDp: Float,
    inMultiWindow: Boolean,
    preferredScale: Float,
): PerformanceLayout {
    // A top/bottom split on a portrait phone often gives the app a short, wide
    // rectangle. Width and window mode together distinguish it from full landscape.
    val below = safeWidthDp < 480f || safeWidthDp <= safeHeightDp ||
        (inMultiWindow && safeWidthDp < 600f)
    val mapHeight = if (below) (safeHeightDp * .24f).coerceAtMost(64f) else 0f
    val naturalScale = if (below) {
        (safeWidthDp / 500f).coerceIn(.7f, 1f)
    } else {
        val ratio = minOf(safeWidthDp / 760f, safeHeightDp / 380f)
        // Keep the familiar phone proportions and grow more gently on larger screens.
        (if (ratio <= 1f) ratio else 1f + (ratio - 1f) * .55f).coerceIn(.78f, 1.25f)
    }
    val widthLimit = safeWidthDp / (if (below) 500f else 560f)
    // Keep space for playing even when the divider is dragged to a very small window.
    val keyboardSpace = (safeHeightDp * .40f).coerceAtMost(128f)
    val heightLimit = (safeHeightDp - keyboardSpace - mapHeight - (if (below) 6f else 0f)) / 108f
    return PerformanceLayout(
        mapBelowPiano = below,
        controlScale = minOf(naturalScale * preferredScale, widthLimit, heightLimit).coerceAtLeast(.05f),
        bottomMapHeightDp = mapHeight,
    )
}
