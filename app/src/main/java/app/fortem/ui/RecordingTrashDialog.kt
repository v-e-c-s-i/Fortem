// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fortem.recording.*
import kotlinx.coroutines.*

@Composable
internal fun RecordingTrashDialog(
    recorder: PerformanceRecorder,
    playingId: Long?,
    playbackPositionNanos: () -> Long,
    previewEnabled: Boolean,
    transportBusy: Boolean,
    onPreview: (Long) -> Unit,
    onRestored: (RecorderState) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var deletedTakes by remember { mutableStateOf<List<TrashRecordingSummary>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(revision) {
        busy = true
        try { deletedTakes = withContext(Dispatchers.IO) { recorder.listTrash() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = R.string.trash_read_failed
        } finally { busy = false }
    }
    Dialog(onDismissRequest = { if (!busy && !transportBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImmersiveDialog()
        Surface(shape = RoundedCornerShape(18.dp), modifier = Modifier
            .fillMaxWidth(.62f).widthIn(min = 390.dp, max = 600.dp).fillMaxHeight(.88f)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text(R.string.recording_trash), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !busy && !transportBusy, onClick = onDismiss) { Text(strings.text(R.string.close)) }
                }
                Text(strings.text(R.string.trash_retention_hint),
                    style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!busy && deletedTakes.isEmpty()) Text(strings.text(R.string.trash_empty), Modifier.padding(vertical = 16.dp))
                LazyColumn(Modifier.weight(1f), flingBehavior = rememberSmoothScrollFlingBehavior()) {
                    items(deletedTakes, key = { it.id }) { item ->
                        val isPlaying = playingId == item.id
                        val progressColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .78f)
                        Box(Modifier.fillMaxWidth()) {
                        if (isPlaying) PlaybackProgress(playbackPositionNanos, item.durationNanos,
                            progressColor, Modifier.matchParentSize())
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(recordingName(item.id), style = MaterialTheme.typography.bodyMedium)
                                val remaining = (AndroidPerformanceStorage.TRASH_RETENTION_MILLIS -
                                    (System.currentTimeMillis() - item.deletedAtMillis).coerceAtLeast(0L))
                                    .coerceAtLeast(0L)
                                val days = (remaining + 86_399_999L) / 86_400_000L
                                TrashRecordingTime(item.durationNanos, isPlaying, playbackPositionNanos, days)
                            }
                            IconButton(enabled = !busy && !transportBusy && previewEnabled,
                                onClick = { onPreview(item.id) }, modifier = Modifier.semantics {
                                    contentDescription = if (isPlaying) strings.text(R.string.stop_preview)
                                        else strings.text(R.string.play_trash_recording)
                                }) {
                                RecordingActionIcon(if (isPlaying) RecordingActionKind.STOP else RecordingActionKind.PLAY)
                            }
                            TextButton(enabled = !busy && !transportBusy, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        onRestored(withContext(Dispatchers.IO) { recorder.restoreFromTrash(item.id) })
                                        revision++
                                    } catch (cancelled: CancellationException) { throw cancelled
                                    } catch (_: Exception) { error = R.string.recording_restore_failed; busy = false }
                                }
                            }) { Text(strings.text(R.string.restore)) }
                        }
                        }
                        HorizontalDivider()
                    }
                }
                error?.let { Text(strings.text(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun TrashRecordingTime(durationNanos: Long, isPlaying: Boolean, position: () -> Long, days: Long) {
    val strings = LocalAppStrings.current
    val seconds by remember(isPlaying, position, durationNanos) {
        derivedStateOf { if (isPlaying) position().coerceIn(0L, durationNanos) / 1_000_000_000L else 0L }
    }
    val duration = if (isPlaying)
        "${recordingDuration(seconds * 1_000_000_000L)} / ${recordingDuration(durationNanos)}"
        else recordingDuration(durationNanos)
    Text(strings.text(R.string.trash_days_remaining, duration, days), style = MaterialTheme.typography.labelSmall)
}
