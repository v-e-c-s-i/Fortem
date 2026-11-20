// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import kotlin.math.floor

internal const val FIRST_PIANO_MIDI_NOTE = 21 // A0
internal const val LAST_PIANO_MIDI_NOTE = 108 // C8

internal fun isBlackPianoKey(midiNote: Int): Boolean = when (Math.floorMod(midiNote, 12)) {
    1, 3, 6, 8, 10 -> true
    else -> false
}

/** Все 52 белые клавиши стандартного 88-клавишного фортепиано. */
internal val pianoWhiteNotes: IntArray =
    (FIRST_PIANO_MIDI_NOTE..LAST_PIANO_MIDI_NOTE)
        .filterNot(::isBlackPianoKey)
        .toIntArray()

/** Чёрная клавиша расположена на границе двух соседних белых. */
internal data class PianoBlackKey(
    val midiNote: Int,
    val boundaryIndex: Int,
)

internal val pianoBlackKeys: List<PianoBlackKey> =
    (FIRST_PIANO_MIDI_NOTE..LAST_PIANO_MIDI_NOTE)
        .filter(::isBlackPianoKey)
        .map { midiNote ->
            PianoBlackKey(
                midiNote = midiNote,
                boundaryIndex = pianoWhiteNotes.count { it < midiNote },
            )
        }

/**
 * Окно над всей клавиатурой выражено в белых клавишах. Дробное начало нужно,
 * чтобы перемещение и масштабирование на мини-карте оставались плавными.
 */
data class KeyboardViewport(
    val startWhiteKey: Float,
    val visibleWhiteKeys: Float,
) {
    fun clamped(): KeyboardViewport {
        val span = visibleWhiteKeys.coerceIn(MIN_VISIBLE_WHITE_KEYS, MAX_VISIBLE_WHITE_KEYS)
        val lastStart = (pianoWhiteNotes.size - span).coerceAtLeast(0f)
        return copy(
            startWhiteKey = startWhiteKey.coerceIn(0f, lastStart),
            visibleWhiteKeys = span,
        )
    }

    /**
     * Преобразует жест на мини-карте в перемещение и масштаб основного окна.
     * Центр щипка остаётся над той же нотой, а панорамирование двигает само окно.
     */
    fun transformed(
        mapWidthPx: Float,
        centroidXPx: Float,
        panXPx: Float,
        zoomFactor: Float,
    ): KeyboardViewport {
        if (mapWidthPx <= 0f || !zoomFactor.isFinite() || zoomFactor <= 0f) return clamped()

        val anchorFraction = (centroidXPx / mapWidthPx).coerceIn(0f, 1f)
        val anchorWhiteKey = startWhiteKey + visibleWhiteKeys * anchorFraction
        val newSpan = (visibleWhiteKeys / zoomFactor)
            .coerceIn(MIN_VISIBLE_WHITE_KEYS, MAX_VISIBLE_WHITE_KEYS)
        val startAfterZoom = anchorWhiteKey - newSpan * anchorFraction
        val panInWhiteKeys = panXPx / mapWidthPx * pianoWhiteNotes.size

        return KeyboardViewport(
            startWhiteKey = startAfterZoom + panInWhiteKeys,
            visibleWhiteKeys = newSpan,
        ).clamped()
    }

    /**
     * Меняет только ширину окна, удерживая его геометрический центр на месте.
     * Если центр находится близко к краю фортепиано, окно упирается в границу,
     * но никогда не выходит за диапазон A0–C8.
     */
    fun resizedAroundCenter(spanScale: Float): KeyboardViewport {
        if (!spanScale.isFinite() || spanScale <= 0f) return clamped()

        val centerWhiteKey = startWhiteKey + visibleWhiteKeys / 2f
        // Карта управляет именно шириной видимого диапазона: раздвижение
        // пальцев расширяет рамку, а сведение сужает её.
        val newSpan = (visibleWhiteKeys * spanScale)
            .coerceIn(MIN_VISIBLE_WHITE_KEYS, MAX_VISIBLE_WHITE_KEYS)
        return KeyboardViewport(
            startWhiteKey = centerWhiteKey - newSpan / 2f,
            visibleWhiteKeys = newSpan,
        ).clamped()
    }

    /**
     * Ставит геометрический центр окна под выбранную точку мини-карты.
     * Так одиночного касания достаточно, чтобы перейти в далёкую октаву.
     */
    fun centeredAtMapFraction(fraction: Float): KeyboardViewport {
        val centerWhiteKey = fraction.coerceIn(0f, 1f) * pianoWhiteNotes.size
        return copy(
            startWhiteKey = centerWhiteKey - visibleWhiteKeys / 2f,
        ).clamped()
    }

    fun whiteKeyIndexAtFraction(fraction: Float): Int =
        floor(startWhiteKey + visibleWhiteKeys * fraction.coerceIn(0f, 1f))
            .toInt()
            .coerceIn(pianoWhiteNotes.indices)

    /** При воспроизведении используется самый широкий доступный обзор записи. */
    fun overviewForPlayback(midiNotes: Set<Int>): KeyboardViewport {
        if (midiNotes.isEmpty()) return copy(visibleWhiteKeys = MAX_VISIBLE_WHITE_KEYS).clamped()
        val coordinates = midiNotes.map(::whiteCoordinateForMidiNote)
        val center = (requireNotNull(coordinates.minOrNull()) +
            requireNotNull(coordinates.maxOrNull())) / 2f
        return KeyboardViewport(
            startWhiteKey = center - MAX_VISIBLE_WHITE_KEYS / 2f,
            visibleWhiteKeys = MAX_VISIBLE_WHITE_KEYS,
        ).clamped()
    }

    /** Если широкий обзор не вместил всю пьесу, окно следует только за активными нотами. */
    fun following(midiNotes: Set<Int>): KeyboardViewport {
        if (midiNotes.isEmpty()) return clamped()
        val safe = clamped()
        val coordinates = midiNotes.map(::whiteCoordinateForMidiNote)
        val left = requireNotNull(coordinates.minOrNull())
        val right = requireNotNull(coordinates.maxOrNull())
        if (left >= safe.startWhiteKey && right <= safe.startWhiteKey + safe.visibleWhiteKeys) {
            return safe
        }
        val center = (left + right) / 2f
        return copy(startWhiteKey = center - visibleWhiteKeys / 2f).clamped()
    }

    companion object {
        const val MIN_VISIBLE_WHITE_KEYS = 4f
        const val MAX_VISIBLE_WHITE_KEYS = 24f
        const val DEFAULT_VISIBLE_WHITE_KEYS = 10f // C4 through E5, including both keys.

        val DEFAULT = KeyboardViewport(
            startWhiteKey = pianoWhiteNotes.indexOf(60).toFloat(), // C4
            visibleWhiteKeys = DEFAULT_VISIBLE_WHITE_KEYS,
        )
    }
}

/** Чёрная клавиша получает координату границы между соседними белыми клавишами. */
private fun whiteCoordinateForMidiNote(midiNote: Int): Float {
    val safeNote = midiNote.coerceIn(FIRST_PIANO_MIDI_NOTE, LAST_PIANO_MIDI_NOTE)
    val exactWhiteIndex = pianoWhiteNotes.indexOf(safeNote)
    if (exactWhiteIndex >= 0) return exactWhiteIndex.toFloat() + 0.5f
    return pianoWhiteNotes.count { it < safeNote }.toFloat()
}
