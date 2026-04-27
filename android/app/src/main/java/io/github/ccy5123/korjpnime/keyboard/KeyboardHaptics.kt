package io.github.ccy5123.korjpnime.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * Composition-scoped flag for the IME's haptic-feedback preference.
 * Provided by [io.github.ccy5123.korjpnime.KorJpnImeService] from the
 * `KeyboardPreferences.hapticsFlow` so any keyboard surface inside the
 * composition can fire press-down feedback on key taps without piping
 * the boolean through every Composable's parameter list.
 *
 * Default `true` so previews / tests that don't provide the flag still
 * get tactile feedback on a real device (it's harmless when no Window
 * is attached — `View.performHapticFeedback` no-ops in that case).
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * Drop-in replacement for `Modifier.clickable` that fires a
 * `KEYBOARD_TAP` haptic on the **press-down** edge instead of the
 * tap-up edge.  The extra ~100–200 ms tap latency that
 * `Modifier.clickable` introduces was perceptible on Note20 — users
 * felt the buzz "after" the press; press-down feedback feels instant.
 *
 * `onTap` still fires on the release edge so a press-then-cancel
 * (finger drag off the key) doesn't commit text — only haptic fires
 * on the down edge to confirm the touch was registered.
 */
fun Modifier.clickableWithKeyboardHaptic(
    onClick: () -> Unit,
): Modifier = composed {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    pointerInput(Unit) {
        detectTapGestures(
            onPress = {
                if (enabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                tryAwaitRelease()
            },
            onTap = { onClick() },
        )
    }
}
