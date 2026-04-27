package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun ShiftIcon(color: Color, size: Int = 16) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 16f
        val path = Path().apply {
            moveTo(8 * s, 2.5f * s)
            lineTo(3 * s, 8 * s)
            lineTo(5.5f * s, 8 * s)
            lineTo(5.5f * s, 13 * s)
            lineTo(10.5f * s, 13 * s)
            lineTo(10.5f * s, 8 * s)
            lineTo(13 * s, 8 * s)
            close()
        }
        drawPath(path, color, style = Stroke(width = 1.5f * s))
    }
}

@Composable
fun BackspaceIcon(color: Color, size: Int = 18) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 18f
        val outline = Path().apply {
            moveTo(5 * s, 1 * s)
            lineTo(1 * s, 7 * s)
            lineTo(5 * s, 13 * s)
            lineTo(16 * s, 13 * s)
            quadraticBezierTo(17 * s, 13 * s, 17 * s, 12 * s)
            lineTo(17 * s, 2 * s)
            quadraticBezierTo(17 * s, 1 * s, 16 * s, 1 * s)
            close()
        }
        drawPath(outline, color, style = Stroke(width = 1.5f * s))
        drawLine(color, Offset(8 * s, 5 * s), Offset(13 * s, 9 * s), strokeWidth = 1.5f * s)
        drawLine(color, Offset(13 * s, 5 * s), Offset(8 * s, 9 * s), strokeWidth = 1.5f * s)
    }
}

@Composable
fun EnterIcon(color: Color, size: Int = 18) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 18f
        val arrow = Path().apply {
            moveTo(16 * s, 2 * s)
            lineTo(16 * s, 6 * s)
            quadraticBezierTo(16 * s, 9 * s, 13 * s, 9 * s)
            lineTo(2 * s, 9 * s)
        }
        drawPath(arrow, color, style = Stroke(width = 1.7f * s))
        val tip = Path().apply {
            moveTo(6 * s, 5 * s)
            lineTo(2 * s, 9 * s)
            lineTo(6 * s, 13 * s)
        }
        drawPath(tip, color, style = Stroke(width = 1.7f * s))
    }
}

@Composable
fun GlobeIcon(color: Color, size: Int = 16) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 16f
        val center = Offset(8 * s, 8 * s)
        val radius = 6 * s
        drawCircle(color, radius, center, style = Stroke(width = 1.5f * s))
        drawLine(color, Offset(2 * s, 8 * s), Offset(14 * s, 8 * s), strokeWidth = 1.5f * s)
        // Vertical "meridians" approximated as ellipses (two stroke arcs).
        val v = Path().apply {
            moveTo(8 * s, 2 * s)
            cubicTo(11 * s, 5 * s, 11 * s, 11 * s, 8 * s, 14 * s)
            moveTo(8 * s, 2 * s)
            cubicTo(5 * s, 5 * s, 5 * s, 11 * s, 8 * s, 14 * s)
        }
        drawPath(v, color, style = Stroke(width = 1.5f * s))
    }
}

@Composable
fun SettingsGearIcon(color: Color, size: Int = 13) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 16f
        drawCircle(color, 2 * s, Offset(8 * s, 8 * s), style = Stroke(width = 1.4f * s))
        val rays = listOf(
            Offset(8 * s, 1 * s) to Offset(8 * s, 3 * s),
            Offset(8 * s, 13 * s) to Offset(8 * s, 15 * s),
            Offset(1 * s, 8 * s) to Offset(3 * s, 8 * s),
            Offset(13 * s, 8 * s) to Offset(15 * s, 8 * s),
            Offset(3 * s, 3 * s) to Offset(4.5f * s, 4.5f * s),
            Offset(11.5f * s, 11.5f * s) to Offset(13 * s, 13 * s),
            Offset(3 * s, 13 * s) to Offset(4.5f * s, 11.5f * s),
            Offset(11.5f * s, 4.5f * s) to Offset(13 * s, 3 * s),
        )
        rays.forEach { (a, b) ->
            drawLine(color, a, b, strokeWidth = 1.4f * s)
        }
    }
}

@Composable
fun ChipChevron(color: Color, size: Int = 14) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val w = this.size.width
        val s = w / 14f
        val p = Path().apply {
            moveTo(5 * s, 4 * s)
            lineTo(9 * s, 7 * s)
            lineTo(5 * s, 10 * s)
        }
        drawPath(p, color, style = Stroke(width = 1.5f * s))
    }
}
