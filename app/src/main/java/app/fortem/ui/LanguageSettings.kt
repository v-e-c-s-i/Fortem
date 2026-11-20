// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.PopupPositionProvider
import app.fortem.R
import app.fortem.settings.AppLanguage

/** One compact row opens the two supported languages without radio-button lists. */
@Composable
internal fun LanguageSettings() {
    val strings = LocalAppStrings.current
    val preference = LocalLanguagePreference.current
    var expanded by remember { mutableStateOf(false) }
    fun title(language: AppLanguage) = if (language == AppLanguage.RUSSIAN) "Русский" else "English"
    Box {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f),
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(strings.text(R.string.language_heading), modifier = Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                Text(title(preference.language) + " ▾", style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Anchor the menu to the trailing edge of the complete preference row.
        if (expanded) Popup(popupPositionProvider = remember { LanguageMenuPosition },
            onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
          Surface(modifier = Modifier.width(160.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), shadowElevation = 8.dp) {
           Column {

            AppLanguage.entries.forEach { language ->
                DropdownMenuItem(text = { Text(title(language)) }, onClick = {
                    preference.onChanged(language)
                    expanded = false
                })
            }
           }
          }
        }
    }
}

private object LanguageMenuPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (if (anchorBounds.bottom + popupContentSize.height <= windowSize.height)
            anchorBounds.bottom else anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
