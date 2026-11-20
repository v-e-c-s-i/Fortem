// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import app.fortem.audio.AudioTimbre
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.TuningModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceCodecTest {
    private val initial = PerformanceConfiguration(
        tuningSystem = TuningSystem.JUST_INTONATION,
        tonicPitchClass = 2,
        referenceFrequencyHz = 441.5,
        timbre = AudioTimbre.PIANO,
    )

    @Test
    fun roundTripPreservesNotesTimingAndConfigurationChanges() {
        val changed = initial.copy(
            tuningSystem = TuningSystem.PYTHAGOREAN,
            timbre = AudioTimbre.PURE_TONE,
        )
        val recording = PerformanceRecording(
            createdAtEpochMillis = 1234L,
            durationNanos = 3_000_000_000L,
            initialConfiguration = initial,
            events = listOf(
                PedalPerformanceEvent(0L, false),
                NotePerformanceEvent(10L, 60, true, 0.8f),
                PedalPerformanceEvent(12L, true),
                LatchPerformanceEvent(15L, setOf(60)),
                ConfigurationPerformanceEvent(20L, changed),
                PedalPerformanceEvent(25L, false),
                NotePerformanceEvent(30L, 60, false, 0.8f),
            ),
            completed = true,
        )

        assertEquals(recording, PerformanceCodec.decode(PerformanceCodec.encode(recording)))
    }

    @Test
    fun legacyTakeWithoutPedalStillDecodes() {
        // Формат до 0.11.6 не содержал PEDAL; порядок и длительности нот сохраняются.
        val legacy = """FORTEM_PERFORMANCE\t1
CREATED\t1234
DURATION\t30
COMPLETED\t1
INITIAL\tJUST_INTONATION\t2\t441.5\tPIANO
NOTE\t10\tON\t60\t0.8
NOTE\t30\tOFF\t60\t0.8
""".replace("\\t", "\t")
        val recording = requireNotNull(PerformanceCodec.decode(legacy))
        assertEquals(listOf(NotePerformanceEvent(10L, 60, true),
            NotePerformanceEvent(30L, 60, false)), recording.events)
        assertEquals(recording, PerformanceCodec.decode(PerformanceCodec.encode(recording)))
    }

    @Test
    fun malformedPedalStateIsRejected() {
        val recording = PerformanceRecording(1L, 10L, initial,
            listOf(PedalPerformanceEvent(0L, true)), completed = true)
        val encoded = PerformanceCodec.encode(recording).replace("PEDAL\t0\t1", "PEDAL\t0\tbad")
        assertNull(PerformanceCodec.decode(encoded))
    }

    @Test
    fun scalaSnapshotsSurviveReplacementAndConfigurationChanges() {
        val equal = ScalaScale("Равномерный\tпример", (0..11).map { it * 100.0 })
        val changed = ScalaScale("Другой файл", equal.cents.mapIndexed { i, value ->
            if (i == 4) 386.313713864835 else value
        })
        val initialScala = initial.copy(tuningSystem = TuningSystem.SCALA, scalaScale = equal)
        val take = PerformanceRecording(1L, 100L, initialScala, listOf(
            NotePerformanceEvent(0L, 64, true),
            ConfigurationPerformanceEvent(50L, initialScala.copy(scalaScale = changed)),
            NotePerformanceEvent(100L, 64, false)), true)
        assertEquals(take, PerformanceCodec.decode(PerformanceCodec.encode(take)))
        val damaged = PerformanceCodec.encode(take).replace(
            app.fortem.tuning.ScalaScaleCodec.encode(equal), "-")
        assertNull(PerformanceCodec.decode(damaged))
    }

    @Test
    fun invalidHeaderIsRejected() {
        assertNull(PerformanceCodec.decode("not a Fortem performance"))
    }

    @Test fun enharmonicTonicChangesRoundTripAndLegacyKeepsItsOriginalAnchor() {
        val sharp = initial.copy(tonicPitchClass = 1, tonicSpelling = TonicSpelling.SHARP,
            tuningModel = TuningModel.TONIC_RELATIVE)
        val flat = sharp.copy(tonicSpelling = TonicSpelling.FLAT)
        val take = PerformanceRecording(1L, 100L, sharp, listOf(
            NotePerformanceEvent(0L, 61, true),
            ConfigurationPerformanceEvent(50L, flat),
            NotePerformanceEvent(100L, 61, false)), true)
        assertEquals(take, PerformanceCodec.decode(PerformanceCodec.encode(take)))

        val legacy = "FORTEM_PERFORMANCE\t1\nCREATED\t1\nDURATION\t100\nCOMPLETED\t1\n" +
            "INITIAL\tJUST_INTONATION\t1\t440.0\tPIANO\t-\n" +
            "CONFIG\t50\tPYTHAGOREAN\t1\t440.0\tPIANO\t-\n"
        val decoded = requireNotNull(PerformanceCodec.decode(legacy))
        assertNull(decoded.initialConfiguration.tonicSpelling)
        assertNull((decoded.events.single() as ConfigurationPerformanceEvent).configuration.tonicSpelling)
        assertEquals(decoded, PerformanceCodec.decode(PerformanceCodec.encode(decoded)))
    }

    @Test fun damagedNewFormatIsRejectedRatherThanRetunedAsLegacy() {
        val take = PerformanceRecording(1L, 100L,
            initial.copy(tonicSpelling = TonicSpelling.FLAT), emptyList(), true)
        val text = PerformanceCodec.encode(take)
        assertNull(PerformanceCodec.decode(text.replace("\tFLAT", "\tUNKNOWN")))
        assertNull(PerformanceCodec.decode(text.replace("\tFLAT", "")))
        assertNull(PerformanceCodec.decode(text.replace("FORTEM_PERFORMANCE\t3", "FORTEM_PERFORMANCE\t4")))
        assertNull(PerformanceCodec.decode(text.replace("\tLEGACY_EQUAL_TONIC", "\tUNKNOWN")))
        assertNull(PerformanceCodec.decode(text.replace("\tLEGACY_EQUAL_TONIC", "")))
    }

    @Test fun versionTwoKeepsC4ModelIncludingChangesAndLegacyNullSpelling() {
        val old = "FORTEM_PERFORMANCE\t2\nCREATED\t1\nDURATION\t100\nCOMPLETED\t1\n" +
            "INITIAL\tJUST_INTONATION\t4\t440.0\tPIANO\t-\tSHARP\n" +
            "CONFIG\t50\tPYTHAGOREAN\t1\t432.0\tPIANO\t-\tFLAT\n" +
            "CONFIG\t60\tJUST_INTONATION\t2\t441.5\tPIANO\t-\t-\n"
        val decoded = requireNotNull(PerformanceCodec.decode(old))
        assertEquals(TuningModel.LEGACY_C4_TONIC, decoded.initialConfiguration.tuningModel)
        decoded.events.filterIsInstance<ConfigurationPerformanceEvent>().forEach {
            assertEquals(TuningModel.LEGACY_C4_TONIC, it.configuration.tuningModel)
        }
        assertNull((decoded.events.last() as ConfigurationPerformanceEvent).configuration.tonicSpelling)
        assertEquals(decoded, PerformanceCodec.decode(PerformanceCodec.encode(decoded)))
        assertNull(PerformanceCodec.decode(old.replace("\tSHARP", "")))
        assertNull(PerformanceCodec.decode(old.replace("\tFLAT", "\tUNKNOWN")))
    }
}
