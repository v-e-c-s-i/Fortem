// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.fortem.tuning.ScalaScale
import app.fortem.tuning.ScalaImportException
import app.fortem.tuning.ScalaProblem
import app.fortem.tuning.ScalaScaleCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScalaImportSettings(scale: ScalaScale?, onImported: (ScalaScale) -> Unit, showHeading: Boolean = true) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<UiMessage?>(null) }
    fun importScale(readText: () -> String) {
        busy = true
        scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { ScalaScaleCodec.parse(readText()) }
                onImported(imported)
                message = UiMessage(R.string.scala_imported)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = scalaImportMessage(error)
            } finally { busy = false }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importScale {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= ScalaScaleCodec.MAX_FILE_BYTES) {
                    val count = input.read(buffer, 0, minOf(buffer.size,
                        ScalaScaleCodec.MAX_FILE_BYTES + 1 - output.size()))
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > ScalaScaleCodec.MAX_FILE_BYTES) throw ScalaImportException(ScalaProblem.FILE_TOO_LARGE)
            // Архив Scala использует Latin-1; современные файлы часто UTF-8.
            val utf8 = bytes.toString(Charsets.UTF_8)
            if ('\uFFFD' in utf8) bytes.toString(Charsets.ISO_8859_1) else utf8
        }
    }
    Column {
        if (showHeading) Text("SCALA", style = MaterialTheme.typography.labelSmall)
        Text(
            scale?.title ?: strings.text(R.string.scala_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f),
        )
        TextButton(enabled = !busy, onClick = { launcher.launch(arrayOf("*/*")) }) {
            Text(if (busy) strings.text(R.string.reading) else if (scale == null) strings.text(R.string.scala_import) else strings.text(R.string.scala_add))
        }
        message?.let { Text(strings.text(it), style = MaterialTheme.typography.labelSmall) }
    }
}

/** Decode validation reasons at the UI boundary; never expose raw exception messages. */
private fun scalaImportMessage(error: Exception): UiMessage {
    if (error !is ScalaImportException) return UiMessage(R.string.scala_read_failed)
    val resource = when (error.problem) {
        ScalaProblem.INVALID_TITLE -> R.string.scala_invalid_title
        ScalaProblem.INVALID_INTERVALS -> R.string.scala_invalid_intervals
        ScalaProblem.INVALID_ORDER -> R.string.scala_invalid_order
        ScalaProblem.FILE_TOO_LARGE -> R.string.scala_file_large
        ScalaProblem.MISSING_HEADER -> R.string.scala_missing_header
        ScalaProblem.UNSUPPORTED_STEPS -> R.string.scala_unsupported_steps
        ScalaProblem.MISSING_INTERVALS -> R.string.scala_missing_intervals
        ScalaProblem.INVALID_RATIO -> R.string.scala_invalid_ratio
        ScalaProblem.INVALID_INTERVAL -> R.string.scala_invalid_interval
        ScalaProblem.NONFINITE_INTERVAL -> R.string.scala_nonfinite_interval
        ScalaProblem.INVALID_OCTAVE -> R.string.scala_invalid_octave
        ScalaProblem.POSITIVE_RATIO -> R.string.scala_positive_ratio
        ScalaProblem.LIBRARY_FULL -> R.string.scala_library_full
    }
    return UiMessage(resource, listOfNotNull(error.detail))
}
