package io.github.ccy5123.korjpnime.keyboard

import android.widget.Toast
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

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
internal fun LanguageBadge(tokens: KeyboardTokens, inputLanguage: InputLanguage) {
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

/**
 * Single ⋯ icon → dropdown menu of every chrome-level action.  Stub
 * entries (emoji / clipboard / voice) just toast "준비 중" for now —
 * the visible-but-disabled affordance lets users know the slot exists
 * and follow-up work can wire each entry without further UI plumbing.
 */
@Composable
internal fun UtilityMenu(
    tokens: KeyboardTokens,
    onSettingsClick: (() -> Unit)?,
    onSystemImeSettings: (() -> Unit)?,
    onClipboardClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    fun stub(label: String) {
        Toast.makeText(context, "$label — 준비 중", Toast.LENGTH_SHORT).show()
    }

    Box {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⋯",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = tokens.inkSoft,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            DropdownMenuItem(
                text = { Text("🎨  이모지") },
                onClick = { open = false; stub("이모지") },
            )
            DropdownMenuItem(
                text = { Text("📋  클립보드") },
                onClick = { open = false; onClipboardClick?.invoke() },
            )
            DropdownMenuItem(
                text = { Text("🎤  음성 입력") },
                onClick = { open = false; stub("음성 입력") },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("🌐  시스템 키보드 설정") },
                onClick = { open = false; onSystemImeSettings?.invoke() },
            )
            DropdownMenuItem(
                text = { Text("⚙️  키보드 설정") },
                onClick = { open = false; onSettingsClick?.invoke() },
            )
        }
    }
}
