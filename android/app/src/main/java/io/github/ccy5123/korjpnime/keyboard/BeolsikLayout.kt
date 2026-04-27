package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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

private val ROW0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val ROW1 = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ")
private val ROW2 = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ")
private val ROW3 = listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")

/**
 * Symbol pages for the 두벌식 layout — surfaced when the user taps `!#1`
 * from letters and cycled via the page-toggle key (`1`/`2`).  Layout
 * matches the user's spec for Japanese-output context (full-width 「」
 * 、 。 ¥) since this IME's commit target is Japanese.
 *
 * Each row is `List<String>` (not `String`) so multi-codepoint glyphs
 * with variation selectors (e.g. `▪︎` = U+25AA + U+FE0E) stay grouped as
 * one tappable key.
 */
private val SYM_PAGE_1: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "/", "_", "<", ">", "「", "」"),
    listOf("!", "@", "#", "¥", "%", "^", "&", "*", "(", ")"),
    listOf("-", "'", "\"", ":", ";", "、", "？"),
)

private val SYM_PAGE_2: List<List<String>> = listOf(
    listOf("`", "~", "\\", "|", "{", "}", "€", "£", "¥", "$"),
    listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧"),
    listOf("☆", "▪︎", "¤", "《", "》", "¡", "¿"),
)

private enum class BeolsikPage { LETTERS, SYMBOLS_1, SYMBOLS_2 }

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
    var page by remember { mutableStateOf(BeolsikPage.LETTERS) }

    when (page) {
        BeolsikPage.LETTERS -> BeolsikLetters(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            onShowSymbols = { page = BeolsikPage.SYMBOLS_1 },
        )
        BeolsikPage.SYMBOLS_1 -> BeolsikSymbols(
            tokens = tokens,
            shape = shape,
            rows = SYM_PAGE_1,
            otherPageLabel = "2/2",
            onAction = onAction,
            onShowLetters = { page = BeolsikPage.LETTERS },
            onCyclePage = { page = BeolsikPage.SYMBOLS_2 },
        )
        BeolsikPage.SYMBOLS_2 -> BeolsikSymbols(
            tokens = tokens,
            shape = shape,
            rows = SYM_PAGE_2,
            otherPageLabel = "1/2",
            onAction = onAction,
            onShowLetters = { page = BeolsikPage.LETTERS },
            onCyclePage = { page = BeolsikPage.SYMBOLS_1 },
        )
    }
}

@Composable
private fun BeolsikLetters(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    onShowSymbols: () -> Unit,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

    var shifted by remember { mutableStateOf(false) }
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
        // Top number row.  Each digit commits as ASCII; the IME service
        // converts to full-width (０–９) in handleCommit because the
        // editor context is always Japanese-output.
        KeyboardRow(gap = gap) {
            ROW0.forEach { digit ->
                Key(tokens, shape, label = digit,
                    onClick = { dispatch(KeyAction.Commit(digit)) })
            }
        }
        KeyboardRow(gap = gap) {
            ROW1.forEach { rawJamo ->
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
        }
        KeyboardRow(gap = gap) {
            JamoSpacer(weight = 0.5f)
            ROW2.forEach { rawJamo ->
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
            JamoSpacer(weight = 0.5f)
        }
        KeyboardRow(gap = gap) {
            Key(tokens, shape, weight = 1.4f, fn = true, pressed = shifted,
                onClick = {
                    shifted = !shifted
                    onAction(KeyAction.Shift)
                }) {
                ShiftIcon(color = tokens.inkSoft)
            }
            ROW3.forEach { rawJamo ->
                val displayed = if (shifted) shiftedJamo(rawJamo) else rawJamo
                Key(tokens, shape, label = displayed,
                    onClick = { dispatch(KeyAction.Commit(displayed)) })
            }
            BackspaceKey(
                tokens = tokens,
                shape = shape,
                onTriggerBackspace = { dispatch(KeyAction.Backspace) },
            )
        }
        KeyboardRow(gap = gap) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "!#1",
                onClick = { onShowSymbols() })
            Key(tokens, shape, weight = 1.3f, fn = true,
                onClick = { dispatch(KeyAction.SwitchIme) }) {
                GlobeIcon(color = tokens.inkSoft)
            }
            Key(tokens, shape, weight = 0.7f, fn = true, label = "、",
                onClick = { dispatch(KeyAction.Commit("、")) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                onClick = { dispatch(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = "。",
                onClick = { dispatch(KeyAction.Commit("。")) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { dispatch(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

@Composable
private fun BeolsikSymbols(
    tokens: KeyboardTokens,
    shape: KeyShape,
    rows: List<List<String>>,
    otherPageLabel: String,
    onAction: (KeyAction) -> Unit,
    onShowLetters: () -> Unit,
    onCyclePage: () -> Unit,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        // Top number row — kept on symbol pages too so the user doesn't
        // have to hop back to letters just to type a digit.  Per spec:
        // "두벌식의 경우 ... 맨윗줄에 숫자를 1부터 0까지" applies to both
        // letters and symbol pages.
        KeyboardRow(gap = gap) {
            ROW0.forEach { digit ->
                Key(tokens, shape, label = digit,
                    onClick = { onAction(KeyAction.Commit(digit)) })
            }
        }
        // Rows 0 + 1 — full 10-col rows of symbols.
        KeyboardRow(gap = gap) {
            rows[0].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
        }
        KeyboardRow(gap = gap) {
            rows[1].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
        }
        // Row 2 — page-cycle on the left, the row's chars (~7), Backspace on the right.
        KeyboardRow(gap = gap) {
            Key(tokens, shape, weight = 1.4f, fn = true, label = otherPageLabel,
                onClick = { onCyclePage() })
            rows[2].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
            BackspaceKey(
                tokens = tokens,
                shape = shape,
                onTriggerBackspace = { onAction(KeyAction.Backspace) },
            )
        }
        // Bottom row — `한` returns to letters; rest mirrors letters bottom.
        KeyboardRow(gap = gap) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "한",
                onClick = { onShowLetters() })
            Key(tokens, shape, weight = 1.3f, fn = true,
                onClick = { onAction(KeyAction.SwitchIme) }) {
                GlobeIcon(color = tokens.inkSoft)
            }
            Key(tokens, shape, weight = 0.7f, fn = true, label = "、",
                onClick = { onAction(KeyAction.Commit("、")) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                onClick = { onAction(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = "。",
                onClick = { onAction(KeyAction.Commit("。")) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { onAction(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

@Composable
private fun ColumnScope.KeyboardRow(
    gap: Int,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
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
            .let { if (onClick != null) it.clickableWithKeyboardHaptic(onClick = onClick) else it },
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
