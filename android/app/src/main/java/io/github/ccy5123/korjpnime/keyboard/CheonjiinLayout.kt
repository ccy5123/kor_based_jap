package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.engine.CheonjiinComposer
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

// 4 rows × 4 cols.  ConsonantGroup carries its full multi-tap cycle (e.g.,
// [ㄱ, ㅋ, ㄲ]); the layout shows the first two on the key face but the
// service-side state machine (CheonjiinComposer) walks the full cycle.
private sealed class CjCell {
    data class Vowel(val stroke: Char) : CjCell()
    data class ConsonantGroup(val cycle: List<Char>) : CjCell() {
        val displayPrimary: String get() = cycle[0].toString()
        val displaySecondary: String get() = cycle.getOrNull(1)?.toString() ?: ""
    }
    /** Multi-tap punctuation cycle — typically `.`, `,`, `?`, `!`. */
    data class Punct(val cycle: List<Char>) : CjCell() {
        val display: String get() = cycle.joinToString("")
    }
    /**
     * Split bottom-left cell.  Left half opens the symbols / numeric page;
     * right half cycles foreign-language layouts (한 ↔ 영, long-press =
     * system IME picker).  Visually one grid cell, internally two halves
     * with `weight = 1f` each inside a `weight = 1f` parent.
     */
    object SymbolLangSplit : CjCell()
    /**
     * Trigger kanji conversion on the current run.  Placeholder for now —
     * key surfaces but the conversion wiring lands in a later slice.
     */
    object Hanja : CjCell()
    object Backspace : CjCell()
    object Enter : CjCell()
    object Space : CjCell()
}

// Pull cycles from the composer's authoritative table so the keypad and the
// state machine never drift.
private val CYCLES = CheonjiinComposer.CONSONANT_CYCLES

/**
 * Phone-dialpad style digit overlay — long-press on each letter cell
 * produces the corresponding digit.  ㅇㅁ at "0" mirrors the round
 * shape of the digit; ㅣ ㆍ ㅡ map to the top row 1 / 2 / 3.  Service-
 * side `digitsToFullWidth` then converts to ０..９ for the editor.
 */
private val DIGIT_BY_PRIMARY: Map<Char, String> = mapOf(
    'ㅣ' to "1", 'ㆍ' to "2", 'ㅡ' to "3",
    'ㄱ' to "4", 'ㄴ' to "5", 'ㄷ' to "6",
    'ㅂ' to "7", 'ㅅ' to "8", 'ㅈ' to "9",
    'ㅇ' to "0",
)

/**
 * Letters-page punctuation cycle (row 3 col 4) — language-aware.
 *  - JAPANESE: 句点 → 読点 → 全角？ → 全角！  (standard JP 4-cycle).
 *  - KOREAN / ENGLISH: ASCII counterparts so Korean Cheonjiin doesn't
 *    silently emit Japanese full-width punct when the user's mode is
 *    Korean output.
 */
private fun lettersPunctCycleFor(lang: InputLanguage): List<Char> =
    if (lang == InputLanguage.JAPANESE)
        listOf('。', '、', '？', '！')
    else
        listOf('.', ',', '?', '!')

private fun lettersGridFor(lang: InputLanguage): List<List<CjCell>> = listOf(
    listOf(
        CjCell.Vowel(CheonjiinComposer.STROKE_I),
        CjCell.Vowel(CheonjiinComposer.STROKE_DOT),
        CjCell.Vowel(CheonjiinComposer.STROKE_EU),
        CjCell.Backspace,
    ),
    listOf(
        CjCell.ConsonantGroup(CYCLES.getValue('ㄱ')),
        CjCell.ConsonantGroup(CYCLES.getValue('ㄴ')),
        CjCell.ConsonantGroup(CYCLES.getValue('ㄷ')),
        CjCell.Enter,
    ),
    listOf(
        CjCell.ConsonantGroup(CYCLES.getValue('ㅂ')),
        CjCell.ConsonantGroup(CYCLES.getValue('ㅅ')),
        CjCell.ConsonantGroup(CYCLES.getValue('ㅈ')),
        CjCell.Punct(lettersPunctCycleFor(lang)),
    ),
    listOf(
        CjCell.SymbolLangSplit,
        CjCell.ConsonantGroup(CYCLES.getValue('ㅇ')),
        CjCell.Space,
        CjCell.Hanja,
    ),
)

/**
 * Numeric-page punctuation cycle on row 3 col 4.  ASCII counterpart of the
 * letters page's 句点・読点 cycle — for when the user is typing numbers /
 * URL-ish text and wants ASCII punct without a page hop.
 */
private val NUMERIC_PUNCT_CYCLE = listOf('.', ',', '-', '/')

/**
 * Common quick-access symbol on numeric / symbol pages, row 4 col 4
 * (replaces 한자 from the letters page since hanja conversion isn't
 * applicable here).
 */
private const val FREQ_SYMBOL = "?"

/**
 * Symbol pages — 3 rows × 6 symbols per page.  Each row's rightmost
 * column on the rendered grid is filled by the numeric page's utility
 * column (back / enter / punct cycle), so users can issue those actions
 * without page-hopping.  Content varies by [InputLanguage]: KOREAN /
 * ENGLISH use ASCII punctuation and `[]` brackets / `₩` won; JAPANESE
 * substitutes `! ? . , ( )` with full-width `！ ？ 、 。 「 」` and
 * `₩` with `¥` — derived from the Beolsik Korean / Japanese spec since
 * "천지인 <- 두벌식" carries the same substitution rules over.
 *
 * Each row is `List<String>` (not `String`) so multi-codepoint glyphs
 * with variation selectors stay grouped as one tappable key.
 */
private val KOR_CJ_SYM_1: List<List<String>> = listOf(
    listOf("!", "?", ".", ",", "(", ")"),
    listOf("@", ":", ";", "/", "-", "♡"),
    listOf("*", "_", "%", "~", "^", "#"),
)

private val KOR_CJ_SYM_2: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "\"", "'"),
    listOf("&", "♤", "☆", "♧", "\\", "₩"),
    listOf("<", ">", "{", "}", "[", "]"),
)

private val KOR_CJ_SYM_3: List<List<String>> = listOf(
    listOf("`", "|", "$", "€", "£", "¥"),
    listOf("°", "○", "●", "□", "■", "◇"),
    listOf("※", "《", "》", "¤", "¡", "¿"),
)

private val JPN_CJ_SYM_1: List<List<String>> = listOf(
    listOf("！", "？", "、", "。", "「", "」"),
    listOf("@", ":", ";", "/", "-", "♡"),
    listOf("*", "_", "%", "~", "^", "#"),
)

private val JPN_CJ_SYM_2: List<List<String>> = listOf(
    listOf("+", "×", "÷", "=", "\"", "'"),
    listOf("&", "♤", "☆", "♧", "\\", "¥"),
    listOf("<", ">", "{", "}", "[", "]"),
)

// Page 3 (decorative + currency) is identical across languages.
private val CJ_SYM_3: List<List<String>> = KOR_CJ_SYM_3

/** Returns `[page1, page2, page3]` for the current language. */
private fun cjSymbolPagesFor(lang: InputLanguage): List<List<List<String>>> =
    if (lang == InputLanguage.JAPANESE)
        listOf(JPN_CJ_SYM_1, JPN_CJ_SYM_2, CJ_SYM_3)
    else
        listOf(KOR_CJ_SYM_1, KOR_CJ_SYM_2, CJ_SYM_3)

/**
 * Cheonjiin pages — accessed via the bottom-left split cell:
 *  - LETTERS: 4×4 jamo keypad (default)
 *  - NUMERIC: 4×4 phone-dialpad digits 1–9 / 0
 *  - SYM_1 / SYM_2 / SYM_3: 7×4 symbol grids (6 symbols + utility column)
 *
 * Cycle from the bottom-left split-left half: NUMERIC → SYM_1 → SYM_2 →
 * SYM_3 → NUMERIC.  Right half always returns to LETTERS.
 */
private enum class CjPage { LETTERS, NUMERIC, SYM_1, SYM_2, SYM_3 }

@Composable
fun CheonjiinLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit = {},
    inputLanguage: InputLanguage = InputLanguage.JAPANESE,
    onLanguageCycle: () -> Unit = {},
) {
    var page by remember { mutableStateOf(CjPage.LETTERS) }
    val symPages = cjSymbolPagesFor(inputLanguage)
    when (page) {
        CjPage.LETTERS -> CjLetters(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            onShowNumeric = { page = CjPage.NUMERIC },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
        CjPage.NUMERIC -> CjNumeric(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            onCyclePage = { page = CjPage.SYM_1 },
            onBackToLetters = { page = CjPage.LETTERS },
            inputLanguage = inputLanguage,
        )
        // Symbol pages: page-nav cycles 1/3 → 2/3 → 3/3 → 1/3 (no longer
        // visits numeric).  Numeric page is reachable via the dedicated
        // "123" key in the bottom-left split on every symbol page.
        CjPage.SYM_1 -> CjSymbols(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            rows = symPages[0],
            currentPageLabel = "1/3",
            onCyclePage = { page = CjPage.SYM_2 },
            onBackToNumeric = { page = CjPage.NUMERIC },
            onBackToLetters = { page = CjPage.LETTERS },
            inputLanguage = inputLanguage,
        )
        CjPage.SYM_2 -> CjSymbols(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            rows = symPages[1],
            currentPageLabel = "2/3",
            onCyclePage = { page = CjPage.SYM_3 },
            onBackToNumeric = { page = CjPage.NUMERIC },
            onBackToLetters = { page = CjPage.LETTERS },
            inputLanguage = inputLanguage,
        )
        CjPage.SYM_3 -> CjSymbols(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            rows = symPages[2],
            currentPageLabel = "3/3",
            onCyclePage = { page = CjPage.SYM_1 },
            onBackToNumeric = { page = CjPage.NUMERIC },
            onBackToLetters = { page = CjPage.LETTERS },
            inputLanguage = inputLanguage,
        )
    }
}

@Composable
private fun CjLetters(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    onShowNumeric: () -> Unit,
    inputLanguage: InputLanguage,
    onLanguageCycle: () -> Unit,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 5
    val pad = if (shape == KeyShape.FLAT) 0 else 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        lettersGridFor(inputLanguage).forEach { row ->
            CjRow(gap = gap) {
                row.forEach { cell ->
                    when (cell) {
                        is CjCell.Vowel -> {
                            val digit = DIGIT_BY_PRIMARY[cell.stroke]
                            Key(
                                tokens, shape, label = cell.stroke.toString(),
                                cornerHint = digit,
                                onClick = { onAction(KeyAction.CjVowel(cell.stroke)) },
                                onLongPress = digit?.let { d ->
                                    { onAction(KeyAction.Commit(d)) }
                                },
                            )
                        }
                        is CjCell.ConsonantGroup -> {
                            val digit = DIGIT_BY_PRIMARY[cell.cycle.first()]
                            Key(
                                tokens, shape,
                                double = cell.displayPrimary to cell.displaySecondary,
                                cornerHint = digit,
                                onClick = { onAction(KeyAction.CjConsonant(cell.cycle)) },
                                onLongPress = digit?.let { d ->
                                    { onAction(KeyAction.Commit(d)) }
                                },
                            )
                        }
                        is CjCell.Punct -> Key(
                            tokens, shape, fn = true, label = cell.display,
                            onClick = { onAction(KeyAction.CjPunct(cell.cycle)) },
                        )
                        CjCell.Backspace -> BackspaceKey(
                            tokens = tokens, shape = shape, weight = 1f,
                            onTriggerBackspace = { onAction(KeyAction.Backspace) },
                        )
                        CjCell.Enter -> Key(
                            tokens, shape, fn = true,
                            onClick = { onAction(KeyAction.Enter) },
                        ) { EnterIcon(color = tokens.inkSoft) }
                        CjCell.Space -> {
                            // Japanese mode splits the space slot 1/3 ▶ +
                            // 2/3 space (▶ for cursor / future bunsetsu
                            // extend).  Korean mode keeps the full-width
                            // space — the user said "한국어일 때는 필요
                            // 없어 일본어일 때만 있으면 된다".
                            if (inputLanguage == InputLanguage.JAPANESE) {
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    horizontalArrangement = Arrangement.spacedBy(gap.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Key(tokens, shape, fn = true, label = "▶",
                                        onClick = { onAction(KeyAction.CursorRight) })
                                    SpaceKey(tokens = tokens, shape = shape, weight = 2f,
                                        label = spaceLabelFor(inputLanguage),
                                        onClick = { onAction(KeyAction.Space) })
                                }
                            } else {
                                SpaceKey(
                                    tokens = tokens, shape = shape, weight = 1f,
                                    label = spaceLabelFor(inputLanguage),
                                    onClick = { onAction(KeyAction.Space) },
                                )
                            }
                        }
                        CjCell.SymbolLangSplit -> {
                            // Japanese mode: 3-way !#1 | lang | ◀ (◀ for
                            // cursor / future bunsetsu shrink).  Korean
                            // mode: 2-way !#1 | lang only (no ◀ — same
                            // reasoning as the ▶ case above).
                            if (inputLanguage == InputLanguage.JAPANESE) {
                                Row(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    horizontalArrangement = Arrangement.spacedBy(gap.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Key(tokens, shape, fn = true, label = "!#1",
                                        onClick = { onShowNumeric() })
                                    Key(
                                        tokens, shape, fn = true,
                                        label = langCycleLabelFor(inputLanguage),
                                        onClick = onLanguageCycle,
                                        onLongPress = { onAction(KeyAction.SwitchIme) },
                                    )
                                    Key(tokens, shape, fn = true, label = "◀",
                                        onClick = { onAction(KeyAction.CursorLeft) })
                                }
                            } else {
                                SplitNavKey(
                                    tokens = tokens, shape = shape, gap = gap,
                                    leftLabel = "!#1",
                                    onLeftClick = onShowNumeric,
                                    rightLabel = langCycleLabelFor(inputLanguage),
                                    onRightClick = onLanguageCycle,
                                    onRightLongPress = { onAction(KeyAction.SwitchIme) },
                                )
                            }
                        }
                        CjCell.Hanja -> {
                            // Same physical slot, language-aware semantics:
                            //   - KOR: "한자" → single-syllable Hangul→Hanja
                            //   - JPN: "再変換" → reconvert the most recent
                            //     kanji pick (re-surfaces the candidate strip
                            //     so the user can swap to a different kanji
                            //     for the same reading).
                            //   - ENG: ENG never renders Cheonjiin (forced
                            //     QWERTY at KeyboardSurface), so this branch
                            //     can only be hit if a future direction
                            //     surfaces it — fall back to "한자" as KOR.
                            val (label, action) = when (inputLanguage) {
                                InputLanguage.JAPANESE -> "再変換" to KeyAction.Reconvert
                                else -> "한자" to KeyAction.Hanja
                            }
                            Key(tokens, shape, fn = true, label = label,
                                onClick = { onAction(action) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Numeric panel — 4×4 phone-dialpad layout.  Bottom-left split: left half
 * cycles to symbol page 1, right half jumps back to letters (한).  Other
 * action keys (Backspace / Enter / Space / Punct cycle) keep their letter-
 * page positions for muscle memory.
 */
@Composable
private fun CjNumeric(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    onCyclePage: () -> Unit,
    onBackToLetters: () -> Unit,
    inputLanguage: InputLanguage,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 5
    val pad = if (shape == KeyShape.FLAT) 0 else 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        CjRow(gap = gap) {
            DigitKey(tokens, shape, "1", onAction)
            DigitKey(tokens, shape, "2", onAction)
            DigitKey(tokens, shape, "3", onAction)
            BackspaceKey(
                tokens = tokens, shape = shape, weight = 1f,
                onTriggerBackspace = { onAction(KeyAction.Backspace) },
            )
        }
        CjRow(gap = gap) {
            DigitKey(tokens, shape, "4", onAction)
            DigitKey(tokens, shape, "5", onAction)
            DigitKey(tokens, shape, "6", onAction)
            Key(
                tokens, shape, fn = true,
                onClick = { onAction(KeyAction.Enter) },
            ) { EnterIcon(color = tokens.inkSoft) }
        }
        CjRow(gap = gap) {
            DigitKey(tokens, shape, "7", onAction)
            DigitKey(tokens, shape, "8", onAction)
            DigitKey(tokens, shape, "9", onAction)
            Key(
                tokens, shape, fn = true, label = NUMERIC_PUNCT_CYCLE.joinToString(""),
                onClick = { onAction(KeyAction.CjPunct(NUMERIC_PUNCT_CYCLE)) },
            )
        }
        CjRow(gap = gap) {
            // Numeric page: left = cycle to symbol page, right = back to letters.
            SplitNavKey(
                tokens = tokens, shape = shape, gap = gap,
                leftLabel = "!@#",
                onLeftClick = onCyclePage,
                rightLabel = "한",
                onRightClick = onBackToLetters,
            )
            DigitKey(tokens, shape, "0", onAction)
            SpaceKey(tokens = tokens, shape = shape, weight = 1f,
                label = spaceLabelFor(inputLanguage),
                onClick = { onAction(KeyAction.Space) })
            Key(tokens, shape, fn = true, label = FREQ_SYMBOL,
                onClick = { onAction(KeyAction.Commit(FREQ_SYMBOL)) })
        }
    }
}

/**
 * Symbol page — 7 cols × 4 rows.  Top 3 rows: 6 user-spec symbols + the
 * numeric page's utility column (back / enter / punct cycle).
 *
 * Bottom row layout (total weight = 7, aligning the 7-col rows above):
 *
 *   [split: 123 | 한] (weight 2)  — fixed left split, NOT page nav:
 *      • left half "123" → returns to numeric panel;
 *      • right half "한"  → returns to letters page.
 *   [split: <currentPageLabel> | space] (weight 4) — page nav lives
 *      *inside* the space slot per the user's "스페이스바를 반으로 잘라
 *      서 왼쪽에 할당하고 오른쪽은 원래 스페이스바" spec:
 *      • left half shows the current page (1/3, 2/3, 3/3) and a tap
 *        advances the symbol-page cycle (1/3 → 2/3 → 3/3 → 1/3 — does
 *        NOT visit numeric, which lives on the dedicated "123" key);
 *      • right half is the actual space bar (committing a literal space).
 *   [freq symbol] (weight 1)
 *
 * @param rows 3 rows × 6 symbols each (the page's content grid).
 * @param currentPageLabel Indicator of the *current* symbol page ("1/3"
 *   etc.).  Tapping the split-space's left half advances to the next page.
 */
@Composable
private fun CjSymbols(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    rows: List<List<String>>,
    currentPageLabel: String,
    onCyclePage: () -> Unit,
    onBackToNumeric: () -> Unit,
    onBackToLetters: () -> Unit,
    inputLanguage: InputLanguage,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 5
    val pad = if (shape == KeyShape.FLAT) 0 else 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        // Row 1: 6 symbols + Backspace (rightmost utility col, same as numeric).
        CjRow(gap = gap) {
            rows[0].forEach { sym -> SymbolKey(tokens, shape, sym, onAction) }
            BackspaceKey(
                tokens = tokens, shape = shape, weight = 1f,
                onTriggerBackspace = { onAction(KeyAction.Backspace) },
            )
        }
        // Row 2: 6 symbols + Enter.
        CjRow(gap = gap) {
            rows[1].forEach { sym -> SymbolKey(tokens, shape, sym, onAction) }
            Key(
                tokens, shape, fn = true,
                onClick = { onAction(KeyAction.Enter) },
            ) { EnterIcon(color = tokens.inkSoft) }
        }
        // Row 3: 6 symbols + ASCII punct cycle.
        CjRow(gap = gap) {
            rows[2].forEach { sym -> SymbolKey(tokens, shape, sym, onAction) }
            Key(
                tokens, shape, fn = true, label = NUMERIC_PUNCT_CYCLE.joinToString(""),
                onClick = { onAction(KeyAction.CjPunct(NUMERIC_PUNCT_CYCLE)) },
            )
        }
        // Row 4: 123/한 (weight 2) + page-nav/space split (weight 4) + freq sym (weight 1).
        CjRow(gap = gap) {
            SplitNavKey(
                tokens = tokens, shape = shape, gap = gap, weight = 2f,
                leftLabel = "123",
                onLeftClick = onBackToNumeric,
                rightLabel = "한",
                onRightClick = onBackToLetters,
            )
            // Inner Row weighted at 4 splits 50/50 into [page-nav | space].
            // The page-nav button gets a SpaceKey-style background instead of
            // a fn-key tint so it visually reads as "part of the space bar"
            // rather than a separate utility key.
            Row(
                modifier = Modifier.weight(4f).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(gap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Key(tokens, shape, fn = true, label = currentPageLabel,
                    onClick = onCyclePage)
                SpaceKey(tokens = tokens, shape = shape, weight = 1f,
                    label = spaceLabelFor(inputLanguage),
                    onClick = { onAction(KeyAction.Space) })
            }
            Key(tokens, shape, fn = true, label = FREQ_SYMBOL,
                onClick = { onAction(KeyAction.Commit(FREQ_SYMBOL)) })
        }
    }
}

@Composable
private fun RowScope.DigitKey(
    tokens: KeyboardTokens,
    shape: KeyShape,
    digit: String,
    onAction: (KeyAction) -> Unit,
) = Key(tokens, shape, label = digit, onClick = { onAction(KeyAction.Commit(digit)) })

@Composable
private fun RowScope.SymbolKey(
    tokens: KeyboardTokens,
    shape: KeyShape,
    sym: String,
    onAction: (KeyAction) -> Unit,
) = Key(tokens, shape, label = sym, onClick = { onAction(KeyAction.Commit(sym)) })

/**
 * One grid cell split into two half-keys.  Outer Row weights against the
 * keypad row by [weight]; each child Key weights against the inner Row at
 * `1f`, so they share the slot 50/50 regardless of column count.
 */
@Composable
private fun RowScope.SplitNavKey(
    tokens: KeyboardTokens,
    shape: KeyShape,
    gap: Int,
    weight: Float = 1f,
    leftLabel: String,
    onLeftClick: () -> Unit,
    rightLabel: String,
    onRightClick: () -> Unit,
    onRightLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.weight(weight).fillMaxHeight(),
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(tokens, shape, fn = true, label = leftLabel, onClick = onLeftClick)
        Key(
            tokens, shape, fn = true, label = rightLabel,
            onClick = onRightClick,
            onLongPress = onRightLongPress,
        )
    }
}

@Composable
private fun ColumnScope.CjRow(
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
