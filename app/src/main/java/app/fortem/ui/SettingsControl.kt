// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import app.fortem.R
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

internal const val SETTINGS_TAP_MAX_MILLIS = 250L
internal const val EXIT_ARM_MILLIS = 600L
internal const val EXIT_HOLD_MILLIS = 900L

/** A quick tap opens settings; holding fills the adjacent exit indicator exactly once. */
@Composable
internal fun SettingsControl(onClick: () -> Unit, onExit: () -> Unit) {
    val strings = LocalAppStrings.current
    val currentClick by rememberUpdatedState(onClick)
    val currentExit by rememberUpdatedState(onExit)
    val scope = rememberCoroutineScope()
    val pressed = remember { mutableStateOf(false) }
    val holding = remember { mutableStateOf(false) }
    val progress = remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val movementMargin by rememberUpdatedState(with(density) { 24.dp.toPx() })
    Box(Modifier.size(40.dp)) {
        Surface(color = if (pressed.value) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .66f),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.size(40.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = strings.text(R.string.settings)
                    // Accessibility services have explicit actions, independent of a physical hold.
                    this.onClick(action = { currentClick(); true })
                    this.onLongClick(label = strings.text(R.string.exit_app), action = { currentExit(); true })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // Track the gear finger only; another finger may still be playing a key.
                        val down = awaitFirstDown()
                        down.consume()
                        pressed.value = true
                        holding.value = false
                        progress.floatValue = 0f
                        var completed = false
                        var quickRelease = false
                        val fill = scope.launch {
                            // Frame time keeps the hold duration real even when system animation scaling is zero.
                            val start = withFrameNanos { it }
                            do {
                                val elapsed = withFrameNanos { it } - start
                                // A tap never displays an exit timer. First recognize a deliberate hold,
                                // then start its separate confirmation ring.
                                val fillNanos = elapsed - EXIT_ARM_MILLIS * 1_000_000L
                                if (fillNanos >= 0L) {
                                    holding.value = true
                                    progress.floatValue = (fillNanos.toDouble() / (EXIT_HOLD_MILLIS * 1_000_000L))
                                        .toFloat().coerceIn(0f, 1f)
                                }
                            } while (progress.floatValue < 1f)
                            currentCoroutineContext().ensureActive()
                            completed = true
                            currentExit()
                        }
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                // Consumed releases include Android's synthetic ACTION_CANCEL.
                                if (change.isConsumed) break
                                val inside = change.position.x in -movementMargin..(size.width + movementMargin) &&
                                    change.position.y in -movementMargin..(size.height + movementMargin)
                                if (!inside) break
                                change.consume()
                                if (!change.pressed) {
                                    // The middle interval is deliberately neutral: a cancelled hold
                                    // must never fall through to opening settings, whatever Android's
                                    // configured accessibility long-press timeout happens to be.
                                    quickRelease = !holding.value &&
                                        change.uptimeMillis - down.uptimeMillis < SETTINGS_TAP_MAX_MILLIS
                                    break
                                }
                            }
                        } finally {
                            fill.cancel()
                            pressed.value = false
                            holding.value = false
                            progress.floatValue = 0f
                        }
                        // Releasing an unfinished long hold cancels it instead of opening settings.
                        if (quickRelease && !completed) currentClick()
                    }
                }) {
            Box(contentAlignment = Alignment.Center) { SettingsSymbol(Modifier.size(22.dp)) }
        }
        // This passive overlay is always measured in the same Android window.
        // Neither opening a Popup nor inserting/removing a layout node can interrupt
        // the press. Hidden feedback has no pointer handler and cannot block controls.
        ExitHoldHint(holding, progress, Modifier.align(Alignment.CenterEnd)
            .wrapContentSize(Alignment.CenterEnd, unbounded = true).offset(x = (-48).dp))
    }
}

/** Only drawing/semantics read the per-frame value; no toolbar layout changes during the fill. */
@Composable
private fun ExitHoldHint(holding: State<Boolean>, progress: FloatState, modifier: Modifier = Modifier) {
    val strings = LocalAppStrings.current
    val color = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.graphicsLayer {
                val appearance = (progress.floatValue * 8f).coerceIn(0f, 1f)
                // Observe press state here, without rebuilding the active touch target.
                alpha = if (holding.value) .35f + .65f * appearance else 0f
                scaleX = .94f + .06f * appearance
                scaleY = scaleX
                transformOrigin = TransformOrigin(1f, .5f)
                shape = RoundedCornerShape(50)
                shadowElevation = 4.dp.toPx()
                clip = true
            }
            .background(background, RoundedCornerShape(50))
            .semantics {
                if (holding.value) {
                    testTag = "exit-hold"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress.floatValue, 0f..1f)
                } else hideFromAccessibility()
            }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(24.dp)) {
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2f
                val diameter = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(color.copy(alpha = .2f), -90f, 360f, false,
                    topLeft = Offset(inset, inset), size = diameter, style = stroke)
                drawArc(color, -90f, progress.floatValue * 360f, false,
                    topLeft = Offset(inset, inset), size = diameter, style = stroke)
                // Small power symbol inside the progress ring.
                drawArc(color, -45f, 270f, false, topLeft = Offset(size.width * .28f, size.height * .32f),
                    size = Size(size.width * .44f, size.height * .44f), style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
                drawLine(color, Offset(center.x, size.height * .25f), Offset(center.x, size.height * .52f),
                    strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round)
            }
            Text(strings.text(R.string.exit_hold_label), style = MaterialTheme.typography.labelMedium)
        }
    }
}
