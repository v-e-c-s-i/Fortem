// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.settings

/** Stable preference codes; the device language never overrides the selected app language. */
enum class AppLanguage(val code: String) {
    RUSSIAN("ru"), ENGLISH("en");

    companion object {
        // Preserve an explicit English choice; fresh and legacy system preferences use Russian.
        fun fromStoredValue(value: String?): AppLanguage =
            entries.firstOrNull { it.code == value } ?: RUSSIAN
    }
}
