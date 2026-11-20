// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.tuning.ScalaScale
import app.fortem.tuning.TuningSystem

data class VisibleTuningSelection(
    val tunings: Set<TuningSystem>,
    val scalaIds: Set<String>,
    val selectedTuning: TuningSystem,
    val selectedScale: ScalaScale?,
)

/** Скрытие последнего варианта никогда не оставляет экран без работоспособного строя. */
fun resolveTuningVisibility(
    tunings: Set<TuningSystem>,
    scalaIds: Set<String>,
    scales: List<ScalaScale>,
    selected: TuningSystem,
    selectedScale: ScalaScale?,
): VisibleTuningSelection {
    val visibleScales = scales.filter { it.libraryId in scalaIds }
    val validIds = visibleScales.mapTo(linkedSetOf()) { it.libraryId }
    val available = (tunings - TuningSystem.SCALA).toMutableSet()
    if (visibleScales.isNotEmpty()) available += TuningSystem.SCALA
    if (available.isEmpty()) available += TuningSystem.EQUAL_TEMPERAMENT
    val scale = selectedScale?.takeIf { it.libraryId in validIds } ?: visibleScales.firstOrNull()
    val choice = if (selected in available) selected else
        TuningSystem.entries.firstOrNull { it in available && it != TuningSystem.SCALA }
            ?: TuningSystem.SCALA
    return VisibleTuningSelection(available, validIds, choice, scale ?: selectedScale)
}
