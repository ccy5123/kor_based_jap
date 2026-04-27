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
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

private val ROW0 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val ROW1 = listOf("ㅂ", "ㅈ", "ㄷ", "ㄱ", "ㅅ", "ㅛ", "ㅕ", "ㅑ", "ㅐ", "ㅔ")
private val ROW2 = listOf("ㅁ", "ㄴ", "ㅇ", "ㄹ", "ㅎ", "ㅗ", "ㅓ", "ㅏ", "ㅣ")
private val ROW3 = listOf("ㅋ", "ㅌ", "ㅊ", "ㅍ", "ㅠ", "ㅜ", "ㅡ")

/**
 * Symbol pages for the 두벌식 layout — surfaced when the user taps `!#1`
 * from letters and cycled via the page-toggle key (`1`/`2`).  Content
 * varies by [InputLanguage]:
 *
 *  - **KOREAN / ENGLISH**: ASCII punctuation (`! @ # ₩ , ?` etc.) and
 *    `[ ]` brackets — what Korean / English typists expect.
 *  - **JAPANESE**: full-width punctuation (`！ 、 ？`) and `「 」` brackets,
 *    `¥` instead of `₩`, plus `← →` cursor-movement keys flanking Space
 *    on the bottom row (per the user's "스페이스바 양옆 커서 이동" spec).
 *
 * Each row is `List<String>` (not `String`) so multi-codepoint glyphs
 * with variation selectors (e.g. `▪︎` = U+25AA + U+FE0E) stay grouped as
 * one tappable key.  Picked by [symbolPagesFor] / [symbolBottomFor] at
 * render time so toggling 한/영/일 swaps content without re-mounting.
 */
private val KOR_SYM_PAGE_1: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]"),
    listOf("!", "@", "#", "₩", "%", "^", "&", "*", "(", ")"),
    listOf("-", "'", "\"", ":", ";", ",", "?"),
)

private val KOR_SYM_PAGE_2: List<List<String>> = listOf(
    listOf("`", "~", "\\", "|", "{", "}", "€", "£", "¥", "$"),
    listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧"),
    listOf("☆", "▪︎", "¤", "《", "》", "¡", "¿"),
)

private val JPN_SYM_PAGE_1: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "/", "_", "<", ">", "「", "」"),
    listOf("！", "@", "#", "¥", "%", "^", "&", "*", "(", ")"),
    listOf("-", "'", "\"", ":", ";", "、", "？"),
)

private val JPN_SYM_PAGE_2: List<List<String>> = listOf(
    listOf("`", "~", "\\", "|", "{", "}", "€", "£", "$", "₩"),
    listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧"),
    listOf("☆", "▪︎", "¤", "《", "》", "¡", "¿"),
)

// English uses ASCII like Korean but swaps the prominent currency: $ on
// the easy-reach P1 row 2 (same slot Korean uses for ₩), and ¥ + ₩ tucked
// at the end of P2 row 1 (where Korean has ¥ + $).  Decorative pages
// mirror Korean / Japanese exactly.
private val ENG_SYM_PAGE_1: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "/", "_", "<", ">", "[", "]"),
    listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
    listOf("-", "'", "\"", ":", ";", ",", "?"),
)

private val ENG_SYM_PAGE_2: List<List<String>> = listOf(
    listOf("`", "~", "\\", "|", "{", "}", "€", "£", "¥", "₩"),
    listOf("°", "•", "○", "●", "□", "■", "♤", "♡", "◇", "♧"),
    listOf("☆", "▪︎", "¤", "《", "》", "¡", "¿"),
)

/**
 * Returns `[page1, page2]` for the current language.  Internal so
 * [QwertyLayout] can reuse the same routing in ENGLISH mode.  Three
 * disjoint sets so each language's primary currency sits on the easy-
 * reach slot (₩ for KOR, $ for ENG, ¥ for JPN).
 */
internal fun beolsikSymbolPagesFor(lang: InputLanguage): List<List<List<String>>> =
    when (lang) {
        InputLanguage.JAPANESE -> listOf(JPN_SYM_PAGE_1, JPN_SYM_PAGE_2)
        InputLanguage.ENGLISH -> listOf(ENG_SYM_PAGE_1, ENG_SYM_PAGE_2)
        InputLanguage.KOREAN -> listOf(KOR_SYM_PAGE_1, KOR_SYM_PAGE_2)
    }

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
    inputLanguage: InputLanguage = InputLanguage.JAPANESE,
    onLanguageCycle: () -> Unit = {},
) {
    var page by remember { mutableStateOf(BeolsikPage.LETTERS) }
    val pages = beolsikSymbolPagesFor(inputLanguage)

    when (page) {
        BeolsikPage.LETTERS -> BeolsikLetters(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            onShowSymbols = { page = BeolsikPage.SYMBOLS_1 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
        BeolsikPage.SYMBOLS_1 -> BeolsikSymbols(
            tokens = tokens,
            shape = shape,
            rows = pages[0],
            otherPageLabel = "2/2",
            onAction = onAction,
            onShowLetters = { page = BeolsikPage.LETTERS },
            onCyclePage = { page = BeolsikPage.SYMBOLS_2 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
        BeolsikPage.SYMBOLS_2 -> BeolsikSymbols(
            tokens = tokens,
            shape = shape,
            rows = pages[1],
            otherPageLabel = "1/2",
            onAction = onAction,
            onShowLetters = { page = BeolsikPage.LETTERS },
            onCyclePage = { page = BeolsikPage.SYMBOLS_1 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
    }
}

@Composable
private fun BeolsikLetters(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    onShowSymbols: () -> Unit,
    inputLanguage: InputLanguage,
    onLanguageCycle: () -> Unit,
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
            // Language cycle (한 → 영 → 일 → 한).  Long-press still opens the
            // system IME picker so users keep that escape hatch.
            Key(
                tokens, shape, weight = 1.3f, fn = true,
                label = langCycleLabelFor(inputLanguage),
                onClick = onLanguageCycle,
                onLongPress = { dispatch(KeyAction.SwitchIme) },
            )
            // Korean / English use ASCII , .  ; Japanese uses 、 。 .
            val (left, right) = letterPunctFor(inputLanguage)
            Key(tokens, shape, weight = 0.7f, fn = true, label = left,
                onClick = { dispatch(KeyAction.Commit(left)) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                label = spaceLabelFor(inputLanguage),
                onClick = { dispatch(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = right,
                onClick = { dispatch(KeyAction.Commit(right)) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { dispatch(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

/** Bottom-row left/right punct adjacent to Space — half-width for KOR/ENG, full-width for JPN. */
private fun letterPunctFor(lang: InputLanguage): Pair<String, String> =
    if (lang == InputLanguage.JAPANESE) "、" to "。" else "," to "."

@Composable
private fun BeolsikSymbols(
    tokens: KeyboardTokens,
    shape: KeyShape,
    rows: List<List<String>>,
    otherPageLabel: String,
    onAction: (KeyAction) -> Unit,
    onShowLetters: () -> Unit,
    onCyclePage: () -> Unit,
    inputLanguage: InputLanguage,
    onLanguageCycle: () -> Unit,
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
        // Japanese mode adds ← / → cursor-movement keys flanking Space and
        // moves 전각 ，． to single cells outside them, per the user's
        // "스페이스바 양옆 커서 이동 한칸 짬내서 전각,." spec.
        if (inputLanguage == InputLanguage.JAPANESE) {
            KeyboardRow(gap = gap) {
                Key(tokens, shape, weight = 1.2f, fn = true, label = "한",
                    onClick = { onShowLetters() })
                Key(tokens, shape, weight = 1.0f, fn = true,
                    label = langCycleLabelFor(inputLanguage),
                    onClick = onLanguageCycle,
                    onLongPress = { onAction(KeyAction.SwitchIme) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = "，",
                    onClick = { onAction(KeyAction.Commit("，")) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = "←",
                    onClick = { onAction(KeyAction.CursorLeft) })
                SpaceKey(tokens = tokens, shape = shape, weight = 2.4f,
                    label = spaceLabelFor(inputLanguage),
                    onClick = { onAction(KeyAction.Space) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = "→",
                    onClick = { onAction(KeyAction.CursorRight) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = "．",
                    onClick = { onAction(KeyAction.Commit("．")) })
                Key(tokens, shape, weight = 1.2f, accent = true,
                    onClick = { onAction(KeyAction.Enter) }) {
                    EnterIcon(color = tokens.onAccent)
                }
            }
        } else {
            val (left, right) = letterPunctFor(inputLanguage)
            KeyboardRow(gap = gap) {
                Key(tokens, shape, weight = 1.3f, fn = true, label = "한",
                    onClick = { onShowLetters() })
                Key(tokens, shape, weight = 1.3f, fn = true,
                    label = langCycleLabelFor(inputLanguage),
                    onClick = onLanguageCycle,
                    onLongPress = { onAction(KeyAction.SwitchIme) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = left,
                    onClick = { onAction(KeyAction.Commit(left)) })
                SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                    label = spaceLabelFor(inputLanguage),
                    onClick = { onAction(KeyAction.Space) })
                Key(tokens, shape, weight = 0.7f, fn = true, label = right,
                    onClick = { onAction(KeyAction.Commit(right)) })
                Key(tokens, shape, weight = 1.3f, accent = true,
                    onClick = { onAction(KeyAction.Enter) }) {
                    EnterIcon(color = tokens.onAccent)
                }
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
    label: String = "한국어",
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
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = tokens.inkSoft,
        )
    }
}

/**
 * Space-bar label per input-language mode — shown in the centre of the
 * SpaceKey so the user always sees which language they're typing in.
 *  - KOREAN  → "한국어"
 *  - ENGLISH → "Eng."
 *  - JAPANESE → "日本語"
 */
internal fun spaceLabelFor(lang: io.github.ccy5123.korjpnime.theme.InputLanguage): String =
    when (lang) {
        io.github.ccy5123.korjpnime.theme.InputLanguage.KOREAN -> "한국어"
        io.github.ccy5123.korjpnime.theme.InputLanguage.ENGLISH -> "Eng."
        io.github.ccy5123.korjpnime.theme.InputLanguage.JAPANESE -> "日本語"
    }

/**
 * Short label for the 한/영/일 cycle button — shows the NEXT language a
 * tap will switch to (the SpaceKey already shows the current one, so
 * splitting the responsibility avoids redundancy).  Cycle: KOREAN →
 * ENGLISH → JAPANESE → KOREAN.
 */
internal fun langCycleLabelFor(lang: io.github.ccy5123.korjpnime.theme.InputLanguage): String =
    when (lang) {
        io.github.ccy5123.korjpnime.theme.InputLanguage.KOREAN -> "En"
        io.github.ccy5123.korjpnime.theme.InputLanguage.ENGLISH -> "日"
        io.github.ccy5123.korjpnime.theme.InputLanguage.JAPANESE -> "한"
    }
