package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
        CjCell.Punct(listOf('.', ',', '?', '!')),
    ),
    listOf(
        CjCell.Plain("!#1"),
        CjCell.ConsonantGroup(CYCLES.getValue('ㅇ')),
        CjCell.Space,
        CjCell.Globe,
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
                        is CjCell.Vowel -> Key(
                            tokens, shape, label = cell.stroke.toString(),
                            onClick = { onAction(KeyAction.CjVowel(cell.stroke)) },
                        )
                        is CjCell.ConsonantGroup -> Key(
                            tokens, shape,
                            double = cell.displayPrimary to cell.displaySecondary,
                            onClick = { onAction(KeyAction.CjConsonant(cell.cycle)) },
                        )
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
                    }
                }
            }
        }
    }
}
