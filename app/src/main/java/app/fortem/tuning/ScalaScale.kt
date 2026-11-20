// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.tuning

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Machine-readable validation failures; user-facing text belongs to UI resources. */
enum class ScalaProblem {
    INVALID_TITLE, INVALID_INTERVALS, INVALID_ORDER, FILE_TOO_LARGE, MISSING_HEADER,
    UNSUPPORTED_STEPS, MISSING_INTERVALS, INVALID_RATIO, INVALID_INTERVAL,
    NONFINITE_INTERVAL, INVALID_OCTAVE, POSITIVE_RATIO, LIBRARY_FULL,
}

class ScalaImportException(val problem: ScalaProblem, val detail: String? = null) :
    IllegalArgumentException(problem.name)

/** Preserve the original constraints while exposing stable error codes. */
private fun requireScala(condition: Boolean, problem: ScalaProblem, detail: String? = null) {
    if (!condition) throw ScalaImportException(problem, detail)
}

/** Один импортированный 12-ступенный октавный строй, независимый от исходного файла. */
data class ScalaScale(val title: String, val cents: List<Double>) {
    init {
        requireScala(title.isNotBlank() && title.length <= 240 && '\n' !in title && '\r' !in title, ScalaProblem.INVALID_TITLE)
        requireScala(cents.size == 12 && cents.all { it.isFinite() }, ScalaProblem.INVALID_INTERVALS)
        requireScala(cents.first() == 0.0 && cents.zipWithNext().all { (a, b) -> b > a } &&
            cents.last() < 1200.0, ScalaProblem.INVALID_ORDER)
    }

    // Вычисляется один раз: перерисовка нот и длинный список не повторяют SHA-256.
    val libraryId: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(ScalaScaleCodec.encode(this).toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    internal val ratios: DoubleArray = DoubleArray(12) { 2.0.pow(cents[it] / 1200.0) }
}

/** .scl: 1/1 не записывается; целое 2 — отношение 2/1, а 2.0 — два цента. */
object ScalaScaleCodec {
    const val MAX_FILE_BYTES = 65_536

    fun parse(text: String, fallbackTitle: String = "Scala"): ScalaScale {
        requireScala(text.length <= MAX_FILE_BYTES, ScalaProblem.FILE_TOO_LARGE)
        val lines = text.removePrefix("\uFEFF").lineSequence()
            .filterNot { it.trimStart().startsWith('!') }.toList()
        requireScala(lines.size >= 2, ScalaProblem.MISSING_HEADER)
        val countLine = lines[1].trim().split(Regex("\\s+"))[0]
        requireScala(countLine.toIntOrNull() == 12, ScalaProblem.UNSUPPORTED_STEPS)
        val pitches = lines.drop(2).filter(String::isNotBlank)
        requireScala(pitches.size >= 12, ScalaProblem.MISSING_INTERVALS)
        val values = pitches.take(12).map { line ->
            val token = line.trim().split(Regex("\\s+"))[0]
            val cents = if ('/' in token) {
                val parts = token.split('/')
                requireScala(parts.size == 2, ScalaProblem.INVALID_RATIO, token)
                ratioCents(parts[0], parts[1])
            } else if ('.' in token) {
                token.toDoubleOrNull() ?: throw ScalaImportException(ScalaProblem.INVALID_INTERVAL, token)
            } else {
                ratioCents(token, "1")
            }
            requireScala(cents.isFinite(), ScalaProblem.NONFINITE_INTERVAL)
            cents
        }
        requireScala(abs(values.last() - 1200.0) < 0.001, ScalaProblem.INVALID_OCTAVE)
        val title = lines.first().trim().ifBlank { fallbackTitle.trim().ifBlank { "Scala" } }.take(240)
        return ScalaScale(title, listOf(0.0) + values.dropLast(1))
    }

    private fun ratioCents(numerator: String, denominator: String): Double {
        val a = numerator.toLongOrNull()?.takeIf { it > 0L }
            ?: throw ScalaImportException(ScalaProblem.POSITIVE_RATIO)
        val b = denominator.toLongOrNull()?.takeIf { it > 0L }
            ?: throw ScalaImportException(ScalaProblem.POSITIVE_RATIO)
        return 1200.0 * ln(a.toDouble() / b.toDouble()) / ln(2.0)
    }

    // Снимок встроен в запись: удаление/замена .scl не меняет звучание старого исполнения.
    fun encode(scale: ScalaScale): String = Base64.getEncoder().encodeToString(
        (scale.title + "\n" + scale.cents.joinToString(",")).toByteArray(Charsets.UTF_8),
    )

    fun decode(value: String): ScalaScale {
        require(value.length <= MAX_FILE_BYTES)
        val fields = String(Base64.getDecoder().decode(value), Charsets.UTF_8).split('\n', limit = 2)
        require(fields.size == 2)
        return ScalaScale(fields[0], fields[1].split(',').map(String::toDouble))
    }
}
