// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.midi

import app.fortem.recording.PerformanceConfiguration
import app.fortem.tuning.TuningEngine
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.TuningModel

/** Режим контроллера временный: сохранённые строи и исходные записи не переписываются. */
fun PerformanceConfiguration.forMidiOutput(connected: Boolean): PerformanceConfiguration =
    if (!connected) this else copy(
        tuningSystem = TuningSystem.EQUAL_TEMPERAMENT,
        tonicPitchClass = 0,
        referenceFrequencyHz = TuningEngine.DEFAULT_REFERENCE_FREQUENCY_HZ,
        scalaScale = null,
        tonicSpelling = null,
        tuningModel = TuningModel.LEGACY_EQUAL_TONIC,
    )
