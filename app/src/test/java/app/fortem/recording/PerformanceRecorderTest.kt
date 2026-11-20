// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceRecorderTest {
    private val configuration = PerformanceConfiguration(
        tuningSystem = TuningSystem.EQUAL_TEMPERAMENT,
        tonicPitchClass = 0,
        referenceFrequencyHz = 440.0,
        timbre = AudioTimbre.PIANO,
    )

    @Test
    fun simultaneousChordUsesOneTimestampAndIsClosedOnStop() {
        val storage = MemoryStorage()
        val clock = MutableClock()
        val recorder = PerformanceRecorder(storage, clock, wallClockMillis = { 42L })

        recorder.start(configuration)
        clock.now = 100L
        recorder.recordActiveNotes(setOf(60, 64, 67))
        clock.now = 300L
        val finalState = recorder.stop()

        assertFalse(finalState.isRecording)
        val notes = finalState.lastRecording!!.events.filterIsInstance<NotePerformanceEvent>()
        assertEquals(listOf(100L, 100L, 100L), notes.take(3).map { it.timeNanos })
        assertEquals(setOf(60, 64, 67), notes.take(3).map { it.midiNote }.toSet())
        assertTrue(notes.take(3).all { it.isNoteOn })
        assertTrue(notes.takeLast(3).all { !it.isNoteOn && it.timeNanos == 300L })
    }

    @Test
    fun unfinishedDraftIsRecoveredWithNoteOff() {
        val unfinished = PerformanceRecording(
            createdAtEpochMillis = 1L,
            durationNanos = 500L,
            initialConfiguration = configuration,
            events = listOf(NotePerformanceEvent(10L, 60, true)),
            completed = false,
        )
        val storage = MemoryStorage(unfinished)

        val recovered = PerformanceRecorder(storage, MutableClock()).state().lastRecording!!

        assertTrue(recovered.completed)
        assertEquals(NotePerformanceEvent(500L, 60, false), recovered.events.last())
    }

    @Test
    fun latchChangesAreStoredSeparatelyFromSoundingNotes() {
        val storage = MemoryStorage()
        val clock = MutableClock()
        val recorder = PerformanceRecorder(storage, clock)

        recorder.start(configuration)
        clock.now = 100L
        recorder.recordActiveNotes(setOf(60))
        clock.now = 150L
        recorder.recordLatchedNotes(setOf(60))
        clock.now = 300L
        recorder.stop()

        val latchEvents = recorder.state().lastRecording!!.events
            .filterIsInstance<LatchPerformanceEvent>()
        assertEquals(LatchPerformanceEvent(150L, setOf(60)), latchEvents.first())
        assertEquals(LatchPerformanceEvent(300L, emptySet()), latchEvents.last())
    }

    @Test
    fun repeatedPedalNoteKeepsItsAttackInRecording() {
        val clock = MutableClock()
        val recorder = PerformanceRecorder(MemoryStorage(), clock)
        recorder.start(configuration)
        clock.now = 100L
        recorder.recordActiveNotes(setOf(60, 64))
        clock.now = 200L
        recorder.recordRetriggeredNote(60)
        recorder.recordActiveNotes(setOf(60, 64))
        clock.now = 300L
        recorder.stop()
        val notes = recorder.state().lastRecording!!.events.filterIsInstance<NotePerformanceEvent>()
        assertEquals(listOf(NotePerformanceEvent(200L, 60, false),
            NotePerformanceEvent(200L, 60, true)), notes.filter { it.timeNanos == 200L })
        assertEquals(2, notes.count { it.midiNote == 60 && it.isNoteOn })
        assertEquals(1, notes.count { it.midiNote == 64 && it.isNoteOn })
    }

    @Test
    fun aNewNoteIsRecordedOnceAndPedalOverlapIsPreserved() {
        val clock = MutableClock()
        val recorder = PerformanceRecorder(MemoryStorage(), clock)
        recorder.start(configuration)
        clock.now = 100L
        recorder.recordRetriggeredNote(60) // Первый удар ещё не является повторным.
        recorder.recordActiveNotes(setOf(60))
        clock.now = 200_000_000L
        recorder.recordRetriggeredNote(62)
        recorder.recordActiveNotes(setOf(60, 62))
        clock.now = 350_000_000L
        recorder.recordActiveNotes(setOf(62))
        clock.now = 500_000_000L
        recorder.stop()
        val notes = recorder.state().lastRecording!!.events.filterIsInstance<NotePerformanceEvent>()
        assertEquals(1, notes.count { it.midiNote == 62 && it.isNoteOn })
        assertEquals(200_000_000L, notes.single { it.midiNote == 62 && it.isNoteOn }.timeNanos)
        assertEquals(350_000_000L, notes.single { it.midiNote == 60 && !it.isNoteOn }.timeNanos)
    }

    @Test
    fun pedalInitialStateAndChangesSurviveSavingAndRestart() {
        val clock = MutableClock()
        val storage = MemoryStorage()
        val recorder = PerformanceRecorder(storage, clock)
        recorder.start(configuration, initiallyPedalEnabled = false)
        recorder.recordActiveNotes(setOf(60))
        clock.now = 100L
        recorder.recordPedalEnabled(true)
        recorder.recordPedalEnabled(true) // Повтор той же команды не добавляет событий.
        clock.now = 200L
        recorder.recordPedalEnabled(false)
        clock.now = 300L
        recorder.stop()
        val loaded = PerformanceRecorder(storage, MutableClock()).state().lastRecording!!
        assertEquals(listOf(PedalPerformanceEvent(0L, false),
            PedalPerformanceEvent(100L, true), PedalPerformanceEvent(200L, false)),
            loaded.events.filterIsInstance<PedalPerformanceEvent>())
        assertEquals(loaded, PerformanceCodec.decode(PerformanceCodec.encode(loaded)))
    }

    @Test
    fun initiallyEnabledPedalIsPreservedEvenWithoutAToggle() {
        val recorder = PerformanceRecorder(MemoryStorage(), MutableClock())
        recorder.start(configuration, initiallyPedalEnabled = true)
        recorder.recordActiveNotes(setOf(60))
        recorder.stop()
        assertEquals(listOf(PedalPerformanceEvent(0L, true)),
            recorder.state().lastRecording!!.events.filterIsInstance<PedalPerformanceEvent>())
        recorder.start(configuration, initiallyPedalEnabled = false)
        recorder.recordActiveNotes(setOf(62))
        recorder.stop()
        assertEquals(listOf(PedalPerformanceEvent(0L, false)),
            recorder.state().lastRecording!!.events.filterIsInstance<PedalPerformanceEvent>())
    }

    private class MutableClock(var now: Long = 0L) : MonotonicClock {
        override fun nowNanos(): Long = now
    }

    private class MemoryStorage(initial: PerformanceRecording? = null) : PerformanceStorage {
        private var recording = initial
        override fun loadLast(): PerformanceRecording? = recording
        override fun saveLast(recording: PerformanceRecording) {
            this.recording = recording
        }
    }
}
