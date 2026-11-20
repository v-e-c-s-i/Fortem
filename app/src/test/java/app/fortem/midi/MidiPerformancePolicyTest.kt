// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import app.fortem.audio.AudioTimbre
import app.fortem.recording.PerformanceConfiguration
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.TonicSpelling
import app.fortem.tuning.TuningModel
import org.junit.Assert.*
import org.junit.Test

class MidiPerformancePolicyTest {
    @Test fun midiUsesFixedEqualTemperamentAndLeavesOriginalConfigurationIntact() {
        val custom = PerformanceConfiguration(TuningSystem.SCALA, 7, 432.0, AudioTimbre.PIANO,
            ScalaScale("Пример", (0..11).map { it * 100.0 }), TonicSpelling.FLAT,
            TuningModel.TONIC_RELATIVE)
        val midi = custom.forMidiOutput(true)
        assertEquals(TuningSystem.EQUAL_TEMPERAMENT, midi.tuningSystem)
        assertEquals(0, midi.tonicPitchClass)
        assertEquals(440.0, midi.referenceFrequencyHz, 0.0)
        assertNull(midi.scalaScale)
        assertNull(midi.tonicSpelling)
        assertEquals(TonicSpelling.FLAT, custom.tonicSpelling)
        assertEquals(TuningModel.TONIC_RELATIVE, custom.tuningModel)
        assertEquals(TuningModel.LEGACY_EQUAL_TONIC, midi.tuningModel)
        assertEquals(custom.timbre, midi.timbre)
        assertSame(custom, custom.forMidiOutput(false))
        assertEquals(432.0, custom.referenceFrequencyHz, 0.0)
        assertNotNull(custom.scalaScale)
    }
}
