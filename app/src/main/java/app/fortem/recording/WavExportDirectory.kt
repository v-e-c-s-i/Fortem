// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.recording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.fortem.settings.UserSettings
import java.io.File

internal object WavExportDirectory {
    fun remember(context: Context, settings: UserSettings, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        settings.saveWavExportDirectory(uri.toString())
    }

    fun write(context: Context, directoryUri: Uri, source: File, name: String,
              checkCancelled: () -> Unit) {
        val resolver = context.contentResolver
        val directory = DocumentsContract.buildDocumentUriUsingTree(directoryUri,
            DocumentsContract.getTreeDocumentId(directoryUri))
        val destination = DocumentsContract.createDocument(resolver, directory, "audio/wav", name)
            ?: error("Не удалось создать WAV в выбранной папке")
        try {
            val output = resolver.openOutputStream(destination, "wt")
                ?: error("Не удалось открыть WAV")
            output.use { out -> source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
            } }
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }
}
