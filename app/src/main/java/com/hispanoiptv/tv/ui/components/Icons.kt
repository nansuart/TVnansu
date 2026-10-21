package com.hispanoiptv.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NavIcon { HOME, LIVE, MOVIES, SERIES, SEARCH, SETTINGS }

/** Simple line icons drawn in code, so the app needs no icon library. */
@Composable
fun LineIcon(icon: NavIcon, color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (icon) {
            NavIcon.HOME -> {
                val p = Path().apply {
                    moveTo(3.5f * u, 11f * u)
                    lineTo(12f * u, 4f * u)
                    lineTo(20.5f * u, 11f * u)
                    moveTo(6f * u, 9.5f * u)
                    lineTo(6f * u, 20f * u)
                    lineTo(18f * u, 20f * u)
                    lineTo(18f * u, 9.5f * u)
                    moveTo(10f * u, 20f * u)
                    lineTo(10f * u, 14.5f * u)
                    lineTo(14f * u, 14.5f * u)
                    lineTo(14f * u, 20f * u)
                }
                drawPath(p, color, style = stroke)
            }
            NavIcon.LIVE -> {
                drawCircle(color, radius = 2.2f * u, center = Offset(12f * u, 12f * u))
                arc(color, u, 6f, stroke, -50f, 100f)
                arc(color, u, 6f, stroke, 130f, 100f)
                arc(color, u, 10f, stroke, -45f, 90f)
                arc(color, u, 10f, stroke, 135f, 90f)
            }
            NavIcon.MOVIES -> {
                drawRoundRect(
                    color, topLeft = Offset(3f * u, 5f * u), size = Size(18f * u, 14f * u),
                    cornerRadius = CornerRadius(3f * u), style = stroke,
                )
                val p = Path().apply {
                    moveTo(10f * u, 9f * u)
                    lineTo(15.5f * u, 12f * u)
                    lineTo(10f * u, 15f * u)
                    close()
                }
                drawPath(p, color)
            }
            NavIcon.SERIES -> {
                drawRoundRect(
                    color, topLeft = Offset(3f * u, 8f * u), size = Size(15f * u, 12f * u),
                    cornerRadius = CornerRadius(2.5f * u), style = stroke,
                )
                val back = Path().apply {
                    moveTo(7f * u, 4.5f * u)
                    lineTo(19f * u, 4.5f * u)
                    quadraticTo(21.5f * u, 4.5f * u, 21.5f * u, 7f * u)
                    lineTo(21.5f * u, 16f * u)
                }
                drawPath(back, color, style = stroke)
            }
            NavIcon.SEARCH -> {
                drawCircle(color, radius = 6.5f * u, center = Offset(10.5f * u, 10.5f * u), style = stroke)
                drawLine(color, Offset(15.5f * u, 15.5f * u), Offset(20.5f * u, 20.5f * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }
            NavIcon.SETTINGS -> {
                val rows = listOf(6f to 15f, 12f to 8f, 18f to 13f)
                for ((y, knob) in rows) {
                    drawLine(color, Offset(4f * u, y * u), Offset(20f * u, y * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
                    drawCircle(color, radius = 2.4f * u, center = Offset(knob * u, y * u))
                }
            }
        }
    }
}

private fun DrawScope.arc(color: Color, u: Float, r: Float, stroke: Stroke, start: Float, sweep: Float) {
    drawArc(
        color = color,
        startAngle = start,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset((12f - r) * u, (12f - r) * u),
        size = Size(2 * r * u, 2 * r * u),
        style = stroke,
    )
}
