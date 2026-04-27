package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/** Top chrome bar above the candidate strip. */
@Composable
fun TopChrome(tokens: KeyboardTokens) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(tokens.strip)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // D1b: round dots stand in for emoji/sticker/mic/clip; swap for proper SVG icons later.
        ChromeDot(tokens.inkSoft)
        ChromeDot(tokens.inkSoft)
        ChromeDot(tokens.inkSoft)
        ChromeDot(tokens.inkSoft)
        Box(modifier = Modifier.weight(1f).height(1.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(tokens.accentSoft)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text = "KO → JA",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.accent,
                letterSpacing = 0.7.sp,
            )
        }
        SettingsGearIcon(color = tokens.inkSoft)
    }
}

@Composable
private fun ChromeDot(color: Color) {
    Box(
        modifier = Modifier
            .size(13.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.5f)),
    )
}
