// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import java.util.concurrent.atomic.AtomicLong

/** Снимок состояния для верхней панели; сами события остаются внутри рекордера. */
data class RecorderState(
    val isRecording: Boolean,
    val elapsedNanos: Long,
    val lastRecording: PerformanceRecording?,
    val saveFailed: Boolean = false,
)

class PerformanceRecorder(
    private val storage: PerformanceStorage,
    private val clock: MonotonicClock,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
) {
    private var startedAtNanos = 0L
    private var recordingCreatedAtEpochMillis = 0L
    private var initialConfiguration: PerformanceConfiguration? = null
    private var currentConfiguration: PerformanceConfiguration? = null
    private var events = mutableListOf<PerformanceEvent>()
    private var activeNotes = emptySet<Int>()
    private var latchedNotes = emptySet<Int>()
    private var pedalEnabled = false
    // Запись на диск сериализуется отдельно: медленный fsync не должен держать
    // монитор рекордера и задерживать события новых касаний.
    private val storageWriteLock = Any()
    private val storageWriteRevision = AtomicLong(0L)
    @Volatile private var saveFailed = false
    private var replacementId: Long? = null
    private var lastAllocatedId = 0L
    private var lastRecording: PerformanceRecording? = storage.loadLast()?.let { loaded ->
        val recovered = loaded.recovered()
        if (recovered !== loaded) saveLastSafely(recovered, storageWriteRevision.incrementAndGet())
        recovered
    }

    init { lastAllocatedId = lastRecording?.createdAtEpochMillis ?: 0L }

    @Synchronized
    fun state(): RecorderState = RecorderState(
        isRecording = initialConfiguration != null,
        elapsedNanos = elapsedNanosLocked(),
        lastRecording = lastRecording,
        saveFailed = saveFailed,
    )

    @Synchronized
    fun start(
        configuration: PerformanceConfiguration,
        initiallyActiveNotes: Set<Int> = emptySet(),
        initiallyLatchedNotes: Set<Int> = emptySet(),
        replaceId: Long? = null,
        initiallyPedalEnabled: Boolean = false,
    ): RecorderState {
        if (initialConfiguration != null) return state()
        // Do not discard an unsaved completed take when starting the next one.
        if (saveFailed && lastRecording != null) {
            if (!saveLastSafely(lastRecording!!, storageWriteRevision.incrementAndGet())) return state()
        }
        replacementId = replaceId
        startedAtNanos = clock.nowNanos()
        recordingCreatedAtEpochMillis = maxOf(wallClockMillis(), lastAllocatedId + 1)
        lastAllocatedId = recordingCreatedAtEpochMillis
        initialConfiguration = configuration
        currentConfiguration = configuration
        events = mutableListOf()
        activeNotes = emptySet()
        latchedNotes = emptySet()
        // Начальное состояние сохраняется даже без переключений во время исполнения.
        pedalEnabled = initiallyPedalEnabled
        events += PedalPerformanceEvent(0L, pedalEnabled)
        recordActiveNotesLocked(initiallyActiveNotes, timeNanos = 0L)
        recordLatchedNotesLocked(initiallyLatchedNotes, timeNanos = 0L)
        checkpointLocked(completed = false)
        return state()
    }

    @Synchronized
    fun recordActiveNotes(notes: Set<Int>): RecorderState {
        if (initialConfiguration == null) return state()
        recordActiveNotesLocked(notes, elapsedNanosLocked())
        return state()
    }

    /** Сбрасывает ошибочный дубль без stop(): завершённая запись не создаётся.
     * Тот же файл черновика заменяется новым началом; цель replaceId сохраняется.
     * Номер ревизии не даёт запоздалому checkpoint вернуть старые события. */
    @Synchronized
    fun restart(
        configuration: PerformanceConfiguration,
        initiallyActiveNotes: Set<Int> = emptySet(),
        initiallyLatchedNotes: Set<Int> = emptySet(),
        initiallyPedalEnabled: Boolean = false,
    ): RecorderState {
        if (initialConfiguration == null) return state()
        startedAtNanos = clock.nowNanos()
        initialConfiguration = configuration
        currentConfiguration = configuration
        events = mutableListOf()
        activeNotes = emptySet()
        latchedNotes = emptySet()
        pedalEnabled = initiallyPedalEnabled
        events += PedalPerformanceEvent(0L, pedalEnabled)
        recordActiveNotesLocked(initiallyActiveNotes, 0L)
        recordLatchedNotesLocked(initiallyLatchedNotes, 0L)
        checkpointLocked(completed = false)
        return state()
    }

    /** Повторное касание педальной ноты — новая атака даже при неизменном наборе нот. */
    @Synchronized
    fun recordRetriggeredNote(midiNote: Int): RecorderState {
        if (initialConfiguration == null || midiNote !in activeNotes) return state()
        val timeNanos = elapsedNanosLocked()
        events += NotePerformanceEvent(timeNanos, midiNote, isNoteOn = false)
        events += NotePerformanceEvent(timeNanos, midiNote, isNoteOn = true)
        return state()
    }

    /** Каждое фактическое переключение хранится с музыкальной временной меткой. */
    @Synchronized
    fun recordPedalEnabled(enabled: Boolean): RecorderState {
        if (initialConfiguration == null || enabled == pedalEnabled) return state()
        pedalEnabled = enabled
        events += PedalPerformanceEvent(elapsedNanosLocked(), enabled)
        return state()
    }

    @Synchronized
    fun recordLatchedNotes(notes: Set<Int>): RecorderState {
        if (initialConfiguration == null) return state()
        recordLatchedNotesLocked(notes, elapsedNanosLocked())
        return state()
    }

    @Synchronized
    fun recordConfiguration(configuration: PerformanceConfiguration): RecorderState {
        if (initialConfiguration == null || configuration == currentConfiguration) return state()
        currentConfiguration = configuration
        events += ConfigurationPerformanceEvent(
            timeNanos = elapsedNanosLocked(),
            configuration = configuration,
        )
        return state()
    }

    /** Периодический черновик защищает игру даже от уничтожения процесса. */
    fun checkpoint(): RecorderState {
        val snapshot = synchronized(this) {
            if (initialConfiguration == null) null
            else buildRecording(elapsedNanosLocked(), completed = false) to
                storageWriteRevision.incrementAndGet()
        }
        if (snapshot != null) {
            saveLastSafely(snapshot.first, snapshot.second)
        }
        return state()
    }

    @Synchronized
    fun stop(completeReplacement: Boolean = true): RecorderState {
        if (initialConfiguration == null) return state()
        val duration = elapsedNanosLocked()
        recordLatchedNotesLocked(emptySet(), duration)
        recordActiveNotesLocked(emptySet(), duration)
        val recording = buildRecording(duration, completed = true)
        // Пустой дубль возникает, например, при закрытии приложения после
        // случайного касания записи. Он не должен вытеснять выбранную мелодию.
        if (!recording.hasPlayedNotes()) {
            synchronized(storageWriteLock) {
                storageWriteRevision.incrementAndGet() // запретить запоздалый checkpoint
                runCatching { storage.delete(recording.createdAtEpochMillis) }
            }
            saveFailed = false
            replacementId = null
            lastRecording = lastRecording?.takeIf(PerformanceRecording::hasPlayedNotes)
                ?: storage.loadLast()?.recovered()
            initialConfiguration = null
            currentConfiguration = null
            activeNotes = emptySet()
            latchedNotes = emptySet()
            events = mutableListOf()
            return state()
        }
        val saved = saveLastSafely(recording, storageWriteRevision.incrementAndGet())
        // Only a successful, nonempty new take may replace the selected old one.
        // On storage failure (or an empty attempt) the original remains intact.
        if (completeReplacement && saved) {
            replacementId?.let { runCatching { storage.moveToTrash(it) } }
        }
        replacementId = null
        lastRecording = recording
        initialConfiguration = null
        currentConfiguration = null
        activeNotes = emptySet()
        latchedNotes = emptySet()
        events = mutableListOf()
        return state()
    }

    private fun recordActiveNotesLocked(notes: Set<Int>, timeNanos: Long) {
        val safeNotes = notes.filterTo(sortedSetOf()) { it in 0..127 }
        // Сначала отпускаются ушедшие ноты, затем одним временем запускается новый аккорд.
        (activeNotes - safeNotes).sorted().forEach { midiNote ->
            events += NotePerformanceEvent(timeNanos, midiNote, isNoteOn = false)
        }
        (safeNotes - activeNotes).sorted().forEach { midiNote ->
            events += NotePerformanceEvent(timeNanos, midiNote, isNoteOn = true)
        }
        activeNotes = safeNotes
    }

    private fun recordLatchedNotesLocked(notes: Set<Int>, timeNanos: Long) {
        val safeNotes = notes.filterTo(sortedSetOf()) { it in 0..127 }
        if (safeNotes == latchedNotes) return
        events += LatchPerformanceEvent(timeNanos, safeNotes)
        latchedNotes = safeNotes
    }

    private fun elapsedNanosLocked(): Long = if (initialConfiguration == null) {
        lastRecording?.durationNanos ?: 0L
    } else {
        (clock.nowNanos() - startedAtNanos).coerceAtLeast(0L)
    }

    private fun checkpointLocked(completed: Boolean) {
        saveLastSafely(buildRecording(elapsedNanosLocked(), completed), storageWriteRevision.incrementAndGet())
    }

    /** Revisions are allocated under the recorder lock, alongside the snapshot.
     * A delayed old checkpoint can never replace a newer stop snapshot. */
    private fun saveLastSafely(recording: PerformanceRecording, revision: Long): Boolean {
        return synchronized(storageWriteLock) {
            if (revision != storageWriteRevision.get()) return@synchronized true
            val success = runCatching { storage.saveLast(recording) }.isSuccess
            saveFailed = !success
            success
        }
    }

    fun listRecordings(): List<RecordingSummary> = storage.listRecordings()

    @Synchronized
    fun select(id: Long): RecorderState {
        check(initialConfiguration == null && !saveFailed) { "Есть несохранённая запись" }
        lastRecording = requireNotNull(storage.load(id)) { "Запись не найдена" }.recovered()
        return state()
    }

    @Synchronized
    fun delete(id: Long): RecorderState {
        check(initialConfiguration == null && !saveFailed)
        storage.moveToTrash(id)
        if (lastRecording?.createdAtEpochMillis == id) lastRecording = storage.loadLast()?.recovered()
        return state()
    }

    fun load(id: Long): PerformanceRecording? = storage.load(id)?.recovered()

    fun listTrash(): List<TrashRecordingSummary> = storage.listTrash()

    /** Не меняет выбранную запись: проигрыватель получает отдельный снимок корзины. */
    fun loadTrash(id: Long): PerformanceRecording? = storage.loadTrash(id)?.recovered()

    @Synchronized
    fun restoreFromTrash(id: Long): RecorderState {
        check(initialConfiguration == null && !saveFailed)
        storage.restoreFromTrash(id)
        if (lastRecording == null) lastRecording = storage.loadLast()?.recovered()
        return state()
    }

    @Synchronized
    fun retrySave(): RecorderState {
        val snapshot = if (initialConfiguration != null) buildRecording(elapsedNanosLocked(), false)
            else lastRecording
        snapshot?.let { saveLastSafely(it, storageWriteRevision.incrementAndGet()) }
        return state()
    }

    private fun buildRecording(durationNanos: Long, completed: Boolean) = PerformanceRecording(
        createdAtEpochMillis = recordingCreatedAtEpochMillis,
        durationNanos = durationNanos,
        initialConfiguration = requireNotNull(initialConfiguration),
        events = events.toList(),
        completed = completed,
    )
}
