package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
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

@Composable
fun BeolsikLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

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
            ROW1.forEach { Key(tokens, shape, label = it) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JamoSpacer(weight = 0.5f)
            ROW2.forEach { Key(tokens, shape, label = it) }
            JamoSpacer(weight = 0.5f)
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(tokens, shape, weight = 1.4f, fn = true) {
                ShiftIcon(color = tokens.inkSoft)
            }
            ROW3.forEach { Key(tokens, shape, label = it) }
            Key(tokens, shape, weight = 1.4f, fn = true) {
                BackspaceIcon(color = tokens.inkSoft)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "!#1")
            Key(tokens, shape, weight = 1.3f, fn = true) {
                GlobeIcon(color = tokens.inkSoft)
            }
            Key(tokens, shape, weight = 0.7f, fn = true, label = ",")
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f)
            Key(tokens, shape, weight = 0.7f, fn = true, label = ".")
            Key(tokens, shape, weight = 1.3f, accent = true) {
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
            },
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
