// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

/** Незавершённый ввод не попадает в сохранённую настройку. Запятая тоже допустима. */
internal fun parseReferenceFrequency(text: String): Double? = text.trim().replace(',', '.')
    .toDoubleOrNull()?.takeIf { it.isFinite() && it in 300.0..500.0 }
