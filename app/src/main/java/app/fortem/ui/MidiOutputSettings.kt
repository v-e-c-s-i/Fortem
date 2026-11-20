// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.fortem.midi.MidiOutputEngine

@Composable
fun MidiOutputSettings(
    engine: MidiOutputEngine,
    localSound: Boolean,
    onLocalSoundChanged: (Boolean) -> Unit,
    showHeading: Boolean = true,
) {
    val strings = LocalAppStrings.current
    val state by engine.state.collectAsState()
    Column {
        if (showHeading) Text(strings.text(R.string.midi_heading), style = MaterialTheme.typography.labelSmall)
        Text(strings.text(R.string.midi_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
        CheckboxSettingRow(
            title = strings.text(R.string.midi_local_sound),
            checked = localSound,
            onCheckedChange = onLocalSoundChanged,
            description = strings.text(R.string.midi_local_sound_description),
        )
        if (!state.supported) {
            Text(strings.text(R.string.midi_unsupported), style = MaterialTheme.typography.labelSmall)
        } else {
            TextButton(onClick = engine::disconnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.selected == null) strings.text(R.string.midi_off_selected) else strings.text(R.string.midi_turn_off))
            }
            state.destinations.forEach { destination ->
                TextButton(onClick = { engine.connect(destination) },
                    enabled = !state.connecting, modifier = Modifier.fillMaxWidth()) {
                    Text((if (destination == state.selected) "● " else "") + destination.title)
                }
            }
            if (state.destinations.isEmpty()) Text(
                strings.text(R.string.midi_connect_hint),
                style = MaterialTheme.typography.labelSmall)
            if (state.connecting) Text(strings.text(R.string.connecting), style = MaterialTheme.typography.labelSmall)
            state.error?.let { Text(strings.text(it), style = MaterialTheme.typography.labelSmall) }
        }
    }
}
