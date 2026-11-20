// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import android.os.SystemClock
import kotlin.math.ceil
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fortem.audio.AudioTimbre
import app.fortem.recording.*
import app.fortem.settings.UserSettings
import java.io.File
import kotlinx.coroutines.*

/** Дисковые операции и WAV выполняются в IO, проигрыватель остаётся независимым. */
@Composable
internal fun RecordingsDialog(
    recorder: PerformanceRecorder,
    settings: UserSettings,
    pianoAvailable: Boolean,
    retriggerHeldNotes: Boolean,
    recordingEnabled: Boolean,
    playingId: Long?,
    listState: LazyListState,
    playbackPositionNanos: () -> Long,
    transportBusy: Boolean,
    onPlay: (Long) -> Unit,
    onRerecord: (Long) -> Unit,
    onDeleted: (Long, RecorderState) -> Unit,
    onRestored: (RecorderState) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var takes by remember { mutableStateOf<List<RecordingSummary>>(emptyList()) }
    var newestFirst by remember { mutableStateOf(settings.loadRecordingsNewestFirst()) }
    var loading by remember { mutableStateOf(true) }
    var mutating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var pendingFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var folderUri by remember { mutableStateOf(settings.loadWavExportDirectory()) }
    var undoId by remember { mutableStateOf<Long?>(null) }
    var undoDeadline by remember { mutableLongStateOf(0L) }
    val undoRemaining = remember { mutableFloatStateOf(1f) }
    var exportNotice by remember { mutableStateOf<Long?>(null) }
    var newTakeHint by remember { mutableStateOf(false) }
    val busy = loading || transportBusy || exportJob != null || pendingFolderId != null
    val rowsBusy = busy || mutating
    val sortedTakes = remember(takes, newestFirst) {
        if (newestFirst) takes.sortedByDescending { it.id } else takes.sortedBy { it.id }
    }

    LaunchedEffect(Unit) {
        loading = true
        try { takes = withContext(Dispatchers.IO) { recorder.listRecordings() }
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) { message = R.string.recordings_read_failed
        } finally { loading = false }
    }

    RevealRecordingOnOpen(sortedTakes, ready = !loading, playingId = playingId, listState = listState)

    // Время берём из монотонных часов: задержка кадра не удлиняет окно отмены.
    LaunchedEffect(undoId, undoDeadline) {
        if (undoId == null) return@LaunchedEffect
        while (true) {
            val remaining = undoDeadline - SystemClock.uptimeMillis()
            undoRemaining.floatValue = (remaining / 5_000f).coerceIn(0f, 1f)
            if (remaining <= 0L) { undoId = null; break }
            delay(50)
        }
    }

    // Успех экспорта не вытесняет кнопку отмены удаления.
    LaunchedEffect(exportNotice, undoId) {
        if (exportNotice == null || undoId != null) return@LaunchedEffect
        delay(2_500)
        exportNotice = null
    }

    fun exportToFolder(take: RecordingSummary, directory: android.net.Uri) {
        if (exportJob != null) return
        exportProgress = 0f
        exportJob = scope.launch {
            val target = File(context.cacheDir, "fortem-export-${java.util.UUID.randomUUID()}.wav")
            try {
                val workerContext = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    val recording = recorder.load(take.id) ?: error("Запись не найдена")
                    val needsPiano = recording.initialConfiguration.timbre == AudioTimbre.PIANO ||
                        recording.events.filterIsInstance<ConfigurationPerformanceEvent>()
                            .any { it.configuration.timbre == AudioTimbre.PIANO }
                    check(pianoAvailable || !needsPiano) { "Не загружено фортепиано" }
                    var lastPercent = -1
                    PerformanceWavExporter.export(recording, target, retriggerHeldNotes,
                        checkCancelled = { workerContext.ensureActive() },
                        progress = { fraction ->
                            val percent = (fraction * 100).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                scope.launch { exportProgress = fraction }
                            }
                        },
                    )
                    WavExportDirectory.write(context, directory, target, strings.recordingName(take.id) + ".wav") {
                        workerContext.ensureActive()
                    }
                }
                exportNotice = SystemClock.uptimeMillis()
            } catch (error: CancellationException) { throw error
            } catch (_: SecurityException) {
                settings.saveWavExportDirectory(null); folderUri = null
                message = R.string.folder_access_lost
            } catch (_: Exception) {
                message = R.string.wav_save_failed
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { target.delete() }
                exportProgress = null
                exportJob = null
            }
        }
    }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val takeId = pendingFolderId
        pendingFolderId = null
        if (uri != null) {
            try {
                WavExportDirectory.remember(context, settings, uri)
                folderUri = uri.toString()
                if (takeId != null) {
                    loading = true
                    scope.launch {
                        try {
                            val take = withContext(Dispatchers.IO) { recorder.load(takeId)?.summary() }
                            if (take != null) exportToFolder(take, uri)
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) { message = R.string.recording_open_failed
                        } finally { loading = false }
                    }
                }
            } catch (_: Exception) { message = R.string.folder_access_save_failed }
        }
    }

    fun export(take: RecordingSummary) {
        if (busy) return
        val directory = folderUri
        if (directory == null) {
            pendingFolderId = take.id
            chooseFolder.launch(null)
        } else exportToFolder(take, android.net.Uri.parse(directory))
    }

    fun delete(take: RecordingSummary) {
        if (mutating || busy) return
        mutating = true
        scope.launch {
            try {
                // Finish an accepted storage operation even if the user closes this dialog.
                // Only the affected row changes; never reload or resize the whole list.
                withContext(NonCancellable) {
                    val state = withContext(Dispatchers.IO) { recorder.delete(take.id) }
                    takes = takes.filterNot { it.id == take.id }
                    onDeleted(take.id, state)
                    undoRemaining.floatValue = 1f
                    undoDeadline = SystemClock.uptimeMillis() + 5_000
                    undoId = take.id
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { message = R.string.recording_trash_failed
            } finally { mutating = false }
        }
    }

    fun undoDeletion() {
        val id = undoId ?: return
        if (mutating || busy || SystemClock.uptimeMillis() >= undoDeadline) return
        undoId = null
        mutating = true
        scope.launch {
            try {
                withContext(NonCancellable) {
                    val (state, restoredTakes) = withContext(Dispatchers.IO) {
                        recorder.restoreFromTrash(id) to recorder.listRecordings()
                    }
                    takes = restoredTakes
                    onRestored(state)
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { message = R.string.recording_restore_failed
            } finally { mutating = false }
        }
    }

    RecordingsDialogFrame(dismissEnabled = !busy, onDismiss = onDismiss, feedback = {
        Box(Modifier.align(Alignment.BottomEnd).height(44.dp), contentAlignment = Alignment.CenterEnd) {
            if (undoId != null) {
                Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    Row(Modifier.testTag("recording-undo").clickable(enabled = !rowsBusy, onClick = ::undoDeletion)
                        .padding(horizontal = 12.dp).height(40.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.text(R.string.undo), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        UndoCountdown(undoRemaining)
                    }
                }
            } else if (exportNotice != null) {
                Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    Text(strings.text(R.string.wav_saved), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }) {
            Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.text(R.string.recordings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, modifier = Modifier.testTag("recordings-sort"), onClick = {
                        newestFirst = !newestFirst
                        settings.saveRecordingsNewestFirst(newestFirst)
                    }) { Text(if (newestFirst) strings.text(R.string.newest_first) else strings.text(R.string.oldest_first)) }
                    TextButton(enabled = !busy, modifier = Modifier.testTag("recordings-close"), onClick = onDismiss) { Text(strings.text(R.string.close)) }
                }
                Text(buildAnnotatedString {
                    append(strings.text(if (newTakeHint) R.string.new_take_hint else R.string.rerecord_hint))
                    appendInlineContent("rerecord", strings.text(R.string.rerecord_icon))
                    append(strings.text(R.string.trash_retention_suffix))
                }, inlineContent = mapOf("rerecord" to InlineTextContent(
                    Placeholder(18.sp, 18.sp, PlaceholderVerticalAlign.TextCenter)) {
                    RecordingActionIcon(RecordingActionKind.RERECORD, modifier = Modifier.fillMaxSize())
                }), modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("recordings-loading"))
                if (!loading && takes.isEmpty()) Text(strings.text(R.string.recordings_empty),
                    modifier = Modifier.padding(vertical = 20.dp))
                exportProgress?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(strings.text(R.string.wav_progress, (progress * 100).toInt()), Modifier.weight(1f))
                        TextButton(onClick = { exportJob?.cancel() }) { Text(strings.text(R.string.cancel)) }
                    }
                }
                // The viewport and bottom clearance stay unchanged when feedback or time updates.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    RecordingsList(
                        takes = sortedTakes, listState = listState, playingId = playingId,
                        playbackPositionNanos = playbackPositionNanos, busy = rowsBusy,
                        recordingEnabled = recordingEnabled, onPlay = onPlay, onExport = ::export,
                        onRerecord = onRerecord, onRerecordHint = { newTakeHint = true }, onDelete = ::delete,
                        modifier = Modifier.fillMaxSize(),
                    )

                }
            }
    }
    message?.let { text ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(strings.text(text)) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(strings.text(R.string.ok)) } })
    }
}

/** Playback updates only the active row's clock and draw phase, never the list's layout. */
@Composable
internal fun RecordingsList(
    takes: List<RecordingSummary>,
    listState: LazyListState,
    playingId: Long?,
    playbackPositionNanos: () -> Long,
    busy: Boolean,
    recordingEnabled: Boolean,
    onPlay: (Long) -> Unit,
    onExport: (RecordingSummary) -> Unit,
    onRerecord: (Long) -> Unit,
    onRerecordHint: () -> Unit,
    onDelete: (RecordingSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val progressColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .78f)
    LazyColumn(modifier.testTag("recording-list"), state = listState,
        flingBehavior = rememberSmoothScrollFlingBehavior(),
        // Fixed end padding lets the final row clear the window edge in either playback state.
        contentPadding = PaddingValues(bottom = 64.dp)) {
        items(takes, key = { it.id }, contentType = { "recording" }) { take ->
            val isCurrent = playingId == take.id
            Box(Modifier.fillMaxWidth().testTag("recording-row-${take.id}")) {
                if (isCurrent) PlaybackProgress(playbackPositionNanos, take.durationNanos,
                    progressColor, Modifier.matchParentSize())
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clipToBounds()
                        .clickable(enabled = !busy) { onPlay(take.id) }) {
                        // Keep the title inside its share of the row; all actions retain their width.
                        Text(strings.recordingName(take.id), maxLines = 1, softWrap = false,
                            modifier = Modifier.fillMaxWidth().clipToBounds().testTag("recording-title-${take.id}")
                                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 900,
                                    repeatDelayMillis = 0, spacing = MarqueeSpacing(24.dp), velocity = 36.dp),
                            style = MaterialTheme.typography.bodyMedium)
                        RecordingRowTime(take, isCurrent, playbackPositionNanos)
                    }
                    RecordingAction(if (isCurrent) RecordingActionKind.STOP else RecordingActionKind.PLAY,
                        strings.text(if (isCurrent) R.string.stop else R.string.play), !busy,
                        modifier = Modifier.testTag("recording-play-${take.id}")) { onPlay(take.id) }
                    RecordingAction(RecordingActionKind.EXPORT, strings.text(R.string.wav_export), !busy) { onExport(take) }
                    RecordingAction(RecordingActionKind.RERECORD, strings.text(R.string.rerecord_hold), !busy && recordingEnabled,
                        onLongClick = { onRerecord(take.id) }, onClick = onRerecordHint)
                    RecordingAction(RecordingActionKind.DELETE, strings.text(R.string.delete), !busy,
                        modifier = Modifier.testTag("recording-delete-${take.id}")) { onDelete(take) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** One line in either state: starting playback cannot change a row's height or scroll anchor. */
@Composable
private fun RecordingRowTime(take: RecordingSummary, isCurrent: Boolean, position: () -> Long) {
    val duration = recordingDuration(take.durationNanos)
    val seconds by remember(isCurrent, position, take.durationNanos) {
        derivedStateOf { if (isCurrent) position().coerceIn(0L, take.durationNanos) / 1_000_000_000L else 0L }
    }
    val text = if (isCurrent) "${recordingDuration(seconds * 1_000_000_000L)} / $duration" else duration
    Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().testTag("recording-time-${take.id}"),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Убывающая дуга и секунды показывают оставшееся время одним компактным знаком. */
@Composable
private fun UndoCountdown(remaining: FloatState) {
    val strings = LocalAppStrings.current
    val seconds by remember(remaining) {
        derivedStateOf { ceil(remaining.floatValue.coerceIn(0f, 1f) * 5f).toInt() }
    }
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.size(24.dp).semantics { contentDescription = strings.remainingSeconds(seconds) },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val width = 2.dp.toPx()
            val inset = width / 2f
            drawArc(color.copy(alpha = .2f), -90f, 360f, false,
                topLeft = Offset(inset, inset), size = Size(size.width - width, size.height - width),
                style = Stroke(width))
            drawArc(color, -90f, 360f * remaining.floatValue.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = Size(size.width - width, size.height - width),
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Text(seconds.toString(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RecordingAction(kind: RecordingActionKind, label: String,
                            enabled: Boolean, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val strings = LocalAppStrings.current
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.3f)
    val recordColor = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.85f else 0.3f)
    Box(modifier.size(40.dp).combinedClickable(enabled = enabled, onClick = onClick,
        onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) strings.text(R.string.start_rerecord) else null)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        RecordingActionIcon(kind, color, recordColor)
    }
}
