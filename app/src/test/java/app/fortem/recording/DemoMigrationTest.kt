// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoMigrationTest {
    @Test fun cleanInstallHasNoRecordingsOrTrashAndUpgradeRemovesOnlyReservedDemos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "recordings")
        directory.deleteRecursively()
        try {
            val storage = AndroidPerformanceStorage(context)
            assertTrue(storage.listRecordings().isEmpty())
            assertTrue(storage.listTrash().isEmpty())
            assertNull(storage.loadLast())
            val fixture = PerformanceRecording(1_900_000_000_000L, 1_000_000_000L,
                PerformanceConfiguration(TuningSystem.EQUAL_TEMPERAMENT, 0, 440.0, AudioTimbre.PIANO),
                listOf(NotePerformanceEvent(0, 60, true), NotePerformanceEvent(1_000_000_000L, 60, false)), true)
            val personalTrash = fixture.copy(createdAtEpochMillis = fixture.createdAtEpochMillis + 1)
            storage.saveLast(fixture); storage.saveLast(personalTrash)
            storage.moveToTrash(personalTrash.createdAtEpochMillis)
            val demoIds = (946684800001L..946684800006L) + (946684900001L..946684900006L) +
                (946685000001L..946685000010L) + (946685100001L..946685100015L)
            demoIds.forEachIndexed { index, id ->
                storage.saveLast(fixture.copy(createdAtEpochMillis = id))
                if (index % 2 == 0) storage.moveToTrash(id)
            }
            // Markers from previous releases must not bypass cleanup.
            File(directory, "demos-v4.installed").writeText("1")
            repeat(2) {
                val reloaded = AndroidPerformanceStorage(context)
                assertEquals(listOf(fixture.createdAtEpochMillis), reloaded.listRecordings().map { it.id })
                assertEquals(listOf(personalTrash.createdAtEpochMillis), reloaded.listTrash().map { it.id })
                assertEquals(fixture, reloaded.loadLast())
                assertEquals(personalTrash, reloaded.loadTrash(personalTrash.createdAtEpochMillis))
            }
        } finally { directory.deleteRecursively() }
    }
}
