// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Мини-карта всех 88 клавиш. Один палец перемещает окно, два пальца меняют его
 * ширину. Любое короткое касание переносит окно к выбранной ноте.
 */
@Composable
internal fun KeyboardNavigator(
    viewport: KeyboardViewport,
    onViewportChange: (KeyboardViewport) -> Unit,
    activeNotes: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val density = LocalDensity.current
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
    val whiteColor = Color(0xFFBCC2BF)
    val blackColor = Color(0xFF252B28)
    val activeWhiteColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
    val activeBlackColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    val selectionOutline = MaterialTheme.colorScheme.primary
    val resizeHandleColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
    // Тёмные подписи читаются на светлых клавишах и под рамкой диапазона.
    val labelColor = Color(0xFF343936)
    val navigatorCornerRadius = 11.dp
    val selectionInset = 1.dp
    var resizeActive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(navigatorCornerRadius))
            .testTag("keyboard-map")
            .semantics {
                contentDescription =
                    strings.text(R.string.keyboard_map_description)
            }
            .pointerInput(Unit) {
                // Один обработчик исключает борьбу распознавателей щипка и
                // перемещения. Отдельного двойного касания намеренно нет.
                awaitEachGesture {
                        if (size.width == 0) return@awaitEachGesture
                        val firstEvent = awaitPointerEvent()
                        val firstDown = firstEvent.changes.firstOrNull { it.pressed }
                            ?: return@awaitEachGesture

                        // Новое касание немедленно останавливает инерцию.
                        flingJob?.cancel()
                        val velocityTracker = VelocityTracker().apply {
                            addPosition(firstDown.uptimeMillis, firstDown.position)
                        }

                        var gestureViewport = currentViewport
                        var lastPositions = mapOf(firstDown.id.value to firstDown.position)
                        var firstPosition = firstDown.position
                        var panStarted = false
                        var transformed = false
                        var hadMultiplePointers = false
                        var pinchActive = false
                        var resizeStartDistance = 0f
                        var resizeStartViewport = gestureViewport

                        while (true) {
                            val event = awaitPointerEvent()
                            if (size.width == 0 || event.changes.any { !it.pressed && it.previousPressed && it.isConsumed }) {
                                resizeActive = false
                                break
                            }
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.isEmpty()) {
                                resizeActive = false
                                if (!transformed && !hadMultiplePointers) {
                                    // Каждое касание, в том числе повторное, означает
                                    // только переход к выбранной точке карты.
                                    gestureViewport = gestureViewport.centeredAtMapFraction(
                                        firstPosition.x / size.width.toFloat(),
                                    )
                                    currentOnViewportChange(gestureViewport)
                                } else if (panStarted && !hadMultiplePointers) {
                                    // Скорость в пикселях карты; щипок не запускает инерцию.
                                    event.changes.firstOrNull()?.let {
                                        velocityTracker.addPosition(it.uptimeMillis, it.position)
                                    }
                                    val velocity = velocityTracker.calculateVelocity().x
                                        .coerceIn(-8000f, 8000f)
                                    val mapWidth = size.width.toFloat()
                                    if (mapWidth > 0f && abs(velocity) * pianoWhiteNotes.size / mapWidth > .5f) {
                                        val releasedViewport = gestureViewport
                                        flingJob = scope.launch {
                                            val pixelsPerKey = mapWidth / pianoWhiteNotes.size
                                            var motion = MapInertia(releasedViewport.startWhiteKey, velocity / pixelsPerKey)
                                            val maximumStart = pianoWhiteNotes.size - releasedViewport.visibleWhiteKeys
                                            var previousFrame = withFrameNanos { it }
                                            while (abs(motion.velocity) > .15f) {
                                                val frame = withFrameNanos { it }
                                                val seconds = ((frame - previousFrame) / 1_000_000_000.0).toFloat()
                                                previousFrame = frame
                                                motion = motion.advance(seconds, maximumStart)
                                                currentOnViewportChange(releasedViewport.copy(startWhiteKey = motion.position))
                                            }
                                        }
                                    }
                                }
                                event.changes.forEach { it.consume() }
                                break
                            }

                            val currentPositions = pressed.associate { it.id.value to it.position }
                            if (pressed.size >= 2) {
                                val first = pressed[0].position
                                val second = pressed[1].position
                                val distanceNow = distance(first, second)
                                if (!pinchActive) {
                                    // Щипок можно начинать в любом месте карты. В этот
                                    // момент фиксируем окно: дальше меняется только ширина.
                                    hadMultiplePointers = true
                                    pinchActive = true
                                    resizeActive = true
                                    resizeStartDistance = distanceNow.coerceAtLeast(1f)
                                    resizeStartViewport = gestureViewport
                                } else if (distanceNow > 0f) {
                                    val rawSpanScale = distanceNow / resizeStartDistance
                                    val responsiveSpanScale = rawSpanScale.toDouble()
                                        .pow(PINCH_SENSITIVITY.toDouble())
                                        .toFloat()
                                    gestureViewport = resizeStartViewport
                                        .resizedAroundCenter(responsiveSpanScale)
                                    currentOnViewportChange(gestureViewport)
                                    transformed = true
                                }
                            } else {
                                resizeActive = false
                                val change = pressed.single()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val oldPosition = lastPositions[change.id.value]
                                if (pinchActive) {
                                    // После отпускания второго пальца оставшийся сразу
                                    // начинает новый обычный жест без скачка рамки.
                                    pinchActive = false
                                    firstPosition = change.position
                                    panStarted = false
                                } else if (oldPosition != null && lastPositions.size == 1) {
                                    val totalDistance = distance(firstPosition, change.position)
                                    if (panStarted || totalDistance > viewConfiguration.touchSlop) {
                                        panStarted = true
                                        val panWhiteKeys = (change.position.x - oldPosition.x) /
                                            size.width.toFloat() * pianoWhiteNotes.size
                                        gestureViewport = gestureViewport.copy(
                                            startWhiteKey = gestureViewport.startWhiteKey + panWhiteKeys,
                                        ).clamped()
                                        currentOnViewportChange(gestureViewport)
                                        transformed = true
                                    }
                                } else {
                                    firstPosition = change.position
                                    panStarted = false
                                }
                            }

                            lastPositions = currentPositions
                            event.changes.forEach { it.consume() }
                        }
                    }
            },
    ) {
        drawRect(surfaceColor)

        val whiteWidth = size.width / pianoWhiteNotes.size
        val keyTop = 3.dp.toPx()
        val keyBottom = size.height - 3.dp.toPx()
        val keyHeight = (keyBottom - keyTop).coerceAtLeast(0f)

        pianoWhiteNotes.forEachIndexed { index, midiNote ->
            // Единый цвет белых клавиш, как до экспериментов с разметкой октав.
            drawRect(
                color = if (midiNote in activeNotes) {
                    activeWhiteColor
                } else {
                    whiteColor
                },
                topLeft = Offset(index * whiteWidth, keyTop),
                size = Size((whiteWidth - 0.35.dp.toPx()).coerceAtLeast(0.5f), keyHeight),
            )
        }

        pianoBlackKeys.forEach { blackKey ->
            val blackWidth = whiteWidth * 0.62f
            val centerX = whiteWidth * blackKey.boundaryIndex
            drawRect(
                color = if (blackKey.midiNote in activeNotes) activeBlackColor else blackColor,
                topLeft = Offset(centerX - blackWidth / 2f, keyTop),
                size = Size(blackWidth, keyHeight * 0.57f),
            )
        }

        val left = viewport.startWhiteKey / pianoWhiteNotes.size * size.width
        val right = (viewport.startWhiteKey + viewport.visibleWhiteKeys) /
            pianoWhiteNotes.size * size.width
        // Внутренний отступ сохраняет обводку целиком и делает её скругление
        // концентричным внешним углам панели, без визуального среза у A0 и C8.
        val insetPx = selectionInset.toPx()
        val selectionLeft = left + insetPx
        val selectionRight = right - insetPx
        val selectionTop = insetPx
        val selectionHeight = (size.height - insetPx * 2f).coerceAtLeast(1f)
        val selectionCornerPx = (navigatorCornerRadius - selectionInset).toPx()
        val selectionCorner = CornerRadius(selectionCornerPx, selectionCornerPx)
        drawRoundRect(
            color = selectionColor,
            topLeft = Offset(selectionLeft, selectionTop),
            size = Size((selectionRight - selectionLeft).coerceAtLeast(1f), selectionHeight),
            cornerRadius = selectionCorner,
        )
        drawRoundRect(
            color = selectionOutline,
            topLeft = Offset(selectionLeft, selectionTop),
            size = Size((selectionRight - selectionLeft).coerceAtLeast(1f), selectionHeight),
            cornerRadius = selectionCorner,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        if (resizeActive) {
            // Яркие боковые планки видны вокруг пальцев и однозначно показывают,
            // что сейчас регулируется ширина, а не положение диапазона.
            val handleWidth = 3.dp.toPx()
            val handleInset = 5.dp.toPx()
            val handleHeight = (size.height - handleInset * 2f).coerceAtLeast(1f)
            val handleRadius = CornerRadius(handleWidth, handleWidth)
            drawRoundRect(
                color = resizeHandleColor,
                topLeft = Offset(selectionLeft - handleWidth / 2f, handleInset),
                size = Size(handleWidth, handleHeight),
                cornerRadius = handleRadius,
            )
            drawRoundRect(
                color = resizeHandleColor,
                topLeft = Offset(selectionRight - handleWidth / 2f, handleInset),
                size = Size(handleWidth, handleHeight),
                cornerRadius = handleRadius,
            )
        }

        // Компактные метки C1–C7 показывают границы октав; крайняя C8
        // остаётся без подписи, чтобы текст не обрезался краем карты.
        val paint = labelPaint.apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 6.5.sp.toPx() }
        }
        pianoWhiteNotes.forEachIndexed { index, midiNote ->
            if (midiNote % 12 == 0 && midiNote < LAST_PIANO_MIDI_NOTE) {
                drawContext.canvas.nativeCanvas.drawText(
                    "C${midiNote / 12 - 1}",
                    (index + 0.5f) * whiteWidth,
                    size.height - 4.dp.toPx(),
                    paint,
                )
            }
        }
    }
}

private fun distance(first: Offset, second: Offset): Float =
    hypot(first.x - second.x, first.y - second.y)

/** Усиливает небольшое изменение расстояния между пальцами на компактной карте. */
private const val PINCH_SENSITIVITY = 1.35f
