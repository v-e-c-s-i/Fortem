// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.settings

import android.content.Context
import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningEngine
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.ScalaScaleCodec
import app.fortem.tuning.ScalaLibrary
import app.fortem.ui.KeyboardViewport

/** Небольшое постоянное хранилище настроек, выбранных на игровом экране. */
class UserSettings(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // Only the performance controls scale; piano zoom and gesture distances stay independent.
    fun loadInterfaceScale(): Float = preferences.getFloat("interface_scale", 1f)
        .takeIf { it.isFinite() && it in .8f..1.2f } ?: 1f

    fun saveInterfaceScale(value: Float) {
        if (value.isFinite()) preferences.edit().putFloat("interface_scale", value.coerceIn(.8f, 1.2f)).apply()
    }

    // Keep existing players' gesture preference; the chord pedal is independent.
    fun loadLatchGesturesEnabled(): Boolean = preferences.getBoolean("latch_gestures_enabled", false)
    fun saveLatchGesturesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("latch_gestures_enabled", enabled).apply()
    }

    fun loadAppLanguage(): AppLanguage = AppLanguage.fromStoredValue(
        preferences.getString("app_language", null),
    )

    fun saveAppLanguage(language: AppLanguage) {
        preferences.edit().putString("app_language", language.code).apply()
    }

    fun loadTuningSystem(): TuningSystem {
        val storedName = preferences.getString(KEY_TUNING_SYSTEM, null)
        return TuningSystem.entries.firstOrNull { it.name == storedName && (it != TuningSystem.SCALA || loadScalaScale() != null) }
            ?: TuningSystem.EQUAL_TEMPERAMENT
    }

    private fun loadLegacyScalaScale(): ScalaScale? = runCatching {
        preferences.getString("scala_scale", null)?.let(ScalaScaleCodec::decode)
    }.getOrNull()

    fun loadScalaScales(): List<ScalaScale> = if (preferences.contains("scala_library")) {
        runCatching { ScalaLibrary.decode(preferences.getString("scala_library", "").orEmpty()) }
            .getOrDefault(emptyList())
    } else listOfNotNull(loadLegacyScalaScale()) // Миграция прежнего формата с единственным пользовательским строем.

    fun loadScalaScale(): ScalaScale? {
        val scales = loadScalaScales()
        return loadLegacyScalaScale()?.takeIf { candidate -> scales.any { it.libraryId == candidate.libraryId } }
            ?: scales.firstOrNull()
    }

    fun loadVisibleScalaIds(): Set<String> {
        val ids = loadScalaScales().mapTo(linkedSetOf()) { it.libraryId }
        val stored = preferences.getStringSet("visible_scala_ids", null)
        return if (stored != null) stored.intersect(ids) else {
            val legacyVisible = preferences.getStringSet(KEY_VISIBLE_TUNINGS, emptySet()).orEmpty()
            if (TuningSystem.SCALA.name in legacyVisible) ids else emptySet()
        }
    }

    fun saveScalaLibrary(scales: List<ScalaScale>, visibleIds: Set<String>, selected: ScalaScale?) {
        preferences.edit()
            .putString("scala_library", ScalaLibrary.encode(scales))
            .putStringSet("visible_scala_ids", visibleIds.toSet())
            .putString("scala_scale", selected?.takeIf { scale -> scales.any { it.libraryId == scale.libraryId } }
                ?.let(ScalaScaleCodec::encode))
            .apply()
    }

    fun loadTonicPitchClass(): Int =
        preferences.getInt(KEY_TONIC_PITCH_CLASS, 0).coerceIn(0, 11)

    fun loadTonicSpelling(): TonicSpelling = runCatching {
        TonicSpelling.valueOf(preferences.getString("tonic_spelling", "SHARP") ?: "SHARP")
    }.getOrDefault(TonicSpelling.SHARP)

    fun saveTonicSpelling(spelling: TonicSpelling) {
        preferences.edit().putString("tonic_spelling", spelling.name).apply()
    }

    fun loadVisibleTuningSystems(): Set<TuningSystem> {
        val storedNames = preferences.getStringSet(KEY_VISIBLE_TUNINGS, null)?.toSet()
        val parsed = TuningSystem.entries.filterTo(linkedSetOf()) { it.name in storedNames.orEmpty() && (it != TuningSystem.SCALA || loadVisibleScalaIds().isNotEmpty()) }
        return parsed.ifEmpty { DEFAULT_VISIBLE_TUNINGS }
    }

    fun loadReferenceFrequencyHz(): Double {
        val stored = preferences.getFloat(
            KEY_REFERENCE_FREQUENCY_HZ,
            TuningEngine.DEFAULT_REFERENCE_FREQUENCY_HZ.toFloat(),
        ).toDouble()
        return stored.takeIf { it.isFinite() && it in MIN_REFERENCE_HZ..MAX_REFERENCE_HZ }
            ?: TuningEngine.DEFAULT_REFERENCE_FREQUENCY_HZ
    }

    fun loadKeyboardViewport(): KeyboardViewport = KeyboardViewport(
        startWhiteKey = preferences.getFloat(
            KEY_KEYBOARD_START_WHITE_KEY,
            KeyboardViewport.DEFAULT.startWhiteKey,
        ),
        visibleWhiteKeys = preferences.getFloat(
            KEY_VISIBLE_WHITE_KEYS,
            KeyboardViewport.DEFAULT.visibleWhiteKeys,
        ),
    ).clamped()

    fun loadAudioTimbre(): AudioTimbre {
        val storedName = preferences.getString(KEY_AUDIO_TIMBRE, null)
        return AudioTimbre.entries.firstOrNull { it.name == storedName }
            ?: AudioTimbre.PIANO
    }

    fun loadRetriggerHeldNotesDuringPlayback(): Boolean =
        preferences.getBoolean(KEY_RETRIGGER_HELD_NOTES_DURING_PLAYBACK, false)

    fun saveTuningSystem(tuningSystem: TuningSystem) {
        preferences.edit().putString(KEY_TUNING_SYSTEM, tuningSystem.name).apply()
    }

    fun saveTonicPitchClass(tonicPitchClass: Int) {
        preferences.edit().putInt(KEY_TONIC_PITCH_CLASS, tonicPitchClass).apply()
    }

    fun saveVisibleTuningSystems(tuningSystems: Set<TuningSystem>) {
        if (tuningSystems.isEmpty()) return
        preferences.edit()
            .putStringSet(KEY_VISIBLE_TUNINGS, tuningSystems.mapTo(mutableSetOf()) { it.name })
            .apply()
    }

    fun saveReferenceFrequencyHz(referenceFrequencyHz: Double) {
        if (referenceFrequencyHz !in MIN_REFERENCE_HZ..MAX_REFERENCE_HZ) return
        preferences.edit()
            .putFloat(KEY_REFERENCE_FREQUENCY_HZ, referenceFrequencyHz.toFloat())
            .apply()
    }

    fun saveKeyboardViewport(viewport: KeyboardViewport) {
        val safeViewport = viewport.clamped()
        preferences.edit()
            .putFloat(KEY_KEYBOARD_START_WHITE_KEY, safeViewport.startWhiteKey)
            .putFloat(KEY_VISIBLE_WHITE_KEYS, safeViewport.visibleWhiteKeys)
            .apply()
    }

    fun saveAudioTimbre(timbre: AudioTimbre) {
        preferences.edit().putString(KEY_AUDIO_TIMBRE, timbre.name).apply()
    }

    fun saveRetriggerHeldNotesDuringPlayback(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_RETRIGGER_HELD_NOTES_DURING_PLAYBACK, enabled)
            .apply()
    }

    // По умолчанию старый способ игры остаётся привычным и не удерживает ноты.
    fun loadHoldUntilNextChord(): Boolean = preferences.getBoolean(KEY_HOLD_UNTIL_NEXT_CHORD, false)
    fun saveHoldUntilNextChord(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_HOLD_UNTIL_NEXT_CHORD, enabled).apply()
    }

    // По умолчанию MIDI слушают через принимающий инструмент компьютера.
    fun loadMidiLocalSound(): Boolean = preferences.getBoolean("midi_local_sound", false)
    fun saveMidiLocalSound(enabled: Boolean) {
        preferences.edit().putBoolean("midi_local_sound", enabled).apply()
    }

    fun loadRecordingsNewestFirst(): Boolean = preferences.getBoolean("recordings_newest_first", true)
    fun saveRecordingsNewestFirst(value: Boolean) {
        preferences.edit().putBoolean("recordings_newest_first", value).apply()
    }

    fun loadWavExportDirectory(): String? = preferences.getString("wav_export_directory", null)
    fun saveWavExportDirectory(uri: String?) {
        preferences.edit().putString("wav_export_directory", uri).apply()
    }

    companion object {
        const val FILE_NAME = "fortem_user_settings"
        const val KEY_TUNING_SYSTEM = "tuning_system"
        const val KEY_TONIC_PITCH_CLASS = "tonic_pitch_class"
        const val KEY_VISIBLE_TUNINGS = "visible_tunings"
        const val KEY_REFERENCE_FREQUENCY_HZ = "reference_frequency_hz"
        const val KEY_KEYBOARD_START_WHITE_KEY = "keyboard_start_white_key"
        const val KEY_VISIBLE_WHITE_KEYS = "visible_white_keys"
        const val KEY_AUDIO_TIMBRE = "audio_timbre"
        const val KEY_HOLD_UNTIL_NEXT_CHORD = "hold_until_next_chord"
        // Строковое значение сохраняется ради совместимости с настройкой 0.9.4.
        const val KEY_RETRIGGER_HELD_NOTES_DURING_PLAYBACK =
            "retrigger_held_notes_on_pitch_change"
        val DEFAULT_VISIBLE_TUNINGS = linkedSetOf(
            TuningSystem.EQUAL_TEMPERAMENT,
            TuningSystem.PYTHAGOREAN,
            TuningSystem.JUST_INTONATION,
        )

        const val MIN_REFERENCE_HZ = 300.0
        const val MAX_REFERENCE_HZ = 500.0
    }
}
