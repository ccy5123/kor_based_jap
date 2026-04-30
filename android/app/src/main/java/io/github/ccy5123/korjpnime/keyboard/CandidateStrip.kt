package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyboardTokens
import io.github.ccy5123.korjpnime.theme.StripTreatment

/**
 * Merged top-row strip: language badge on the left, candidates
 * (horizontally scrollable) in the middle, expand button + ⋯ menu
 * on the right.  Replaces the old separate TopChrome bar — the
 * combined row is 40 dp tall, 30 dp shorter than the previous chrome
 * + strip stack.  The lang badge and menu remain visible regardless
 * of whether any candidates are present (always-on chrome anchors).
 */
@Composable
fun CandidateStrip(
    tokens: KeyboardTokens,
    treatment: StripTreatment,
    candidates: List<String> = emptyList(),
    onPick: (String) -> Unit = {},
    onExpand: (() -> Unit)? = null,
    inputLanguage: InputLanguage = InputLanguage.JAPANESE,
    onSettingsClick: (() -> Unit)? = null,
    onSystemImeSettings: (() -> Unit)? = null,
    onClipboardClick: (() -> Unit)? = null,
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .height(40.dp)
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
        modifier = withBorder,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: language badge (always visible).
        Box(modifier = Modifier.padding(start = 10.dp, end = 8.dp)) {
            LanguageBadge(tokens = tokens, inputLanguage = inputLanguage)
        }
        // Middle: scrollable candidates.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (treatment == StripTreatment.CHIP)
                Arrangement.spacedBy(6.dp) else Arrangement.Start,
        ) {
            candidates.forEachIndexed { i, c ->
                val selected = i == 0
                val pickModifier = Modifier.clickable { onPick(c) }
                when (treatment) {
                    StripTreatment.CHIP -> ChipCandidate(c, selected, tokens, pickModifier)
                    StripTreatment.UNDERLINE -> UnderlineCandidate(c, selected, tokens, pickModifier)
                    StripTreatment.HAIRLINE -> {
                        HairlineCandidate(c, selected, tokens, pickModifier)
                        if (i < candidates.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(14.dp)
                                    .background(tokens.hairline),
                            )
                        }
                    }
                    StripTreatment.FLUSH -> FlushCandidate(c, selected, tokens, pickModifier)
                }
            }
        }
        // Right: expand-strip chevron (when there's overflow) + ⋯ utility menu.
        if (candidates.isNotEmpty() && onExpand != null) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tokens.keyAlt)
                    .clickable(onClick = onExpand)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▾",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.inkSoft,
                )
            }
        }
        Box(modifier = Modifier.padding(end = 8.dp)) {
            UtilityMenu(
                tokens = tokens,
                onSettingsClick = onSettingsClick,
                onSystemImeSettings = onSystemImeSettings,
                onClipboardClick = onClipboardClick,
            )
        }
    }
}

@Composable
private fun ChipCandidate(
    text: String,
    selected: Boolean,
    tokens: KeyboardTokens,
    interactive: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) tokens.accent else tokens.keyAlt)
            .then(interactive)
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
private fun UnderlineCandidate(
    text: String,
    selected: Boolean,
    tokens: KeyboardTokens,
    interactive: Modifier = Modifier,
) {
    Box(
        modifier = Modifier
            .then(interactive)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
private fun HairlineCandidate(
    text: String,
    selected: Boolean,
    tokens: KeyboardTokens,
    interactive: Modifier = Modifier,
) {
    Box(modifier = Modifier.then(interactive).padding(horizontal = 12.dp)) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) tokens.ink else tokens.inkSoft,
        )
    }
}

@Composable
private fun FlushCandidate(
    text: String,
    selected: Boolean,
    tokens: KeyboardTokens,
    interactive: Modifier = Modifier,
) {
    Column(
        modifier = Modifier.then(interactive).padding(horizontal = 11.dp),
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
