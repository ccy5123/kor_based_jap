package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/** Single keyboard key. Visual only — no input wiring (D2). */
@Composable
fun RowScope.Key(
    tokens: KeyboardTokens,
    shape: KeyShape,
    weight: Float = 1f,
    label: String? = null,
    sub: String? = null,
    double: Pair<String, String>? = null,
    fn: Boolean = false,
    accent: Boolean = false,
    pressed: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val radius = when (shape) {
        KeyShape.ROUNDED -> 8.dp
        KeyShape.PILL -> 999.dp
        KeyShape.FLAT -> 0.dp
        KeyShape.SQUIRCLE -> 14.dp
    }
    val bg = when {
        pressed -> tokens.keyPress
        accent -> tokens.accent
        fn -> tokens.keyAlt
        else -> tokens.key
    }
    val ink = when {
        accent -> tokens.onAccent
        fn -> tokens.inkSoft
        else -> tokens.ink
    }
    val flat = shape == KeyShape.FLAT

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .let { if (flat) it.divider(tokens.hairline) else it.clip(RoundedCornerShape(radius)).background(bg) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            content != null -> content()
            double != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = double.second,
                    fontSize = 9.sp,
                    color = ink.copy(alpha = 0.55f),
                )
                Text(
                    text = double.first,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                )
            }
            label != null -> {
                if (sub != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = sub,
                            fontSize = 8.sp,
                            color = tokens.inkSoft,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                        Text(
                            text = label,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = ink,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                } else {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                    )
                }
            }
        }
    }
}

private fun Modifier.divider(color: Color): Modifier = this.drawBehind {
    val strokePx = 1f
    // right edge
    drawLine(
        color = color,
        start = Offset(size.width - strokePx / 2, 0f),
        end = Offset(size.width - strokePx / 2, size.height),
        strokeWidth = strokePx,
    )
    // bottom edge
    drawLine(
        color = color,
        start = Offset(0f, size.height - strokePx / 2),
        end = Offset(size.width, size.height - strokePx / 2),
        strokeWidth = strokePx,
    )
}
