// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import app.fortem.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The toolbar and help use the same pedal, centered by its visible bounds. */
@Composable
internal fun PedalSymbol(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        // Original iOS outline occupies y=.28..95: subtract .115 to center it at .5.
        fun y(value: Float) = size.height * (value - .115f)
        val pedal = Path().apply {
            moveTo(size.width * .15f, y(.78f))
            lineTo(size.width * .85f, y(.78f))
            quadraticTo(size.width * .98f, y(.35f), size.width * .70f, y(.28f))
            lineTo(size.width * .35f, y(.43f))
            close()
            moveTo(size.width * .10f, y(.95f))
            lineTo(size.width * .90f, y(.95f))
        }
        drawPath(pedal, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Approved combined gear/power outline, shared by the toolbar and symbol reference. */
@Composable
internal fun SettingsSymbol(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val outline = Path()
        repeat(8) { tooth ->
            listOf(0f to .35f, .18f to .35f, .28f to .45f, .62f to .45f, .72f to .35f).forEach { (phase, radius) ->
                val angle = Math.toRadians(((tooth + phase) * 45f - 90f).toDouble())
                val x = center.x + kotlin.math.cos(angle).toFloat() * size.width * radius
                val y = center.y + kotlin.math.sin(angle).toFloat() * size.height * radius
                if (tooth == 0 && phase == 0f) outline.moveTo(x, y) else outline.lineTo(x, y)
            }
        }
        outline.close()
        val stroke = Stroke(1.35.dp.toPx(), cap = StrokeCap.Round)
        drawPath(outline, color, style = stroke)
        // The power mark follows the approved 24-unit SVG geometry inside the gear.
        val powerStroke = Stroke(1.25.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * .5f, size.height * (8.2f / 24f)),
            Offset(size.width * .5f, size.height * (11.2f / 24f)),
            strokeWidth = powerStroke.width, cap = StrokeCap.Round)
        drawArc(color, startAngle = -43.000875f, sweepAngle = 266.001750f, useCenter = false,
            topLeft = Offset(size.width * (8.65f / 24f), size.height * (9.034732f / 24f)),
            size = Size(size.width * (6.7f / 24f), size.height * (6.7f / 24f)), style = powerStroke)
    }
}

/** A readable reference inside settings; examples perform no actions. */
@Composable
internal fun InterfaceSymbolsHelp(showHeading: Boolean = true) {
    val strings = LocalAppStrings.current
    if (showHeading) Text(strings.text(R.string.symbols_heading), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f))
    SymbolHelpRow(strings.text(R.string.pedal), strings.text(R.string.symbol_pedal)) {
        PedalSymbol(Modifier.size(25.dp, 22.dp))
    }
    SymbolHelpRow(strings.text(R.string.start_recording), strings.text(R.string.symbol_record)) {
        val color = MaterialTheme.colorScheme.error
        Canvas(Modifier.size(15.dp)) { drawCircle(color) }
    }
    val actions = listOf(
        Triple(RecordingActionKind.PLAY, R.string.play, R.string.symbol_play),
        Triple(RecordingActionKind.STOP, R.string.stop, R.string.symbol_stop),
        Triple(RecordingActionKind.RERECORD, R.string.rerecord_title, R.string.symbol_rerecord),
        Triple(RecordingActionKind.EXPORT, R.string.wav_export, R.string.symbol_export),
        Triple(RecordingActionKind.DELETE, R.string.delete, R.string.symbol_delete),
    )
    actions.forEach { (kind, title, description) ->
        SymbolHelpRow(strings.text(title), strings.text(description)) {
            RecordingActionIcon(kind, modifier = Modifier.size(23.dp))
        }
    }
    SymbolHelpRow(strings.text(R.string.symbol_lock_title), strings.text(R.string.symbol_lock)) { HelpPadlock(false) }
    SymbolHelpRow(strings.text(R.string.clear_latches), strings.text(R.string.symbol_unlock)) { HelpPadlock(true) }
    SymbolHelpRow(strings.text(R.string.switch_timbre), strings.text(R.string.symbol_timbre)) {
        val active = MaterialTheme.colorScheme.primary
        val inactive = MaterialTheme.colorScheme.outline
        Canvas(Modifier.size(22.dp)) {
            drawCircle(active, radius = 2.dp.toPx(), center = Offset(center.x, size.height * .3f))
            drawCircle(inactive, radius = 2.dp.toPx(), center = Offset(center.x, size.height * .7f))
        }
    }
    SymbolHelpRow(strings.text(R.string.tonic_heading), strings.text(R.string.symbol_tonic)) { Text("C ▾") }
    SymbolHelpRow(strings.text(R.string.settings), strings.text(R.string.symbol_settings)) {
        SettingsSymbol(Modifier.size(22.dp))
    }
}

@Composable
private fun SymbolHelpRow(title: String, detail: String, symbol: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { symbol() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
        }
    }
}

/** Same body, high shackle and keyhole as the keyboard/toolbar padlocks. */
@Composable
private fun HelpPadlock(open: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(23.dp)) {
        val top = size.height * .44f
        val outline = Path().apply {
            moveTo(size.width * .32f, top)
            lineTo(size.width * .32f, size.height * .27f)
            cubicTo(size.width * .32f, size.height * .02f, size.width * .71f, size.height * .02f,
                size.width * .71f, size.height * .27f)
            if (!open) lineTo(size.width * .71f, top)
        }
        val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
        drawPath(outline, color, style = stroke)
        drawRoundRect(color, topLeft = Offset(size.width * .18f, top),
            size = Size(size.width * .64f, size.height * .47f), cornerRadius = CornerRadius(2.5.dp.toPx()), style = stroke)
        drawCircle(color, radius = 1.5.dp.toPx(), center = Offset(center.x, size.height * .64f))
        drawLine(color, Offset(center.x, size.height * .68f), Offset(center.x, size.height * .78f),
            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}
