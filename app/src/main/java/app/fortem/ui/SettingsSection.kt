// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Independent visual groups keep adjacent Scala, MIDI and pitch controls distinct. */
@Composable
internal fun SettingsSection(id: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .18f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .38f)),
        modifier = Modifier.fillMaxWidth().testTag("settings-section-$id")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
            content()
        }
    }
}
