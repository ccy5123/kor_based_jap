package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.engine.CheonjiinComposer
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
    /** Symbols / numbers page — placeholder until M2 wires the page. */
    data class Plain(val label: String) : CjCell()
    object Backspace : CjCell()
    object Enter : CjCell()
    object Globe : CjCell()
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

private val GRID: List<List<CjCell>> = listOf(
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
        // 句点 → 読点 → 全角？ → 全角！ — standard Japanese 4-cycle.
        CjCell.Punct(listOf('。', '、', '？', '！')),
    ),
    listOf(
        CjCell.SymbolLangSplit,
        CjCell.ConsonantGroup(CYCLES.getValue('ㅇ')),
        CjCell.Space,
        CjCell.Hanja,
    ),
)

@Composable
fun CheonjiinLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit = {},
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 5
    val pad = if (shape == KeyShape.FLAT) 0 else 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        GRID.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.forEach { cell ->
                    when (cell) {
                        is CjCell.Vowel -> {
                            val digit = DIGIT_BY_PRIMARY[cell.stroke]
                            Key(
                                tokens, shape, label = cell.stroke.toString(),
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
                        is CjCell.Plain -> Key(
                            tokens, shape, fn = true, label = cell.label,
                            onClick = { onAction(KeyAction.Symbols) },
                        )
                        CjCell.Backspace -> BackspaceKey(
                            tokens = tokens, shape = shape, weight = 1f,
                            onTriggerBackspace = { onAction(KeyAction.Backspace) },
                        )
                        CjCell.Enter -> Key(
                            tokens, shape, fn = true,
                            onClick = { onAction(KeyAction.Enter) },
                        ) { EnterIcon(color = tokens.inkSoft) }
                        CjCell.Globe -> Key(
                            tokens, shape, fn = true,
                            onClick = { onAction(KeyAction.SwitchIme) },
                        ) { GlobeIcon(color = tokens.inkSoft) }
                        CjCell.Space -> SpaceKey(
                            tokens = tokens, shape = shape, weight = 1f,
                            onClick = { onAction(KeyAction.Space) },
                        )
                        CjCell.SymbolLangSplit -> {
                            // One grid slot, two half-keys.  Inner Row is
                            // weight=1f against the outer keypad row; each
                            // child Key is weight=1f against the inner Row,
                            // so they share the slot 50/50.
                            Row(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                horizontalArrangement = Arrangement.spacedBy(gap.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Key(
                                    tokens, shape, fn = true, label = "문자",
                                    onClick = { onAction(KeyAction.Symbols) },
                                )
                                Key(
                                    tokens, shape, fn = true, label = "한/영",
                                    // Tap = internal Korean ↔ English layout
                                    // toggle (wired in M4.B.5).  Long-press =
                                    // system IME picker so users keep that
                                    // escape hatch even with Globe removed.
                                    onClick = { /* TODO: lang toggle */ },
                                    onLongPress = { onAction(KeyAction.SwitchIme) },
                                )
                            }
                        }
                        CjCell.Hanja -> Key(
                            tokens, shape, fn = true, label = "한자",
                            // Trigger syllable-by-syllable kanji conversion
                            // on the current run.  Wired later — placeholder
                            // for now per the user's "일단 키만 놔두고
                            // 작동은 나중에" request.
                            onClick = { /* TODO: Hanja conversion */ },
                        )
                    }
                }
            }
        }
    }
}
