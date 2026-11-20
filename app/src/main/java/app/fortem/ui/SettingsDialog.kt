// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fortem.BuildConfig
import app.fortem.settings.UserSettings
import app.fortem.tuning.TuningSystem
import app.fortem.tuning.ScalaScale
import app.fortem.midi.MidiOutputEngine
import java.util.Locale

/** Все допустимые настройки применяются сразу; закрытие не отменяет изменения. */
@Composable
fun SettingsPanel(
    userSettings: UserSettings,
    isVisible: Boolean,
    scalaScale: ScalaScale?,
    scalaScales: List<ScalaScale>,
    visibleScalaIds: Set<String>,
    onScalaImported: (ScalaScale) -> Unit,
    onScalaDeleted: (ScalaScale) -> Unit,
    onVisibilityChanged: (Set<TuningSystem>, Set<String>) -> Unit,
    midiOutput: MidiOutputEngine,
    midiConnected: Boolean,
    midiLocalSound: Boolean,
    onMidiLocalSoundChanged: (Boolean) -> Unit,
    visibleTunings: Set<TuningSystem>,
    referenceFrequencyHz: Double,
    retriggerHeldNotesDuringPlayback: Boolean,
    interfaceScale: Float,
    onInterfaceScaleChanged: (Float) -> Unit,
    latchGesturesEnabled: Boolean,
    onLatchGesturesChanged: (Boolean) -> Unit,
    onOpenTrash: () -> Unit,
    onReferenceFrequencyChanged: (Double) -> Unit,
    onRetriggerChanged: (Boolean) -> Unit,
    cutoutInsets: WindowInsets = WindowInsets.displayCutout,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    var referenceText by remember {
        mutableStateOf(formatReferenceFrequency(referenceFrequencyHz))
    }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            // Незавершённый/ошибочный ввод не заменяет последнее допустимое значение.
            referenceText = formatReferenceFrequency(referenceFrequencyHz)
        }
    }
    val parsedReference = parseReferenceFrequency(referenceText)

    val panelInteractionSource = remember { MutableInteractionSource() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AbsoluteRoundedCornerShape(
            topLeft = 18.dp,
            topRight = 0.dp,
            bottomRight = 0.dp,
            bottomLeft = 18.dp,
        ),
        tonalElevation = 0.dp,
        modifier = modifier
            .fillMaxHeight()
            .testTag("settings-panel")
            // Пустое место панели перехватывает касание и не закрывает её через фон.
            .clickable(
                interactionSource = panelInteractionSource,
                indication = null,
                onClick = {},
            ),
    ) {
            Column(
                modifier = Modifier
                    // This drawer touches the right edge only. A notch on the opposite left
                    // edge must not add left padding and shift the whole settings column right.
                    .windowInsetsPadding(cutoutInsets.only(WindowInsetsSides.Right + WindowInsetsSides.Top + WindowInsetsSides.Bottom)
                        .union(WindowInsets.captionBar).union(WindowInsets.ime))
                    .padding(18.dp),
            ) {
                Text(
                    text = strings.text(R.string.settings),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.weight(1f).testTag("settings-scroll")
                        .verticalScroll(rememberScrollState(), flingBehavior = rememberSmoothScrollFlingBehavior()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsSection("interface", strings.text(R.string.interface_heading)) {
                        Text(strings.text(R.string.control_size), style = MaterialTheme.typography.bodySmall)
                        Text(strings.text(R.string.control_size_value, (interfaceScale * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall)
                        Slider(value = interfaceScale, onValueChange = onInterfaceScaleChanged,
                            valueRange = .8f..1.2f,
                            modifier = Modifier.semantics { contentDescription = strings.text(R.string.control_size) })
                        TextButton(onClick = { onInterfaceScaleChanged(1f) }) {
                            Text(strings.text(R.string.control_size_reset))
                        }
                        LanguageSettings()
                    }
                    SettingsSection("tunings", strings.text(R.string.tunings_heading)) {
                        // Checkbox не хранит черновик: игровой экран обновляется в том же кадре.
                        val visibleCount = visibleTunings.count { it != TuningSystem.SCALA } +
                            visibleScalaIds.size
                        TuningSystem.entries.filter { it != TuningSystem.SCALA }.forEach { tuning ->
                            val checked = tuning in visibleTunings
                            val canToggle = !checked || visibleCount > 1
                            CompactTuningRow(strings.tuningTitle(tuning), checked, canToggle, onToggle = {
                                onVisibilityChanged(
                                    if (checked) visibleTunings - tuning else visibleTunings + tuning,
                                    visibleScalaIds,
                                )
                            }, description = strings.tuningDescription(tuning))
                        }
                        scalaScales.forEach { scale ->
                            val checked = scale.libraryId in visibleScalaIds
                            CompactTuningRow(scale.title, checked, !checked || visibleCount > 1,
                                onToggle = {
                                    onVisibilityChanged(visibleTunings,
                                        if (checked) visibleScalaIds - scale.libraryId
                                        else visibleScalaIds + scale.libraryId)
                                }, onDelete = { onScalaDeleted(scale) })
                        }

                    }
                    SettingsSection("scala", "SCALA") {
                        ScalaImportSettings(scalaScale, onScalaImported, showHeading = false)
                    }
                    SettingsSection("reference", strings.text(R.string.reference_heading)) {
                        Text(
                            text = if (midiConnected) {
                                strings.text(R.string.midi_reference_hint)
                            } else if (parsedReference == null) {
                                strings.text(R.string.reference_invalid, formatReferenceFrequency(referenceFrequencyHz))
                            } else {
                                strings.text(R.string.reference_hint)
                            },
                            // Пояснение оформлено так же, как подписи к строям выше.
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = if (midiConnected) "440" else referenceText,
                            enabled = !midiConnected,
                            onValueChange = {
                                referenceText = it
                                parseReferenceFrequency(it)?.let(onReferenceFrequencyChanged)
                            },
                            label = { Text(strings.text(R.string.reference_label)) },
                            isError = !midiConnected && parsedReference == null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )

                    }
                    SettingsSection("midi", strings.text(R.string.midi_heading)) {
                        MidiOutputSettings(midiOutput, midiLocalSound, onMidiLocalSoundChanged, showHeading = false)
                    }
                    SettingsSection("held", strings.text(R.string.held_notes_heading)) {
                        CheckboxSettingRow(title = strings.text(R.string.latch_gestures_title),
                            checked = latchGesturesEnabled, onCheckedChange = onLatchGesturesChanged,
                            description = strings.text(R.string.latch_gestures_description))
                        CheckboxSettingRow(title = strings.text(R.string.retrigger_title),
                            checked = retriggerHeldNotesDuringPlayback, onCheckedChange = onRetriggerChanged,
                            description = strings.text(R.string.retrigger_description))
                    }
                    SettingsSection("recordings", strings.text(R.string.recordings_wav_heading)) {
                        WavFolderSettings(userSettings, isVisible)
                        TextButton(onClick = onOpenTrash) { Text(strings.text(R.string.trash_30_days)) }
                    }
                    SettingsSection("symbols", strings.text(R.string.symbols_heading)) {
                        InterfaceSymbolsHelp(showHeading = false)
                    }
                    // Reference-only piano credits live at the end, next to the application version.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                        .testTag("settings-about"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.text(R.string.piano_heading), fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f),
                            style = MaterialTheme.typography.labelSmall)
                        Text(strings.text(R.string.piano_credits),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
                            style = MaterialTheme.typography.labelSmall)
                        Text("Fortem · ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
    }
}

private fun formatReferenceFrequency(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.1f", value)
    }

/** Одна строка сохраняет полноценные зоны касания, даже в узкой панели. */
@Composable
private fun CompactTuningRow(
    title: String,
    checked: Boolean,
    canToggle: Boolean,
    onToggle: () -> Unit,
    onDelete: (() -> Unit)? = null,
    description: String? = null,
) {
    val strings = LocalAppStrings.current
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        CheckboxSettingRow(
            title = title,
            checked = checked,
            enabled = canToggle,
            onCheckedChange = { onToggle() },
            description = description,
            singleLineTitle = true,
            modifier = Modifier.weight(1f),
        )
        if (onDelete != null) {
            val color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f)
            IconButton(onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = strings.text(R.string.delete_tuning, title) }) {
                Canvas(Modifier.size(20.dp)) {
                    val line = 1.5.dp.toPx()
                    drawLine(color, Offset(size.width * .15f, size.height * .25f),
                        Offset(size.width * .85f, size.height * .25f), line)
                    drawRect(color, Offset(size.width * .38f, size.height * .09f),
                        Size(size.width * .24f, size.height * .16f), style = Stroke(line))
                    drawRect(color, Offset(size.width * .25f, size.height * .25f),
                        Size(size.width * .5f, size.height * .65f), style = Stroke(line))
                    listOf(.43f, .57f).forEach { x ->
                        drawLine(color, Offset(size.width * x, size.height * .39f),
                            Offset(size.width * x, size.height * .75f), line)
                    }
                }
            }
        }
    }
}
