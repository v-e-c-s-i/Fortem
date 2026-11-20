// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fortem.tuning.TuningEngine
import kotlinx.coroutines.delay

// Для закрепления требуется вертикальное движение на 63 dp. Длинный жест
// отделяет намеренное закрепление от диагонального проведения по клавишам.
private val LatchGestureDistance = 63.dp

// Состояние одного пальца хранится до его отпускания. После глиссандо точка
// отсчёта замка переносится на текущую ноту, не создавая новое касание.
private data class PointerContact(
    var midiNote: Int?,
    var latestPosition: Offset,
    var latchOriginMidiNote: Int?,
    var latchOriginPosition: Offset,
    var intent: KeyboardGestureIntent = KeyboardGestureIntent.UNDECIDED,
    var isLatched: Boolean = false,
)

/**
 * Мультитач-клавиатура с полным фортепианным диапазоном A0–C8.
 *
 * Один callback отправляется на ноту, даже если её одновременно держат два пальца.
 * Это предупреждает преждевременный note-off при отпускании только одного пальца.
 */
@Composable
fun PianoKeyboard(
    viewport: KeyboardViewport,
    onNoteOn: (Int) -> Unit,
    onNoteOff: (Int) -> Unit,
    onActiveNotesChanged: (Set<Int>) -> Unit,
    onLatchedNotesChanged: (Set<Int>) -> Unit = {},
    clearLatchesSignal: Int = 0,
    resetSignal: Int = 0,
    holdUntilNextChord: Boolean = false,
    latchGesturesEnabled: Boolean = true,
    onNextChordNotesChanged: (Set<Int>) -> Unit = {},
    inputEnabled: Boolean = true,
    externalActiveNotes: Set<Int> = emptySet(),
    externalLatchedNotes: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    // Явные объекты состояния позволяют жесту и кнопке «Снять все» сразу видеть
    // изменения друг друга даже до следующей перерисовки Compose.
    val activeNotesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val pressedNotesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val latchedNotesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val nextChordNotesState = remember { mutableStateOf<Set<Int>>(emptySet()) }
    val chordPedal = remember { ChordPedal(holdUntilNextChord) }
    val pedalReleaseDeadlineState = remember { mutableStateOf<Long?>(null) }
    val activeNotes by activeNotesState
    val latchedNotes by latchedNotesState

    // Актуальные callback используются без перезапуска обработки текущего жеста.
    val currentOnNoteOn by rememberUpdatedState(onNoteOn)
    val currentOnNoteOff by rememberUpdatedState(onNoteOff)
    val currentOnActiveNotesChanged by rememberUpdatedState(onActiveNotesChanged)
    val currentOnLatchedNotesChanged by rememberUpdatedState(onLatchedNotesChanged)
    val currentOnNextChordNotesChanged by rememberUpdatedState(onNextChordNotesChanged)
    val currentViewport by rememberUpdatedState(viewport)
    val currentLatchGesturesEnabled by rememberUpdatedState(latchGesturesEnabled)
    val viewportFollower = remember { KeyboardViewportFollower() }
    // Срабатывает на каждый новый кадр диапазона, даже когда палец неподвижен.
    // Проверка равенства внутри follower не создаёт повторных атак той же ноты.
    SideEffect {
        if (inputEnabled) viewportFollower.update(viewport)
    }

    fun publishState(pressed: Set<Int> = pressedNotesState.value) {
        pedalReleaseDeadlineState.value = chordPedal.nextReleaseAtMillis
        pressedNotesState.value = pressed
        activeNotesState.value = pressed + latchedNotesState.value + nextChordNotesState.value
        currentOnActiveNotesChanged(activeNotesState.value)
        currentOnNextChordNotesChanged(nextChordNotesState.value)
        currentOnLatchedNotesChanged(latchedNotesState.value)
    }

    fun unlockNote(midiNote: Int) {
        if (midiNote !in latchedNotesState.value) return
        latchedNotesState.value -= midiNote
        if (midiNote !in pressedNotesState.value && midiNote !in nextChordNotesState.value) currentOnNoteOff(midiNote)
        publishState()
    }

    fun unlockAllNotes() {
        // Открытый замок освобождает только ручные закрепления. Педаль независима:
        // её ноты и отложенные отпускания остаются под управлением ChordPedal.
        val notesToStop = notesWithoutRemainingHold(
            latchedNotesState.value, pressedNotesState.value, nextChordNotesState.value)
        latchedNotesState.value = emptySet()
        notesToStop.forEach(currentOnNoteOff)
        publishState()
    }

    LaunchedEffect(clearLatchesSignal) {
        if (clearLatchesSignal > 0) unlockAllNotes()
    }

    // Disabling manual latches releases their notes, while fingers and pedal keep sounding.
    LaunchedEffect(latchGesturesEnabled) {
        if (!latchGesturesEnabled) {
            chordPedal.restorePedalContacts()
            unlockAllNotes()
        }
    }

    LaunchedEffect(holdUntilNextChord) {
        chordPedal.changeEnabled(holdUntilNextChord)
        if (!holdUntilNextChord && nextChordNotesState.value.isNotEmpty()) {
            // Выключение режима немедленно отпускает только его собственные ноты.
            val notesToStop = notesWithoutRemainingHold(
                nextChordNotesState.value, pressedNotesState.value, latchedNotesState.value)
            nextChordNotesState.value = emptySet()
            notesToStop.forEach(currentOnNoteOff)
            publishState()
        }
    }

    // Один ожидающий переход вместо постоянного опроса во время игры.
    // Подсветка и записываемый note-off меняются вместе с настоящим отпусканием.
    LaunchedEffect(pedalReleaseDeadlineState.value, resetSignal, inputEnabled) {
        val deadline = pedalReleaseDeadlineState.value
        if (deadline != null && inputEnabled) {
            while (SystemClock.uptimeMillis() < deadline) {
                delay((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L))
            }
            val released = chordPedal.releaseDue(SystemClock.uptimeMillis())
            nextChordNotesState.value = chordPedal.heldNotes
            notesWithoutRemainingHold(released, pressedNotesState.value, latchedNotesState.value)
                .forEach(currentOnNoteOff)
            publishState()
        }
    }

    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) {
            // После ухода приложения в фон движок останавливается, поэтому замки сбрасываются.
            activeNotesState.value = emptySet()
            pressedNotesState.value = emptySet()
            latchedNotesState.value = emptySet()
            nextChordNotesState.value = emptySet()
            chordPedal.cancelGesture()
            pedalReleaseDeadlineState.value = null
            currentOnActiveNotesChanged(emptySet())
            currentOnNextChordNotesChanged(emptySet())
            currentOnLatchedNotesChanged(emptySet())
        }
    }

    // Все белые клавиши снова одного исходного цвета. Октавы подписаны на карте.
    val whiteColor = Color(0xFFD9DDDB)
    val whiteActiveColor = MaterialTheme.colorScheme.primaryContainer
    val blackColor = Color(0xFF202523)
    val blackActiveColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val labelColor = Color(0xFF343936)
    val activeLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
    val whiteLockColor = MaterialTheme.colorScheme.onPrimaryContainer
    val blackLockColor = MaterialTheme.colorScheme.onPrimary
    // Paint сохраняется между кадрами: касание не создаёт новый нативный объект.
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    val density = LocalDensity.current
    val latchThresholdPx = with(density) { LatchGestureDistance.toPx() }
    val lockHitRadiusPx = with(density) { 22.dp.toPx() }
    val currentNavigationInset by rememberUpdatedState(WindowInsets.mandatorySystemGestures.getBottom(density).toFloat())
    val windowView = LocalView.current
    // Compare with window coordinates so visible navigation bars/padding do not steal key space.
    val keyboardTopInWindow = remember { floatArrayOf(0f) }

    Canvas(
        modifier = modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .onGloballyPositioned { keyboardTopInWindow[0] = it.positionInWindow().y }
                .semantics {
                    contentDescription = strings.text(R.string.piano_keyboard_description)
                }
                .pointerInput(resetSignal, inputEnabled) {
                    // Во время воспроизведения клавиатуру рисует проигрыватель,
                    // а живые касания временно не смешиваются с записью.
                    if (!inputEnabled) return@pointerInput
                    val touchSlopPx = viewConfiguration.touchSlop

                    awaitEachGesture {
                        val pointerContacts = mutableMapOf<Long, PointerContact>()
                        val knownPointers = mutableSetOf<Long>()
                        val lockTapPointers = mutableSetOf<Long>()
                        val navigationPointers = mutableSetOf<Long>()
                        val latchesBeforeGesture = latchedNotesState.value
                        var completed = false
                        val pressLedger = KeyboardPressLedger(
                            onNoteOn = currentOnNoteOn,
                            onNoteOff = currentOnNoteOff,
                            isLatched = { it in latchedNotesState.value || it in nextChordNotesState.value },
                        )

                        fun publishGestureState() {
                            publishState(pressLedger.pressedNotes())
                        }

                        fun releasePointer(pointerId: Long): Boolean {
                            val contact = pointerContacts.remove(pointerId) ?: return false
                            val note = contact.midiNote
                            if (currentLatchGesturesEnabled && (contact.isLatched || (note != null && note in latchedNotesState.value))) chordPedal.manualLatch(pointerId)
                            chordPedal.release(pointerId)
                            // Обновляем held до note-off реестра: педальные
                            // ноты сохраняют открытый gate в звуковом движке.
                            nextChordNotesState.value = chordPedal.heldNotes
                            return pressLedger.release(pointerId)
                        }

                        fun beginPointer(pointerId: Long, midiNote: Int, position: Offset): Boolean {
                            chordPedal.press(pointerId, midiNote, SystemClock.uptimeMillis())
                            nextChordNotesState.value = chordPedal.heldNotes
                            pointerContacts[pointerId] = PointerContact(
                                midiNote = midiNote,
                                latestPosition = position,
                                latchOriginMidiNote = midiNote,
                                latchOriginPosition = position,
                            )
                            return pressLedger.press(pointerId, midiNote)
                        }

                        fun resetLatchOrigin(contact: PointerContact, position: Offset) {
                            contact.latchOriginMidiNote = contact.midiNote
                            contact.latchOriginPosition = position
                        }

                        fun moveGlissandoPointer(pointerId: Long, newNote: Int?): Boolean {
                            val contact = pointerContacts[pointerId] ?: return false
                            if (contact.midiNote == newNote) return false

                            contact.midiNote = newNote
                            chordPedal.move(pointerId, newNote)
                            nextChordNotesState.value = chordPedal.heldNotes
                            return pressLedger.move(pointerId, newNote)
                        }

                        fun latchPointer(pointerId: Long): Boolean {
                            val contact = pointerContacts[pointerId] ?: return false
                            val midiNote = contact.midiNote ?: return false
                            if (!currentLatchGesturesEnabled || contact.isLatched) return false
                            contact.isLatched = true
                            chordPedal.manualLatch(pointerId)
                            latchedNotesState.value += midiNote
                            return true
                        }

                        viewportFollower.attach(currentViewport) { movedViewport ->
                            var stateChanged = false
                            pointerContacts.forEach { (pointerId, contact) ->
                                if (!currentLatchGesturesEnabled) contact.isLatched = false
                                // Ручной замок остаётся привязанным к ноте; обычное
                                // удержание следует за клавишей под текущим пальцем.
                                if (!contact.isLatched) {
                                    val note = hitTestKeyboard(contact.latestPosition, size, movedViewport)
                                    if (note != contact.midiNote) {
                                        stateChanged = moveGlissandoPointer(pointerId, note) || stateChanged
                                        contact.intent = KeyboardGestureIntent.GLISSANDO
                                        resetLatchOrigin(contact, contact.latestPosition)
                                    }
                                }
                            }
                            if (stateChanged) publishGestureState()
                        }

                        try {
                            // awaitEachGesture уже предоставляет область чтения событий указателя.
                            // Дополнительная вложенная awaitPointerEventScope здесь недопустима.
                            while (true) {
                                val event = awaitPointerEvent()
                                // Compose represents ACTION_CANCEL as already-consumed releases.
                                // Leave contacts intact for rollback instead of treating this as normal Up.
                                if (event.changes.any { !it.pressed && it.previousPressed && it.isConsumed }) break
                                var stateChanged = false
                                event.changes.forEach { change ->
                                    val pointerId = change.id.value
                                    val isFirstSample = pointerId !in knownPointers
                                    if (isFirstSample) knownPointers += pointerId

                                    // A Home swipe starts in Android's mandatory bottom gesture area.
                                    // Ignore that contact for its entire lifetime, even after it enters a key.
                                    if (change.pressed && isFirstSample && currentNavigationInset > 0f &&
                                        change.position.y + keyboardTopInWindow[0] >=
                                            windowView.rootView.height - currentNavigationInset) {
                                        navigationPointers += pointerId
                                    }
                                    if (pointerId in navigationPointers) {
                                        if (!change.pressed) { navigationPointers -= pointerId; knownPointers -= pointerId }
                                        return@forEach
                                    }
                                    if (change.pressed && isFirstSample) {
                                        val lockedNote = hitTestLatchIcon(
                                            position = change.position,
                                            size = size,
                                            viewport = currentViewport,
                                            latchedNotes = latchedNotesState.value,
                                            hitRadiusPx = lockHitRadiusPx,
                                        )
                                        if (lockedNote != null) {
                                            // Касание именно значка снимает замок и не запускает новую ноту.
                                            unlockNote(lockedNote)
                                            lockTapPointers += pointerId
                                        }
                                    }

                                    if (pointerId !in lockTapPointers) {
                                        if (change.pressed) {
                                            var contact = pointerContacts[pointerId]
                                            val touchedNote = hitTestKeyboard(
                                                position = change.position,
                                                size = size,
                                                viewport = currentViewport,
                                            )

                                            if (contact == null && touchedNote != null) {
                                                stateChanged = beginPointer(
                                                    pointerId,
                                                    touchedNote,
                                                    change.position,
                                                ) || stateChanged
                                                contact = pointerContacts[pointerId]
                                            }

                                            val activeContact = contact
                                            activeContact?.latestPosition = change.position
                                            if (!currentLatchGesturesEnabled) activeContact?.isLatched = false
                                            if (activeContact != null && !activeContact.isLatched) {
                                                val crossedKeyBoundary = touchedNote != activeContact.midiNote
                                                val previousIntent = activeContact.intent

                                                if (!currentLatchGesturesEnabled) {
                                                    // With latching off, upward slides must still reach black keys.
                                                    activeContact.intent = KeyboardGestureIntent.GLISSANDO
                                                    stateChanged = moveGlissandoPointer(pointerId, touchedNote) || stateChanged
                                                    resetLatchOrigin(activeContact, change.position)
                                                } else if (previousIntent == KeyboardGestureIntent.GLISSANDO) {
                                                    if (crossedKeyBoundary) {
                                                        stateChanged = moveGlissandoPointer(
                                                            pointerId,
                                                            touchedNote,
                                                        ) || stateChanged
                                                        resetLatchOrigin(activeContact, change.position)
                                                    } else {
                                                        val originNote = activeContact.latchOriginMidiNote
                                                        if (originNote != null) {
                                                            val delta = change.position -
                                                                activeContact.latchOriginPosition
                                                            val originKeyRect = keyRect(
                                                                originNote,
                                                                // В PointerInputScope размер уже IntSize.
                                                                size,
                                                                currentViewport,
                                                            )
                                                            val insideCurrentKeyLane = change.position.x in
                                                                (originKeyRect.left - touchSlopPx)..
                                                                (originKeyRect.right + touchSlopPx)
                                                            val turnDecision =
                                                                KeyboardGestureClassifier
                                                                        .classifyGlissandoTurn(
                                                                            deltaX = delta.x,
                                                                            deltaY = delta.y,
                                                                            touchSlop = touchSlopPx,
                                                                            crossedKeyBoundary = false,
                                                                            insideCurrentKeyLane =
                                                                                insideCurrentKeyLane,
                                                                        )
                                                            when (turnDecision) {
                                                                GlissandoTurnDecision.START_LATCH -> {
                                                                    activeContact.intent =
                                                                        KeyboardGestureIntent.LATCH
                                                                }

                                                                GlissandoTurnDecision.RESET_ORIGIN -> {
                                                                    resetLatchOrigin(
                                                                        activeContact,
                                                                        change.position,
                                                                    )
                                                                }

                                                                GlissandoTurnDecision.WAIT -> Unit
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    val originNote = activeContact.latchOriginMidiNote
                                                    if (originNote != null) {
                                                        val delta = change.position -
                                                            activeContact.latchOriginPosition
                                                        val originKeyRect = keyRect(
                                                            originNote,
                                                            size,
                                                            currentViewport,
                                                        )
                                                        // Коридор повторяет фактическую ширину ноты
                                                        // и расширяется только на системный допуск.
                                                        val insideOriginKeyLane = change.position.x in
                                                            (originKeyRect.left - touchSlopPx)..
                                                            (originKeyRect.right + touchSlopPx)
                                                        activeContact.intent =
                                                            KeyboardGestureClassifier.classify(
                                                                currentIntent = previousIntent,
                                                                deltaX = delta.x,
                                                                deltaY = delta.y,
                                                                touchSlop = touchSlopPx,
                                                                crossedKeyBoundary = crossedKeyBoundary,
                                                                insideStartingKeyLane =
                                                                    insideOriginKeyLane,
                                                            )

                                                        if (
                                                            activeContact.intent ==
                                                            KeyboardGestureIntent.GLISSANDO
                                                        ) {
                                                            stateChanged = moveGlissandoPointer(
                                                                pointerId,
                                                                touchedNote,
                                                            ) || stateChanged
                                                            resetLatchOrigin(
                                                                activeContact,
                                                                change.position,
                                                            )
                                                        }
                                                    }
                                                }

                                                when (activeContact.intent) {
                                                    KeyboardGestureIntent.GLISSANDO -> {
                                                        // Нота уже обновлена выше. Отдельный поворот
                                                        // вверх сможет начать новый жест закрепления.
                                                    }

                                                    KeyboardGestureIntent.LATCH -> {
                                                        val delta = change.position -
                                                            activeContact.latchOriginPosition
                                                        val upwardDistance = -delta.y
                                                        if (upwardDistance >= latchThresholdPx) {
                                                            // Закрепляется нота, на которой палец
                                                            // находится после первоначального нажатия
                                                            // или завершённого глиссандо.
                                                            stateChanged = latchPointer(pointerId) ||
                                                                stateChanged
                                                        }
                                                    }

                                                    KeyboardGestureIntent.UNDECIDED -> Unit
                                                }
                                            }
                                        } else {
                                            stateChanged = releasePointer(pointerId) ||
                                                stateChanged
                                        }
                                    }

                                    if (!change.pressed) {
                                        knownPointers -= pointerId
                                        lockTapPointers -= pointerId
                                    }
                                    change.consume()
                                }

                                // Все изменения одного системного события публикуются одним
                                // снимком: три одновременных пальца не создают промежуточные
                                // состояния подсветки из одной или двух нот.
                                if (stateChanged) publishGestureState()

                                if (event.changes.none { it.pressed }) { completed = true; break }
                            }
                        } finally {
                            viewportFollower.detach()
                            // Roll back latches created by a cancelled gesture, preserving older locks.
                            if (!completed) {
                                val cancelled = latchedNotesState.value - latchesBeforeGesture
                                latchedNotesState.value -= cancelled
                                notesWithoutRemainingHold(cancelled, pressLedger.pressedNotes(), nextChordNotesState.value)
                                    .forEach(currentOnNoteOff)
                            }
                            // Отмена системного жеста не должна оставлять «зависшие» ноты.
                            if (pointerContacts.isNotEmpty()) {
                                val heldToStop = nextChordNotesState.value - latchedNotesState.value - pressLedger.pressedNotes()
                                chordPedal.cancelGesture()
                                nextChordNotesState.value = emptySet()
                                heldToStop.forEach(currentOnNoteOff)
                            }
                            pointerContacts.clear()
                            pressLedger.releaseAll()
                            publishGestureState()
                        }
                    }
                },
    ) {
        // При воспроизведении внешний снимок является единственным источником
        // подсветки: старое живое состояние не мелькает даже один кадр.
        val displayedActiveNotes = if (inputEnabled) {
            activeNotes + externalActiveNotes
        } else {
            externalActiveNotes
        }
        val displayedLatchedNotes = if (inputEnabled) {
            latchedNotes + externalLatchedNotes
        } else {
            externalLatchedNotes
        }
        val safeViewport = viewport.clamped()
        val whiteWidth = size.width / safeViewport.visibleWhiteKeys
        val keyStroke = 1.dp.toPx()
        val fullLabelThreshold = 30.dp.toPx()
        val octaveLabelThreshold = 15.dp.toPx()
        val labelBaseline = size.height - 14.dp.toPx()

        pianoWhiteNotes.forEachIndexed { index, midiNote ->
            val rect = whiteKeyRect(index, size.toIntSize(), safeViewport)
            if (rect.right < 0f || rect.left > size.width) return@forEachIndexed

            drawRect(
                color = if (midiNote in displayedActiveNotes) {
                    whiteActiveColor
                } else {
                    whiteColor
                },
                topLeft = rect.topLeft,
                size = rect.size,
            )
            // Только разделитель клавиш: полная обводка создавала лишние
            // горизонтальные линии и выступала над скруглёнными чёрными клавишами.
            if (shouldDrawWhiteKeyDivider(index, safeViewport)) {
                val belowBlackKey = pianoBlackKeys.any { it.boundaryIndex == index }
                drawLine(
                    color = outlineColor,
                    start = Offset(rect.left, if (belowBlackKey) size.height * .62f else 0f),
                    end = Offset(rect.left, size.height),
                    strokeWidth = keyStroke,
                )
            }

            // При сильном отдалении остаются только метки C: полный текст иначе
            // налезал бы на соседние клавиши и мешал игре.
            val showFullLabel = whiteWidth >= fullLabelThreshold
            val showOctaveLabel = midiNote % 12 == 0 &&
                whiteWidth >= octaveLabelThreshold
            if (showFullLabel || showOctaveLabel) {
                labelPaint.color = if (midiNote in displayedActiveNotes) {
                    activeLabelColor.toArgb()
                } else {
                    labelColor.toArgb()
                }
                labelPaint.textSize = if (showFullLabel) 13.sp.toPx() else 10.sp.toPx()
                drawContext.canvas.nativeCanvas.drawText(
                    TuningEngine.midiNoteName(midiNote),
                    rect.center.x,
                    labelBaseline,
                    labelPaint,
                )
            }
        }

        pianoBlackKeys.forEach { blackKey ->
            val rect = blackKeyRect(blackKey, size.toIntSize(), safeViewport)
            if (rect.right < 0f || rect.left > size.width) return@forEach
            drawRoundRect(
                color = if (blackKey.midiNote in displayedActiveNotes) {
                    blackActiveColor
                } else {
                    blackColor
                },
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    minOf(5.dp.toPx(), rect.width * 0.18f),
                ),
            )
        }

        // Значок рисуется только на видимой закреплённой клавише. Само
        // закрепление остаётся активным и при перемещении диапазона.
        displayedLatchedNotes.forEach { midiNote ->
            val rect = keyRect(midiNote, size.toIntSize(), safeViewport)
            if (rect.right >= 0f && rect.left <= size.width) {
                drawPadlockWithKeyhole(
                    center = latchIconCenter(midiNote, size.toIntSize(), safeViewport),
                    color = if (isBlackPianoKey(midiNote)) blackLockColor else whiteLockColor,
                    scale = (rect.width / 20.dp.toPx()).coerceIn(0.55f, 1f),
                )
            }
        }
    }
}

/** Контур, высокая дужка и скважина отличают замок от сумки. */
private fun DrawScope.drawPadlockWithKeyhole(center: Offset, color: Color, scale: Float = 1f) {
    val strokeWidth = 1.8.dp.toPx() * scale
    val bodyWidth = 14.dp.toPx() * scale
    val bodyHeight = 11.dp.toPx() * scale
    val bodyTop = center.y - 1.dp.toPx() * scale
    val shackleHalfWidth = 4.dp.toPx() * scale
    val shackleTop = bodyTop - 9.dp.toPx() * scale

    val shackle = Path().apply {
        moveTo(center.x - shackleHalfWidth, bodyTop)
        lineTo(center.x - shackleHalfWidth, bodyTop - 4.dp.toPx() * scale)
        cubicTo(
            center.x - shackleHalfWidth,
            shackleTop,
            center.x + shackleHalfWidth,
            shackleTop,
            center.x + shackleHalfWidth,
            bodyTop - 4.dp.toPx() * scale,
        )
        lineTo(center.x + shackleHalfWidth, bodyTop)
    }
    drawPath(
        path = shackle,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - bodyWidth / 2f, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx() * scale),
        style = Stroke(width = strokeWidth),
    )
    val keyholeCenter = Offset(center.x, bodyTop + 4.2.dp.toPx() * scale)
    drawCircle(color = color, radius = 1.3.dp.toPx() * scale, center = keyholeCenter)
    drawLine(
        color = color,
        start = Offset(center.x, keyholeCenter.y + 1.dp.toPx() * scale),
        end = Offset(center.x, keyholeCenter.y + 3.4.dp.toPx() * scale),
        strokeWidth = 1.5.dp.toPx() * scale,
        cap = StrokeCap.Round,
    )
}

private fun hitTestKeyboard(
    position: Offset,
    size: IntSize,
    viewport: KeyboardViewport,
): Int? {
    return pianoNoteAt(position.x, position.y, size.width.toFloat(), size.height.toFloat(), viewport)
}

private fun hitTestLatchIcon(
    position: Offset,
    size: IntSize,
    viewport: KeyboardViewport,
    latchedNotes: Set<Int>,
    hitRadiusPx: Float,
): Int? = latchedNotes.firstOrNull { midiNote ->
    val rect = keyRect(midiNote, size, viewport)
    if (rect.right < 0f || rect.left > size.width) return@firstOrNull false
    val center = latchIconCenter(midiNote, size, viewport)
    val dx = position.x - center.x
    val dy = position.y - center.y
    // При сильном отдалении зоны соседних замков не должны перекрываться.
    val effectiveRadius = minOf(hitRadiusPx, rect.width * 0.44f)
    dx * dx + dy * dy <= effectiveRadius * effectiveRadius
}

private fun latchIconCenter(
    midiNote: Int,
    size: IntSize,
    viewport: KeyboardViewport,
): Offset {
    val keyRect = keyRect(midiNote, size, viewport)
    val isBlackKey = isBlackPianoKey(midiNote)
    return Offset(
        x = keyRect.center.x,
        y = if (isBlackKey) keyRect.bottom * 0.76f else keyRect.bottom * 0.78f,
    )
}

private fun keyRect(midiNote: Int, size: IntSize, viewport: KeyboardViewport): Rect {
    pianoBlackKeys.firstOrNull { it.midiNote == midiNote }?.let {
        return blackKeyRect(it, size, viewport)
    }

    val whiteIndex = pianoWhiteNotes.indexOf(midiNote).coerceAtLeast(0)
    return whiteKeyRect(whiteIndex, size, viewport)
}

private fun whiteKeyRect(
    whiteIndex: Int,
    size: IntSize,
    viewport: KeyboardViewport,
): Rect {
    val whiteWidth = size.width.toFloat() / viewport.visibleWhiteKeys
    val left = (whiteIndex - viewport.startWhiteKey) * whiteWidth
    return Rect(
        left = left,
        top = 0f,
        right = left + whiteWidth,
        bottom = size.height.toFloat(),
    )
}

private fun blackKeyRect(
    blackKey: PianoBlackKey,
    size: IntSize,
    viewport: KeyboardViewport,
): Rect {
    val whiteWidth = size.width.toFloat() / viewport.visibleWhiteKeys
    val blackWidth = whiteWidth * 0.62f
    val centerX = whiteWidth * (blackKey.boundaryIndex - viewport.startWhiteKey)
    return Rect(
        left = centerX - blackWidth / 2f,
        top = 0f,
        right = centerX + blackWidth / 2f,
        bottom = size.height * 0.62f,
    )
}

private fun Size.toIntSize(): IntSize = IntSize(width.toInt(), height.toInt())
