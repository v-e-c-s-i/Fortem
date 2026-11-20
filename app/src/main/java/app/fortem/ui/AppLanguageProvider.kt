// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.fortem.R
import app.fortem.audio.AudioTimbre
import app.fortem.settings.AppLanguage
import app.fortem.tuning.TuningSystem
import java.util.Locale

/** Сообщение хранит ключ и аргументы, поэтому переводится и после смены языка. */
internal data class UiMessage(@param:StringRes val resourceId: Int, val arguments: List<Any> = emptyList())

internal class AppStrings(private val resources: Resources) {
    fun recordingName(id: Long): String = app.fortem.recording.recordingName(id)

    fun text(@StringRes id: Int, vararg arguments: Any): String =
        resources.getString(id, *arguments)

    fun text(message: UiMessage): String = text(message.resourceId, *message.arguments.toTypedArray())

    fun remainingSeconds(seconds: Int): String =
        resources.getQuantityString(R.plurals.remaining_seconds, seconds, seconds)

    fun tuningTitle(tuning: TuningSystem): String = text(when (tuning) {
        TuningSystem.SCALA -> R.string.tuning_scala
        TuningSystem.EQUAL_TEMPERAMENT -> R.string.tuning_equal
        TuningSystem.PYTHAGOREAN -> R.string.tuning_pythagorean
        TuningSystem.JUST_INTONATION -> R.string.tuning_just
        TuningSystem.QUARTER_COMMA_MEANTONE -> R.string.tuning_meantone
        TuningSystem.WERCKMEISTER_III -> R.string.tuning_werckmeister
        TuningSystem.KIRNBERGER_III -> R.string.tuning_kirnberger
    })

    fun tuningShortTitle(tuning: TuningSystem): String = text(when (tuning) {
        TuningSystem.SCALA -> R.string.tuning_scala
        TuningSystem.EQUAL_TEMPERAMENT -> R.string.tuning_equal_short
        TuningSystem.PYTHAGOREAN -> R.string.tuning_pythagorean
        TuningSystem.JUST_INTONATION -> R.string.tuning_just
        TuningSystem.QUARTER_COMMA_MEANTONE -> R.string.tuning_meantone_short
        TuningSystem.WERCKMEISTER_III -> R.string.tuning_werckmeister_short
        TuningSystem.KIRNBERGER_III -> R.string.tuning_kirnberger_short
    })

    fun tuningDescription(tuning: TuningSystem): String = text(when (tuning) {
        TuningSystem.SCALA -> R.string.tuning_scala_description
        TuningSystem.EQUAL_TEMPERAMENT -> R.string.tuning_equal_description
        TuningSystem.PYTHAGOREAN -> R.string.tuning_pythagorean_description
        TuningSystem.JUST_INTONATION -> R.string.tuning_just_description
        TuningSystem.QUARTER_COMMA_MEANTONE -> R.string.tuning_meantone_description
        TuningSystem.WERCKMEISTER_III -> R.string.tuning_werckmeister_description
        TuningSystem.KIRNBERGER_III -> R.string.tuning_kirnberger_description
    })

    fun timbreTitle(timbre: AudioTimbre): String = text(when (timbre) {
        AudioTimbre.PURE_TONE -> R.string.timbre_pure_tone
        AudioTimbre.PIANO -> R.string.timbre_piano
    })
}

internal data class LanguagePreference(val language: AppLanguage, val onChanged: (AppLanguage) -> Unit)
internal val LocalAppStrings = staticCompositionLocalOf<AppStrings> { error("Missing AppLanguageProvider") }
internal val LocalLanguagePreference = staticCompositionLocalOf<LanguagePreference> { error("Missing AppLanguageProvider") }

/** Меняется только источник текстов: Activity, аудиодвижок и состояния Compose сохраняются. */
@Composable
internal fun AppLanguageProvider(language: AppLanguage, onChanged: (AppLanguage) -> Unit,
    content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resolved = language
    val strings = remember(context, configuration, resolved) {
        val localized = Configuration(configuration).apply { setLocale(Locale.forLanguageTag(resolved.code)) }
        AppStrings(context.createConfigurationContext(localized).resources)
    }
    CompositionLocalProvider(LocalAppStrings provides strings, LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalLanguagePreference provides LanguagePreference(language, onChanged), content = content)
}
