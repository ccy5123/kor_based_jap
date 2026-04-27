package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

private val ROW1 = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ")
private val ROW2 = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ")
private val ROW3 = listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")

/**
 * Momentary-shift jamo map.  Mirrors the `shifted` column in
 * `tsf/src/HangulComposer.cpp::VkToJamo` — the seven 두벌식 keys whose shifted
 * form differs from the unshifted one.  All other keys ignore Shift.
 */
private fun shiftedJamo(jamo: String): String = when (jamo) {
    "ㅂ" -> "ㅃ"
    "ㅈ" -> "ㅉ"
    "ㄷ" -> "ㄸ"
    "ㄱ" -> "ㄲ"
    "ㅅ" -> "ㅆ"
    "ㅐ" -> "ㅒ"
    "ㅔ" -> "ㅖ"
    else -> jamo
}

@Composable
fun BeolsikLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit = {},
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

    // Momentary Shift — toggled by tapping the Shift key, released by any
    // subsequent key (commit / backspace / space / enter / globe / symbols).
    // Caps-lock (double-tap-to-lock) is a future polish.
    var shifted by remember { mutableStateOf(false) }

    // Wrap every non-Shift action so any tap releases the Shift latch.
    val dispatch: (KeyAction) -> Unit = { action ->
        if (shifted) shifted = false
        onAction(action)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ROW1.forEach { rawJamo ->
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JamoSpacer(weight = 0.5f)
            ROW2.forEach { rawJamo ->
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
            JamoSpacer(weight = 0.5f)
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(tokens, shape, weight = 1.4f, fn = true, pressed = shifted,
                onClick = {
                    shifted = !shifted
                    // Pass through so the service can fire haptic / future hooks.
                    // Shift state itself is owned here, not in the service.
                    onAction(KeyAction.Shift)
                }) {
                ShiftIcon(color = tokens.inkSoft)
            }
            ROW3.forEach { rawJamo ->
                // Row 3 has no shiftable jamo (ㅋㅌㅊㅍ ㅠㅜㅡ) but still releases
                // the Shift latch via dispatch so a stray Shift→ㅋ tap doesn't
                // leave Shift sticky.
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
            Key(tokens, shape, weight = 1.4f, fn = true,
                onClick = { dispatch(KeyAction.Backspace) }) {
                BackspaceIcon(color = tokens.inkSoft)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "!#1",
                onClick = { dispatch(KeyAction.Symbols) })
            Key(tokens, shape, weight = 1.3f, fn = true,
                onClick = { dispatch(KeyAction.SwitchIme) }) {
                GlobeIcon(color = tokens.inkSoft)
            }
            Key(tokens, shape, weight = 0.7f, fn = true, label = ",",
                onClick = { dispatch(KeyAction.Commit(",")) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                onClick = { dispatch(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = ".",
                onClick = { dispatch(KeyAction.Commit(".")) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { dispatch(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

@Composable
internal fun RowScope.JamoSpacer(weight: Float) {
    Box(modifier = Modifier.weight(weight).fillMaxHeight())
}

@Composable
internal fun RowScope.SpaceKey(
    tokens: KeyboardTokens,
    shape: KeyShape,
    weight: Float = 3.5f,
    onClick: (() -> Unit)? = null,
) {
    val radius = when (shape) {
        KeyShape.ROUNDED -> 8.dp
        KeyShape.PILL -> 999.dp
        KeyShape.FLAT -> 0.dp
        KeyShape.SQUIRCLE -> 14.dp
    }
    val flat = shape == KeyShape.FLAT
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxHeight()
            .let {
                if (flat) it.background(Color.Transparent) else
                    it.clip(RoundedCornerShape(radius)).background(tokens.key)
            }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "한국어",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.inkSoft,
        )
    }
}
