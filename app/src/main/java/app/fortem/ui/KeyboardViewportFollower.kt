// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

/** Движение клавиатуры — самостоятельный источник обновления удержанных нот.
 * Обработчик принадлежит текущему жесту, а не таймеру или событиям второго пальца. */
internal class KeyboardViewportFollower {
    private var previousViewport: KeyboardViewport? = null
    private var onMove: ((KeyboardViewport) -> Unit)? = null

    fun attach(viewport: KeyboardViewport, onMove: (KeyboardViewport) -> Unit) {
        previousViewport = viewport
        this.onMove = onMove
    }

    fun update(viewport: KeyboardViewport) {
        if (viewport == previousViewport) return
        previousViewport = viewport
        onMove?.invoke(viewport)
    }

    fun detach() {
        onMove = null
        previousViewport = null
    }
}

/** Общая геометрия для обычного касания и неподвижного пальца при инерции. */
internal fun pianoNoteAt(x: Float, y: Float, width: Float, height: Float,
    viewport: KeyboardViewport): Int? {
    if (width <= 0f || height <= 0f || !x.isFinite() || !y.isFinite() ||
        x !in 0f..width || y !in 0f..height) return null
    val whiteWidth = width / viewport.visibleWhiteKeys
    if (y < height * .62f) {
        pianoBlackKeys.firstOrNull { key ->
            val center = whiteWidth * (key.boundaryIndex - viewport.startWhiteKey)
            x >= center - whiteWidth * .31f && x < center + whiteWidth * .31f
        }?.let { return it.midiNote }
    }
    val index = (viewport.startWhiteKey + x / whiteWidth).toInt()
        .coerceIn(pianoWhiteNotes.indices)
    return pianoWhiteNotes[index]
}

/** У неполных крайних клавиш нет отдельной линии внутри скругления панели. */
internal fun shouldDrawWhiteKeyDivider(index: Int, viewport: KeyboardViewport): Boolean =
    index > 0 && index - viewport.startWhiteKey >= 1f - .0001f &&
        viewport.startWhiteKey + viewport.visibleWhiteKeys - index >= 1f - .0001f
