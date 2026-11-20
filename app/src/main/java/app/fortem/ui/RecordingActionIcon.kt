// SPDX-License-Identifier: GPL-3.0-only
package app.fortem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class RecordingActionKind { PLAY, STOP, EXPORT, RERECORD, DELETE }

/** Один вектор для действия и пояснения: точка и стрелка больше не отдельные символы. */
@Composable
internal fun RecordingActionIcon(
    kind: RecordingActionKind,
    color: Color = MaterialTheme.colorScheme.onSurface,
    recordColor: Color = MaterialTheme.colorScheme.error,
    modifier: Modifier = Modifier.size(21.dp),
) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        when (kind) {
            RecordingActionKind.PLAY -> drawPath(Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.12f)
                lineTo(size.width * 0.83f, size.height * 0.50f)
                lineTo(size.width * 0.25f, size.height * 0.88f)
                close()
            }, color)
            RecordingActionKind.STOP -> drawRect(color,
                topLeft = Offset(size.width * .22f, size.height * .22f),
                size = Size(size.width * .56f, size.height * .56f))
            RecordingActionKind.EXPORT -> {
                drawLine(color, Offset(size.width * .5f, size.height * .12f),
                    Offset(size.width * .5f, size.height * .65f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .3f, size.height * .47f),
                    Offset(size.width * .5f, size.height * .67f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .7f, size.height * .47f),
                    Offset(size.width * .5f, size.height * .67f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .16f, size.height * .86f),
                    Offset(size.width * .84f, size.height * .86f), stroke.width, cap = StrokeCap.Round)
            }
            RecordingActionKind.RERECORD -> {
                drawCircle(recordColor, radius = size.width * .14f)
                drawArc(color, startAngle = -45f, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(size.width * .16f, size.height * .16f),
                    size = Size(size.width * .68f, size.height * .68f), style = stroke)
                // Наконечник примыкает к концу дуги и смотрит по её касательной.
                val angle = Math.toRadians(255.0)
                val radial = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
                val tip = center + radial * (size.width * .34f)
                val direction = Offset(-radial.y, radial.x)
                val side = Offset(-direction.y, direction.x)
                drawPath(Path().apply {
                    val base = tip - direction * (size.width * .17f)
                    val left = base + side * (size.width * .10f)
                    val right = base - side * (size.width * .10f)
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                }, color)
            }
            RecordingActionKind.DELETE -> {
                drawRoundRect(color, topLeft = Offset(size.width * .25f, size.height * .29f),
                    size = Size(size.width * .5f, size.height * .59f), style = stroke)
                drawLine(color, Offset(size.width * .16f, size.height * .22f),
                    Offset(size.width * .84f, size.height * .22f), stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * .41f, size.height * .13f),
                    Offset(size.width * .59f, size.height * .13f), stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}
