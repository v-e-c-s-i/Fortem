// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.fortem.recording.WavExportDirectory
import app.fortem.settings.UserSettings

@Composable
internal fun WavFolderSettings(settings: UserSettings, isVisible: Boolean) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current.applicationContext
    var selected by remember { mutableStateOf(settings.loadWavExportDirectory()) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(isVisible) { if (isVisible) selected = settings.loadWavExportDirectory() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                WavExportDirectory.remember(context, settings, uri)
                selected = uri.toString(); error = false
            } catch (_: Exception) { error = true }
        }
    }
    Text(strings.text(R.string.wav_folder_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
    selected?.let { value ->
        val name = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(value))
            .substringAfterLast('/').substringAfter(':') }.getOrDefault(strings.text(R.string.folder_selected))
        Text(strings.text(R.string.folder_name, name), style = MaterialTheme.typography.labelSmall)
    }
    TextButton(onClick = { launcher.launch(selected?.let(Uri::parse)) }) {
        Text(if (selected == null) strings.text(R.string.wav_folder_choose) else strings.text(R.string.wav_folder_change))
    }
    if (error) Text(strings.text(R.string.folder_access_save_failed), color = MaterialTheme.colorScheme.error)
}
