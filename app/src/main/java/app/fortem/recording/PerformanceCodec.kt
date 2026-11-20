// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.ScalaScaleCodec
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.TuningModel

/**
 * Простой версионированный текстовый формат Fortem.
 * Он намеренно не является MIDI: точный строй и его изменения хранятся без потерь.
 */
object PerformanceCodec {
    private const val MAGIC = "FORTEM_PERFORMANCE"
    private const val FORMAT_VERSION = 3

    fun encode(recording: PerformanceRecording): String = buildString {
        appendLine("$MAGIC\t$FORMAT_VERSION")
        appendLine("CREATED\t${recording.createdAtEpochMillis}")
        appendLine("DURATION\t${recording.durationNanos}")
        appendLine("COMPLETED\t${if (recording.completed) 1 else 0}")
        appendConfiguration("INITIAL", recording.initialConfiguration)
        recording.events.sortedBy(PerformanceEvent::timeNanos).forEach { event ->
            when (event) {
                is NotePerformanceEvent -> appendLine(
                    listOf(
                        "NOTE",
                        event.timeNanos,
                        if (event.isNoteOn) "ON" else "OFF",
                        event.midiNote,
                        event.velocity,
                    ).joinToString("\t"),
                )

                is ConfigurationPerformanceEvent ->
                    appendConfiguration("CONFIG\t${event.timeNanos}", event.configuration)

                is PedalPerformanceEvent -> appendLine(
                    "PEDAL\t${event.timeNanos}\t${if (event.enabled) 1 else 0}",
                )

                is LatchPerformanceEvent -> appendLine(
                    listOf(
                        "LATCH",
                        event.timeNanos,
                        event.latchedNotes.sorted().joinToString(","),
                    ).joinToString("\t"),
                )
            }
        }
    }

    fun decode(text: String): PerformanceRecording? = runCatching {
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        // Версии 1 и 2 восстанавливают собственную модель частот. Старое приложение
        // отвергнет версию 3, а не проиграет новое исполнение с неверной опорой.
        val formatVersion = lines.firstOrNull()?.substringAfter("$MAGIC\t", "")?.toIntOrNull()
        require(lines.firstOrNull()?.startsWith("$MAGIC\t") == true &&
            formatVersion != null && formatVersion in 1..FORMAT_VERSION)

        var createdAt = 0L
        var duration = 0L
        var completed = false
        var initial: PerformanceConfiguration? = null
        val events = mutableListOf<PerformanceEvent>()

        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            when (fields.firstOrNull()) {
                "CREATED" -> createdAt = fields[1].toLong()
                "DURATION" -> duration = fields[1].toLong().coerceAtLeast(0L)
                "COMPLETED" -> completed = fields[1] == "1"
                "INITIAL" -> initial = parseConfiguration(fields, 1, formatVersion)
                "CONFIG" -> events += ConfigurationPerformanceEvent(
                    timeNanos = fields[1].toLong().coerceAtLeast(0L),
                    configuration = parseConfiguration(fields, 2, formatVersion),
                )

                "NOTE" -> events += NotePerformanceEvent(
                    timeNanos = fields[1].toLong().coerceAtLeast(0L),
                    isNoteOn = fields[2] == "ON",
                    midiNote = fields[3].toInt().coerceIn(0, 127),
                    velocity = fields[4].toFloat().also { require(it.isFinite()) }.coerceIn(0f, 1f),
                )

                // Новая необязательная строка: старые записи версии 1 остаются читаемыми.
                "PEDAL" -> {
                    require(fields[2] == "0" || fields[2] == "1")
                    events += PedalPerformanceEvent(
                        timeNanos = fields[1].toLong().coerceAtLeast(0L),
                        enabled = fields[2] == "1",
                    )
                }

                "LATCH" -> events += LatchPerformanceEvent(
                    timeNanos = fields[1].toLong().coerceAtLeast(0L),
                    latchedNotes = fields.getOrElse(2) { "" }
                        .split(',')
                        .mapNotNull(String::toIntOrNull)
                        .filterTo(sortedSetOf()) { it in 0..127 },
                )
            }
        }
        PerformanceRecording(
            createdAtEpochMillis = createdAt,
            durationNanos = duration,
            initialConfiguration = requireNotNull(initial),
            events = events.sortedBy(PerformanceEvent::timeNanos),
            completed = completed,
        )
    }.getOrNull()

    private fun StringBuilder.appendConfiguration(
        prefix: String,
        configuration: PerformanceConfiguration,
    ) {
        appendLine(
            listOf(
                prefix,
                configuration.tuningSystem.name,
                configuration.tonicPitchClass,
                configuration.referenceFrequencyHz,
                configuration.timbre.name,
                configuration.scalaScale?.let(ScalaScaleCodec::encode) ?: "-",
                configuration.tonicSpelling?.name ?: "-",
                configuration.tuningModel.name,
            ).joinToString("\t"),
        )
    }

    private fun parseConfiguration(fields: List<String>, start: Int, version: Int): PerformanceConfiguration {
        require(when (version) {
            1 -> fields.size == start + 4 || fields.size == start + 5
            2 -> fields.size == start + 6
            3 -> fields.size == start + 7
            else -> false
        })
        return PerformanceConfiguration(
            tuningSystem = TuningSystem.valueOf(fields[start]),
            tonicPitchClass = fields[start + 1].toInt().coerceIn(0, 11),
            referenceFrequencyHz = fields[start + 2].toDouble().also { require(it.isFinite() && it > 0.0) },
            timbre = AudioTimbre.valueOf(fields[start + 3]),
            scalaScale = fields.getOrNull(start + 4)?.takeIf { it != "-" }?.let(ScalaScaleCodec::decode),
            tonicSpelling = if (version == 1) null else fields[start + 5]
                .takeIf { it != "-" }?.let(TonicSpelling::valueOf),
            tuningModel = when (version) {
                1 -> TuningModel.LEGACY_EQUAL_TONIC
                2 -> TuningModel.LEGACY_C4_TONIC
                else -> TuningModel.valueOf(fields[start + 6])
            },
        ).also { configuration ->
            require(configuration.tuningSystem != TuningSystem.SCALA || configuration.scalaScale != null)
        }
    }
}
