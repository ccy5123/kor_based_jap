package io.github.ccy5123.korjpnime.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Backspace key with hold-to-repeat acceleration.
 *
 * Behavior:
 *   - Tap: fire [onTriggerBackspace] once.
 *   - Hold past [INITIAL_HOLD_MS]: start auto-repeat.  First repeat after
 *     [REPEAT_START_MS], ramping down by [REPEAT_RAMP_STEP_MS] per beat to a
 *     floor of [REPEAT_FLOOR_MS] (~10 chars to reach floor).
 *
 * The actual delete logic stays in `KorJpnImeService.handleBackspace` so the
 * composer's peel-then-delete behavior is identical for tap and repeat:
 * Hangul preedit gets peeled jamo by jamo first, then `deleteSurroundingText`
 * once the composer is empty.
 *
 * Mirrors [Key]'s visual styling for shape / fn-row colour so it sits in line
 * with the other keys.
 */
@Composable
fun RowScope.BackspaceKey(
    tokens: KeyboardTokens,
    shape: KeyShape,
    weight: Float = 1.4f,
    onTriggerBackspace: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val view = LocalView.current
    val hapticsEnabled = LocalHapticsEnabled.current

    // Auto-repeat coroutine.  LaunchedEffect(pressed) cancels cleanly when the
    // finger lifts because waitForUpOrCancellation flips `pressed = false`,
    // which keys the LaunchedEffect to relaunch (and the previous one cancels).
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(INITIAL_HOLD_MS)
        var interval = REPEAT_START_MS
        while (isActive) {
            // Match the press-down haptic on every auto-repeat tick so the
            // user feels each delete (otherwise the held-backspace stream
            // is silent after the initial press).
            if (hapticsEnabled) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            onTriggerBackspace()
            delay(interval)
            if (interval > REPEAT_FLOOR_MS) {
                interval = (interval - REPEAT_RAMP_STEP_MS).coerceAtLeast(REPEAT_FLOOR_MS)
            }
        }
    }

    val radius = when (shape) {
        KeyShape.ROUNDED -> 8.dp
        KeyShape.PILL -> 999.dp
        KeyShape.FLAT -> 0.dp
        KeyShape.SQUIRCLE -> 14.dp
    }
    val flat = shape == KeyShape.FLAT
    val bg = if (pressed) tokens.keyPress else tokens.keyAlt

    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .let {
                if (flat) it.divider(tokens.hairline)
                else it.clip(RoundedCornerShape(radius)).background(bg)
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Fire haptic on the press-down edge for instant tactile
                    // feedback (matches the Key composable's behaviour).
                    if (hapticsEnabled) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    // Fire one delete immediately so a quick tap deletes a
                    // single char without waiting for the hold timeout.
                    onTriggerBackspace()
                    pressed = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BackspaceIcon(color = tokens.inkSoft)
    }
}

private const val INITIAL_HOLD_MS = 400L
private const val REPEAT_START_MS = 80L
private const val REPEAT_FLOOR_MS = 30L
private const val REPEAT_RAMP_STEP_MS = 5L
