// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun freshAndLegacySystemPreferencesUseRussian() {
        listOf(null, "system", "unknown").forEach {
            assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromStoredValue(it))
        }
    }
    @Test fun explicitChoicesSurviveUpdates() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromStoredValue("en"))
        assertEquals(AppLanguage.RUSSIAN, AppLanguage.fromStoredValue("ru"))
        assertEquals(2, AppLanguage.entries.size)
    }
}
