// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

/** Небольшая библиотека пользовательских строев; записи хранят собственные снимки. */
object ScalaLibrary {
    const val MAX_SCALES = 64

    fun add(scales: List<ScalaScale>, scale: ScalaScale): List<ScalaScale> {
        if (scales.any { it.libraryId == scale.libraryId }) return scales
        if (scales.size >= MAX_SCALES) throw ScalaImportException(ScalaProblem.LIBRARY_FULL)
        return scales + scale
    }

    fun encode(scales: List<ScalaScale>): String = scales.joinToString("\n", transform = ScalaScaleCodec::encode)

    fun decode(text: String): List<ScalaScale> {
        require(text.length <= 131_072)
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        require(lines.size <= MAX_SCALES)
        return lines.map(ScalaScaleCodec::decode).distinctBy { it.libraryId }
    }
}
