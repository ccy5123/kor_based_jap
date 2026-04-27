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
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

// 4 rows × 4 cols. Cells are either:
//  - String  → label (vowel or special label like "!#1")
//  - Pair<String,String> → multi-tap consonant group (renders both, primary on bottom)
//  - SpecialKey → backspace / enter / globe / space
private sealed class CjCell {
    data class Vowel(val label: String) : CjCell()
    data class ConsonantGroup(val primary: String, val secondary: String) : CjCell()
    data class Plain(val label: String) : CjCell()
    object Backspace : CjCell()
    object Enter : CjCell()
    object Globe : CjCell()
    object Space : CjCell()
}

private val GRID: List<List<CjCell>> = listOf(
    listOf(
        CjCell.Vowel("ㅣ"),
        CjCell.Vowel("ㆍ"),
        CjCell.Vowel("ㅡ"),
        CjCell.Backspace,
    ),
    listOf(
        CjCell.ConsonantGroup("ㄱ", "ㅋ"),
        CjCell.ConsonantGroup("ㄴ", "ㄹ"),
        CjCell.ConsonantGroup("ㄷ", "ㅌ"),
        CjCell.Enter,
    ),
    listOf(
        CjCell.ConsonantGroup("ㅂ", "ㅍ"),
        CjCell.ConsonantGroup("ㅅ", "ㅎ"),
        CjCell.ConsonantGroup("ㅈ", "ㅊ"),
        CjCell.Plain(".,?!"),
    ),
    listOf(
        CjCell.Plain("!#1"),
        CjCell.ConsonantGroup("ㅇ", "ㅁ"),
        CjCell.Space,
        CjCell.Globe,
    ),
)

@Composable
fun CheonjiinLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
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
                        is CjCell.Vowel -> Key(tokens, shape, label = cell.label)
                        is CjCell.ConsonantGroup -> Key(
                            tokens, shape,
                            double = cell.primary to cell.secondary,
                        )
                        is CjCell.Plain -> Key(tokens, shape, fn = true, label = cell.label)
                        CjCell.Backspace -> Key(tokens, shape, fn = true) {
                            BackspaceIcon(color = tokens.inkSoft)
                        }
                        CjCell.Enter -> Key(tokens, shape, fn = true) {
                            EnterIcon(color = tokens.inkSoft)
                        }
                        CjCell.Globe -> Key(tokens, shape, fn = true) {
                            GlobeIcon(color = tokens.inkSoft)
                        }
                        CjCell.Space -> SpaceKey(tokens = tokens, shape = shape, weight = 1f)
                    }
                }
            }
        }
    }
}
