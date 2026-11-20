// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Общее оформление настроек: галочка у первой строки названия, пояснение рядом с ним. */
@Composable
internal fun CheckboxSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String? = null,
    singleLineTitle: Boolean = false,
) {
    val titleStyle = MaterialTheme.typography.bodyMedium
    // Название остаётся на прежней высоте относительно галочки. Пояснение идёт
    // сразу за текстом, а не за пустой нижней частью 48-dp зоны касания.
    val titleLineHeight = with(LocalDensity.current) { titleStyle.lineHeight.toDp() }
    val titleTop = ((48.dp - titleLineHeight) / 2).coerceAtLeast(0.dp)
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
            // Название и пояснение переключают одну настройку; отдельного
            // обработчика у галочки нет, поэтому касание не срабатывает дважды.
            .toggleable(checked, enabled = enabled, role = Role.Checkbox,
                onValueChange = onCheckedChange),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null,
            modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(top = titleTop, end = 4.dp, bottom = 10.dp)) {
            Text(title, style = titleStyle,
                maxLines = if (singleLineTitle) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
            }
        }
    }
}
