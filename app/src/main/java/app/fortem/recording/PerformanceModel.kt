// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.TuningModel

/** Полное звуковое состояние, необходимое для точного повторения исполнения. */
data class PerformanceConfiguration(
    val tuningSystem: TuningSystem,
    val tonicPitchClass: Int,
    val referenceFrequencyHz: Double,
    val timbre: AudioTimbre,
    val scalaScale: ScalaScale? = null,
    // Для записей формата 1 написание отсутствует.
    val tonicSpelling: TonicSpelling? = null,
    // Новая живая игра задаёт TONIC_RELATIVE; кодек явно восстанавливает старые правила.
    val tuningModel: TuningModel = TuningModel.LEGACY_EQUAL_TONIC,
)

sealed interface PerformanceEvent {
    val timeNanos: Long
}

/** Сила нажатия сохраняется уже сейчас, хотя экран пока использует постоянное значение. */
data class NotePerformanceEvent(
    override val timeNanos: Long,
    val midiNote: Int,
    val isNoteOn: Boolean,
    val velocity: Float = DEFAULT_TOUCH_VELOCITY,
) : PerformanceEvent

data class ConfigurationPerformanceEvent(
    override val timeNanos: Long,
    val configuration: PerformanceConfiguration,
) : PerformanceEvent

/** Снимок закреплённых нот нужен для точного воспроизведения значков замков. */
data class LatchPerformanceEvent(
    override val timeNanos: Long,
    val latchedNotes: Set<Int>,
) : PerformanceEvent

/** Положение кнопки педали: звук уже описан событиями NOTE, здесь состояние интерфейса. */
data class PedalPerformanceEvent(
    override val timeNanos: Long,
    val enabled: Boolean,
) : PerformanceEvent

data class PerformanceRecording(
    val createdAtEpochMillis: Long,
    val durationNanos: Long,
    val initialConfiguration: PerformanceConfiguration,
    val events: List<PerformanceEvent>,
    val completed: Boolean,
) {
    /** Закрывает ноты незавершённого черновика после сбоя или уничтожения процесса. */
    fun recovered(): PerformanceRecording {
        if (completed) return this

        val activeNotes = linkedSetOf<Int>()
        events.sortedBy(PerformanceEvent::timeNanos).forEach { event ->
            if (event is NotePerformanceEvent) {
                if (event.isNoteOn) activeNotes += event.midiNote else activeNotes -= event.midiNote
            }
        }
        val recoveredEvents = buildList {
            addAll(events)
            activeNotes.sorted().forEach { midiNote ->
                add(
                    NotePerformanceEvent(
                        timeNanos = durationNanos,
                        midiNote = midiNote,
                        isNoteOn = false,
                    ),
                )
            }
        }
        return copy(events = recoveredEvents, completed = true)
    }

    fun playedMidiNotes(): Set<Int> = events
        .asSequence()
        .filterIsInstance<NotePerformanceEvent>()
        .filter { it.isNoteOn }
        .map { it.midiNote }
        .toSet()
}

const val DEFAULT_TOUCH_VELOCITY = 0.8f

fun interface MonotonicClock {
    fun nowNanos(): Long
}

/** Черновик без начатой ноты не считается музыкальным исполнением. */
fun PerformanceRecording.hasPlayedNotes(): Boolean =
    events.any { it is NotePerformanceEvent && it.isNoteOn }
