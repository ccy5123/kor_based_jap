package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/** Top chrome bar above the candidate strip. */
@Composable
fun TopChrome(
    tokens: KeyboardTokens,
    inputLanguage: InputLanguage = InputLanguage.JAPANESE,
    onSettingsClick: (() -> Unit)? = null,
) {
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
        LanguageBadge(tokens = tokens, inputLanguage = inputLanguage)
        Box(
            modifier = Modifier
                .size(20.dp)
                .let { if (onSettingsClick != null) it.clip(CircleShape).clickable(onClick = onSettingsClick) else it },
            contentAlignment = Alignment.Center,
        ) {
            SettingsGearIcon(color = tokens.inkSoft)
        }
    }
}

/**
 * Top-chrome badge that surfaces the current [InputLanguage] as a flag
 * emoji + native-script label.  Provides a glanceable cue in the chrome
 * bar that mirrors the SpaceKey label, so users who occasionally
 * forget to check the spacebar before typing still see *something* in
 * their peripheral vision telling them which language they're in.
 *
 * Per-language tint (light theme):
 *   - 🇰🇷 한국어  on a soft blue background
 *   - 🇺🇸 English  on a soft amber background
 *   - 🇯🇵 日本語   on a soft pink background
 *
 * Backgrounds use generous alpha so they read on both the light strip
 * and the dark-mode variant without redefining per-direction tokens.
 */
@Composable
private fun LanguageBadge(tokens: KeyboardTokens, inputLanguage: InputLanguage) {
    val (flag, label, tint) = when (inputLanguage) {
        InputLanguage.KOREAN -> Triple("🇰🇷", "한국어", Color(0xFF1E5AFF))
        InputLanguage.ENGLISH -> Triple("🇺🇸", "English", Color(0xFFB8862E))
        InputLanguage.JAPANESE -> Triple("🇯🇵", "日本語", Color(0xFFBC002D))
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = flag, fontSize = 11.sp)
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                letterSpacing = 0.4.sp,
            )
        }
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
