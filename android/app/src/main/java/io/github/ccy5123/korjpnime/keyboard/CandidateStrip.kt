package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.theme.KeyboardTokens
import io.github.ccy5123.korjpnime.theme.StripTreatment

private val SAMPLES = listOf("こんにちは", "今日は", "コンニチハ", "今日輪")

@Composable
fun CandidateStrip(
    tokens: KeyboardTokens,
    treatment: StripTreatment,
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .height(36.dp)
        .background(tokens.strip)

    val withBorder = if (treatment == StripTreatment.HAIRLINE) {
        baseModifier.drawBehind {
            drawLine(
                color = tokens.hairline,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
    } else baseModifier

    Row(
        modifier = withBorder.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (treatment == StripTreatment.CHIP)
            Arrangement.spacedBy(6.dp) else Arrangement.Start,
    ) {
        SAMPLES.forEachIndexed { i, c ->
            val selected = i == 0
            when (treatment) {
                StripTreatment.CHIP -> ChipCandidate(c, selected, tokens)
                StripTreatment.UNDERLINE -> UnderlineCandidate(c, selected, tokens)
                StripTreatment.HAIRLINE -> {
                    HairlineCandidate(c, selected, tokens)
                    if (i < SAMPLES.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(14.dp)
                                .background(tokens.hairline),
                        )
                    }
                }
                StripTreatment.FLUSH -> FlushCandidate(c, selected, tokens)
            }
        }
        Box(modifier = Modifier.weight(1f).height(1.dp))
        ChipChevron(color = tokens.inkSoft)
    }
}

@Composable
private fun ChipCandidate(text: String, selected: Boolean, tokens: KeyboardTokens) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) tokens.accent else tokens.keyAlt)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) tokens.onAccent else tokens.ink,
        )
    }
}

@Composable
private fun UnderlineCandidate(text: String, selected: Boolean, tokens: KeyboardTokens) {
    Box(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.accent else tokens.ink,
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(tokens.accent, shape = RoundedCornerShape(2.dp))
                    .align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun HairlineCandidate(text: String, selected: Boolean, tokens: KeyboardTokens) {
    Box(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) tokens.ink else tokens.inkSoft,
        )
    }
}

@Composable
private fun FlushCandidate(text: String, selected: Boolean, tokens: KeyboardTokens) {
    Column(
        modifier = Modifier.padding(horizontal = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = tokens.ink,
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (selected) tokens.accent else Color.Transparent),
        )
    }
}
