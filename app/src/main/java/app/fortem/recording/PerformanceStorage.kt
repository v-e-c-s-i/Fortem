// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** One file per take; a checkpoint updates only that take, never its neighbours. */
interface PerformanceStorage {
    fun loadLast(): PerformanceRecording?
    fun saveLast(recording: PerformanceRecording)
    fun listRecordings(): List<RecordingSummary> = listOfNotNull(loadLast()?.summary())
    fun load(id: Long): PerformanceRecording? = loadLast()?.takeIf { it.createdAtEpochMillis == id }
    fun delete(id: Long) = Unit
    fun moveToTrash(id: Long) = delete(id)
    fun listTrash(): List<TrashRecordingSummary> = emptyList()
    fun loadTrash(id: Long): PerformanceRecording? = null
    fun restoreFromTrash(id: Long) = Unit
}

data class RecordingSummary(val id: Long, val durationNanos: Long)
data class TrashRecordingSummary(val id: Long, val durationNanos: Long, val deletedAtMillis: Long)
fun PerformanceRecording.summary() = RecordingSummary(createdAtEpochMillis, durationNanos)

/** AtomicFile keeps the previous version until the replacement is fully written. */
class AndroidPerformanceStorage(context: Context) : PerformanceStorage {
    private val directory = File(context.filesDir, "recordings").apply { mkdirs() }
    private val trashDirectory = File(directory, "trash").apply { mkdirs() }

    init {
        // Migrate 0.10.x without deleting the old file until its copy is durable.
        val legacy = AtomicFile(File(directory, "last.fortem"))
        runCatching { read(legacy)?.let { recording ->
            val target = file(recording.createdAtEpochMillis)
            if (read(target) == null) write(target, recording.recovered())
            legacy.delete()
        } }
        // A killed process may leave a valid incomplete take. Close its notes;
        // keep the original timestamp and every other recording.
        ids().forEach { id ->
            runCatching { read(file(id))?.takeIf { !it.completed }?.let { write(file(id), it.recovered()) } }
        }
        purgeExpiredTrash()
        removeRetiredDemos()
    }

    private fun removeRetiredDemos() {
        // Only the reserved IDs of shipped test examples qualify, never dates or titles.
        // Clear active and trashed copies, including AtomicFile recovery files.
        val demoIds = (946684800001L..946684800006L) + (946684900001L..946684900006L) +
            (946685000001L..946685000010L) + (946685100001L..946685100015L)
        demoIds.forEach { id -> file(id).delete() }
        trashEntries().filter { it.first in demoIds }.forEach { (id, deletedAt) ->
            trashFile(id, deletedAt).delete()
        }
    }

    @Synchronized
    override fun loadLast(): PerformanceRecording? = (ids() - trashEntries().map { it.first }.toSet()).sortedDescending()
        .firstNotNullOfOrNull { read(file(it))?.takeIf(PerformanceRecording::hasPlayedNotes) }
        ?: read(AtomicFile(File(directory, "last.fortem")))?.takeIf(PerformanceRecording::hasPlayedNotes)

    @Synchronized
    override fun saveLast(recording: PerformanceRecording) = write(file(recording.createdAtEpochMillis), recording)

    @Synchronized
    override fun listRecordings(): List<RecordingSummary> = ids().mapNotNull {
        load(it)?.takeIf(PerformanceRecording::hasPlayedNotes)?.summary()
    }

    @Synchronized
    override fun load(id: Long): PerformanceRecording? = if (trashEntries().any { it.first == id }) null else read(file(id))

    @Synchronized
    override fun delete(id: Long) {
        file(id).delete()
        check(id !in ids()) { "Не удалось удалить запись" }
    }

    @Synchronized
    override fun moveToTrash(id: Long) {
        purgeExpiredTrash()
        val recording = requireNotNull(read(file(id))) { "Запись не найдена" }
        // Сначала создаём надёжную копию. Даже авария между копированием и
        // удалением скрывает дубль из списка, не уничтожая его содержимое.
        write(trashFile(id, System.currentTimeMillis()), recording)
        file(id).delete()
    }

    @Synchronized
    override fun listTrash(): List<TrashRecordingSummary> {
        purgeExpiredTrash()
        return trashEntries().mapNotNull { (id, deletedAt) ->
            read(trashFile(id, deletedAt))?.let { TrashRecordingSummary(id, it.durationNanos, deletedAt) }
        }.sortedByDescending { it.deletedAtMillis }
    }

    /** Прослушивание читает снимок, не восстанавливает файл и не продлевает срок корзины. */
    @Synchronized
    override fun loadTrash(id: Long): PerformanceRecording? {
        purgeExpiredTrash()
        val entry = trashEntries().filter { it.first == id }.maxByOrNull { it.second } ?: return null
        return read(trashFile(entry.first, entry.second))
    }

    @Synchronized
    override fun restoreFromTrash(id: Long) {
        purgeExpiredTrash()
        val entries = trashEntries().filter { it.first == id }
        val entry = requireNotNull(entries.maxByOrNull { it.second }) { "Запись не найдена в корзине" }
        val recording = requireNotNull(read(trashFile(entry.first, entry.second)))
        write(file(id), recording)
        entries.forEach { trashFile(it.first, it.second).delete() }
        check(trashEntries().none { it.first == id }) { "Не удалось восстановить запись" }
    }

    private fun purgeExpiredTrash() {
        val now = System.currentTimeMillis()
        trashEntries().filter { now >= it.second && now - it.second >= TRASH_RETENTION_MILLIS }
            .forEach { (id, deletedAt) ->
                // Убираем возможную оставшуюся активную копию после аварии
                // до истечения срока: она не должна снова появиться в списке.
                file(id).delete()
                trashFile(id, deletedAt).delete()
            }
    }

    private fun trashEntries(): List<Pair<Long, Long>> = trashDirectory.listFiles().orEmpty().mapNotNull {
        Regex("deleted_([0-9]+)_take_([0-9]+)\\.fortem(?:\\.bak)?").matchEntire(it.name)?.let { match ->
            match.groupValues[2].toLongOrNull()?.let { id ->
                match.groupValues[1].toLongOrNull()?.let { deletedAt -> id to deletedAt }
            }
        }
    }.distinct()

    private fun trashFile(id: Long, deletedAt: Long) =
        AtomicFile(File(trashDirectory, "deleted_${deletedAt}_take_$id.fortem"))

    companion object { const val TRASH_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L }

    // Include AtomicFile backup names so interrupted commits are recoverable.
    private fun ids(): Set<Long> = directory.listFiles().orEmpty().mapNotNull {
        Regex("take_([0-9]+)\\.fortem(?:\\.bak)?").matchEntire(it.name)?.groupValues?.get(1)?.toLongOrNull()
    }.toSet()

    private fun file(id: Long) = AtomicFile(File(directory, "take_$id.fortem"))
    private fun read(file: AtomicFile): PerformanceRecording? = runCatching {
        file.openRead().bufferedReader(Charsets.UTF_8).use { PerformanceCodec.decode(it.readText()) }
    }.getOrNull()

    private fun write(file: AtomicFile, recording: PerformanceRecording) {
        val output = file.startWrite()
        try {
            output.write(PerformanceCodec.encode(recording).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}
