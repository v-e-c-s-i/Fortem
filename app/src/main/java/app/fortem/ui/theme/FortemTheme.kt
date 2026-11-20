// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D5C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF9EF2DF),
    secondary = Color(0xFFB1CCC4),
    background = Color(0xFF111413),
    surface = Color(0xFF111413),
    surfaceVariant = Color(0xFF404946),
    onSurface = Color(0xFFE1E3E1),
    outline = Color(0xFF89938F),
)

@Composable
fun FortemTheme(content: @Composable () -> Unit) {
    // Единая тёмная схема исключает скачки оформления при смене темы системы.
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
