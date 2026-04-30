package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/**
 * Tap-to-paste clipboard history panel that overlays the keyboard area
 * when the user opens it from the ⋯ menu.  Lists [items] most-recent-
 * first; tapping an entry calls [onPick] (the IME service then commits
 * the text at the cursor and dismisses the panel).
 *
 * Each item card is full-width, multi-line (cap 2 lines visible) for
 * the medium-length clipboard fragments people typically save.
 */
@Composable
fun ClipboardPanel(
    tokens: KeyboardTokens,
    items: List<String>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.sheet)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "📋 클립보드",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = tokens.ink,
            )
            Spacer(modifier = Modifier.padding(end = 0.dp).fillMaxWidth().weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✕",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.inkSoft,
                )
            }
        }
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "최근 복사한 텍스트가 없어요.\n다른 앱에서 복사하면 여기에 쌓입니다.",
                    fontSize = 12.sp,
                    color = tokens.inkSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(items) { item ->
                    ClipboardItemCard(
                        text = item,
                        tokens = tokens,
                        onClick = { onPick(item) },
                        onDelete = { onDelete(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardItemCard(
    text: String,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.key)
            .border(1.dp, tokens.hairline, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = tokens.ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "🗑",
                fontSize = 14.sp,
                color = Color(0xFF888888),
            )
        }
    }
}
