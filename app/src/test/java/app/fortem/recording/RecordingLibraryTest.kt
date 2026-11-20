// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RecordingLibraryTest {
    private val config = PerformanceConfiguration(TuningSystem.EQUAL_TEMPERAMENT, 0, 440.0, AudioTimbre.PIANO)
    private class Clock : MonotonicClock {
        var now = 0L
        override fun nowNanos() = now
    }
    private class Memory : PerformanceStorage {
        val takes = linkedMapOf<Long, PerformanceRecording>()
        val trash = linkedMapOf<Long, PerformanceRecording>()
        var fail = false
        var checkpointEntered: CountDownLatch? = null
        var releaseCheckpoint: CountDownLatch? = null
        override fun loadLast() = takes.values.maxByOrNull { it.createdAtEpochMillis }
        override fun saveLast(recording: PerformanceRecording) {
            if (fail) error("Disk full")
            if (!recording.completed && recording.durationNanos > 0L) {
                checkpointEntered?.countDown()
                releaseCheckpoint?.await(5, TimeUnit.SECONDS)
            }
            takes[recording.createdAtEpochMillis] = recording
        }
        override fun listRecordings() = takes.values.map { it.summary() }
        override fun load(id: Long) = takes[id]
        override fun delete(id: Long) { takes.remove(id) }
        override fun moveToTrash(id: Long) { takes.remove(id)?.let { trash[id] = it } }
        override fun loadTrash(id: Long) = trash[id]
        override fun listTrash() = trash.values.map { TrashRecordingSummary(it.createdAtEpochMillis, it.durationNanos, 1000L) }
        override fun restoreFromTrash(id: Long) { trash.remove(id)?.let { takes[id] = it } }
    }
    @Test fun everyStopKeepsATakeEvenWithinTheSameMillisecond() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        repeat(3) { recorder.start(config); recorder.recordActiveNotes(setOf(60)); clock.now++; recorder.stop() }
        assertEquals(3, storage.takes.size)
        assertEquals(listOf(100L, 101L, 102L), storage.takes.keys.toList())
        val reloaded = PerformanceRecorder(storage, clock)
        assertEquals(102L, reloaded.state().lastRecording!!.createdAtEpochMillis)
        assertEquals(100L, reloaded.select(100L).lastRecording!!.createdAtEpochMillis)
    }
    @Test fun restartingDiscardsMistakeAndResetsTimeWithoutCreatingAnotherTake() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config)
        clock.now = 100L
        recorder.recordActiveNotes(setOf(60))
        recorder.checkpoint()
        clock.now = 200L
        val restarted = recorder.restart(config, initiallyPedalEnabled = true)
        assertTrue(restarted.isRecording)
        assertEquals(0L, restarted.elapsedNanos)
        assertEquals(listOf(100L), storage.takes.keys.toList())
        assertFalse(storage.load(100L)!!.completed)
        assertTrue(storage.load(100L)!!.events.none { it is NotePerformanceEvent })
        clock.now = 220L
        recorder.recordActiveNotes(setOf(64))
        clock.now = 250L
        val saved = recorder.stop().lastRecording!!
        assertEquals(50L, saved.durationNanos)
        val notes = saved.events.filterIsInstance<NotePerformanceEvent>()
        assertEquals(listOf(NotePerformanceEvent(20L, 64, true),
            NotePerformanceEvent(50L, 64, false)), notes)
        assertEquals(PedalPerformanceEvent(0L, true), saved.events.first())
        assertEquals(1, storage.takes.size)
    }
    @Test fun restartPreservesHeldNotesAndReplacementTargetUntilSuccessfulStop() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config, replaceId = 100L); recorder.recordActiveNotes(setOf(62))
        clock.now = 100L
        recorder.restart(config, initiallyActiveNotes = setOf(64), initiallyLatchedNotes = setOf(64))
        assertNotNull(storage.load(100L))
        assertTrue(storage.trash.isEmpty())
        val result = recorder.stop().lastRecording!!
        assertNull(storage.load(100L))
        assertNotNull(storage.loadTrash(100L))
        assertEquals(101L, result.createdAtEpochMillis)
        assertEquals(setOf(64), result.events.filterIsInstance<NotePerformanceEvent>().map { it.midiNote }.toSet())
        assertTrue(result.events.contains(LatchPerformanceEvent(0L, setOf(64))))
    }
    @Test fun inFlightCheckpointCannotRestoreDiscardedNotesAfterRestart() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); clock.now = 100L
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        storage.checkpointEntered = entered; storage.releaseCheckpoint = release
        val checkpoint = thread { recorder.checkpoint() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val restarted = thread { recorder.restart(config) }
        release.countDown(); checkpoint.join(5000); restarted.join(5000)
        assertFalse(checkpoint.isAlive); assertFalse(restarted.isAlive)
        assertFalse(storage.load(100L)!!.completed)
        assertEquals(0L, storage.load(100L)!!.durationNanos)
        assertTrue(storage.load(100L)!!.events.none { it is NotePerformanceEvent })
    }
    @Test fun replacementDeletesOriginalOnlyAfterSuccessfulNonemptyStop() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config, replaceId = 100L)
        assertNotNull(storage.load(100L))
        recorder.stop() // Empty attempt preserves old take.
        assertNotNull(storage.load(100L))
        recorder.start(config, replaceId = 100L); recorder.recordActiveNotes(setOf(64))
        recorder.stop()
        assertNull(storage.load(100L))
        assertNotNull(storage.load(102L))
    }
    @Test fun emptyTakeDoesNotDisplaceLastPlayableRecording() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); clock.now++; recorder.stop()
        recorder.start(config); recorder.checkpoint(); recorder.stop()
        assertEquals(listOf(100L), storage.takes.keys.toList())
        assertEquals(100L, recorder.state().lastRecording?.createdAtEpochMillis)
        assertEquals(100L, PerformanceRecorder(storage, clock).state().lastRecording?.createdAtEpochMillis)
    }
    @Test fun backgroundStopDoesNotReplaceOriginal() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config, replaceId = 100L); recorder.recordActiveNotes(setOf(64))
        recorder.stop(completeReplacement = false)
        assertEquals(2, storage.takes.size)
    }
    @Test fun deletedTakeSurvivesRestartAndCanBeRestored() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config); recorder.recordActiveNotes(setOf(64)); recorder.stop()
        assertEquals(100L, recorder.delete(101L).lastRecording?.createdAtEpochMillis)
        assertNull(storage.load(101L))
        val restarted = PerformanceRecorder(storage, clock)
        assertEquals(listOf(101L), restarted.listTrash().map { it.id })
        restarted.restoreFromTrash(101L)
        assertEquals(setOf(100L, 101L), storage.takes.keys)
        assertTrue(restarted.listTrash().isEmpty())
    }
    @Test fun trashPreviewLeavesSelectionAndTrashUntouched() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config); recorder.recordActiveNotes(setOf(64)); recorder.stop()
        recorder.delete(100L)
        val selected = recorder.state().lastRecording
        val preview = recorder.loadTrash(100L)
        assertEquals(100L, preview?.createdAtEpochMillis)
        assertSame(selected, recorder.state().lastRecording)
        assertEquals(listOf(100L), recorder.listTrash().map { it.id })
        assertNull(storage.load(100L))
        assertEquals(preview, recorder.loadTrash(100L))
        assertNull(recorder.loadTrash(999L))
    }

    @Test fun completedReplacementKeepsTheOriginalInTrash() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config, replaceId = 100L); recorder.recordActiveNotes(setOf(64)); recorder.stop()
        assertNotNull(storage.trash[100L])
        assertNotNull(storage.takes[101L])
    }
    @Test fun failedSaveRetainsOldTakeAndCanBeRetried() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); recorder.stop()
        recorder.start(config, replaceId = 100L); recorder.recordActiveNotes(setOf(64))
        storage.fail = true
        assertTrue(recorder.stop().saveFailed)
        assertNotNull(storage.load(100L))
        assertFalse(recorder.start(config).isRecording)
        storage.fail = false
        assertFalse(recorder.retrySave().saveFailed)
        assertTrue(storage.load(101L)!!.completed)
    }
    @Test fun stopWinsAgainstConcurrentCheckpoint() {
        val storage = Memory(); val clock = Clock()
        val recorder = PerformanceRecorder(storage, clock) { 100L }
        recorder.start(config); recorder.recordActiveNotes(setOf(60)); clock.now = 100L
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        storage.checkpointEntered = entered; storage.releaseCheckpoint = release
        val checkpoint = thread { recorder.checkpoint() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopped = thread { recorder.stop() }
        release.countDown(); checkpoint.join(5000); stopped.join(5000)
        assertFalse(checkpoint.isAlive); assertFalse(stopped.isAlive)
        assertTrue(storage.loadLast()!!.completed)
        assertFalse(storage.loadLast()!!.events.filterIsInstance<NotePerformanceEvent>().last().isNoteOn)
    }
    @Test fun wavHeaderHasCorrectFormatLengthAndFrameRounding() {
        val output = ByteArrayOutputStream()
        PerformanceWavExporter.writeHeader(output, 48000)
        val bytes = output.toByteArray()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(192036, header.getInt(4)); assertEquals(48000, header.getInt(24))
        assertEquals(2, header.getShort(22).toInt()); assertEquals(16, header.getShort(34).toInt())
        assertEquals(192000, header.getInt(40))
        assertEquals(48000L, PerformanceWavExporter.nanosToFrames(1_000_000_000L))
        assertEquals(0L, PerformanceWavExporter.nanosToFrames(1L))
    }
}
