package io.github.ccy5123.korjpnime.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/**
 * Single keyboard key. [onClick] is fired on tap-up; pass null in @Preview
 * to keep the surface inert.  [onLongPress] adds a hold gesture (used for
 * Cheonjiin's long-press → digit shortcut) — when both are set, the key
 * wires up via [pointerInput] / [detectTapGestures] instead of plain
 * [clickable] so press-and-hold dispatches independently from quick tap.
 */
@Composable
fun RowScope.Key(
    tokens: KeyboardTokens,
    shape: KeyShape,
    weight: Float = 1f,
    label: String? = null,
    sub: String? = null,
    double: Pair<String, String>? = null,
    cornerHint: String? = null,
    fn: Boolean = false,
    accent: Boolean = false,
    pressed: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
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
    val view = LocalView.current
    val hapticsEnabled = LocalHapticsEnabled.current

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .let { if (flat) it.divider(tokens.hairline) else it.clip(RoundedCornerShape(radius)).background(bg) }
            .let { mod ->
                if (onClick == null && onLongPress == null) return@let mod
                // Single pointerInput path covers both the tap-only and the
                // tap+long-press cases — fires KEYBOARD_TAP haptic on the
                // press-down edge so the buzz feels instant (the prior
                // tap-up haptic via the service had ~100 ms perceived lag).
                mod.pointerInput(onClick to onLongPress) {
                    detectTapGestures(
                        onPress = {
                            if (hapticsEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            tryAwaitRelease()
                        },
                        onTap = { if (onClick != null) onClick() },
                        onLongPress = { if (onLongPress != null) onLongPress() },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Box-with-overlay so the cornerHint (e.g. Cheonjiin's long-press
        // digit '4' on the 'ㄱ ㅋ' cell) can layer over any of the three
        // content modes — content / double / label.
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                content != null -> Box(
                    modifier = Modifier.align(Alignment.Center),
                ) { content() }

                double != null -> Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Both glyphs same size, side by side (primary left,
                    // secondary right) per the user's revised Cheonjiin spec.
                    Text(
                        text = double.first,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                    )
                    Text(
                        text = double.second,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = ink,
                    )
                }

                label != null -> Text(
                    text = label,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Top-end overlay — `sub` (legacy single-letter hint, used by
            // shifted Beolsik etc.) and `cornerHint` (Cheonjiin's long-press
            // digit) share the slot.  cornerHint takes precedence when both
            // are passed.
            val overlay = cornerHint ?: sub
            if (overlay != null) {
                Text(
                    text = overlay,
                    // Scaled with the main glyph (label / double both at 20sp,
                    // overlay at 11sp) to keep the visual ratio constant.
                    fontSize = 11.sp,
                    // Midway brightness between the phoneme glyph (ink) and
                    // the key panel (background) — visible but visually
                    // recessed so it doesn't compete with the main label.
                    color = ink.copy(alpha = 0.35f),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
    }
}

internal fun Modifier.divider(color: Color): Modifier = this.drawBehind {
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
