package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.engine.EmojiData
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/**
 * Emoji panel — full-keyboard overlay (mirrors ClipboardPanel's pattern).
 *
 *   - Top row: category tabs (가로 스크롤).  "최근" tab first, then the
 *     9 standard Unicode groups (Smileys & Emotion / People & Body /
 *     Animals & Nature / …).  Tabs use Korean labels for our user base.
 *   - Below: 8-column LazyVerticalGrid of emoji cells.  Tap commits +
 *     pushes to recents; panel stays open for chained inputs.
 *   - Top-right: ✕ close button.
 *
 * Skin-tone variants are reserved for a future long-press gesture
 * (data already loaded; see [EmojiData.variantsOf]).
 */
@Composable
fun EmojiPanel(
    tokens: KeyboardTokens,
    categories: List<EmojiData.Category>,
    recents: List<String>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    variantsOf: (String) -> List<String> = { emptyList() },
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    // Long-press popover state: (base emoji, [base + skin variants]).
    // Null when no popover is showing.
    var variantPopover by remember { mutableStateOf<Pair<String, List<String>>?>(null) }

    // Tab 0 is always "최근" (dynamic), tab 1+ are the static categories.
    val tabLabels = listOf("🕘 최근") + categories.map { categoryLabel(it.name) }
    val visibleEmojis = if (selectedTab == 0) recents
                        else categories.getOrNull(selectedTab - 1)?.emojis ?: emptyList()

    Box(modifier = Modifier.fillMaxSize().background(tokens.sheet)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
    ) {
        // Header: category tabs (scrollable) + close button.
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabLabels.forEachIndexed { i, label ->
                    val selected = i == selectedTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) tokens.accentSoft else tokens.keyAlt)
                            .clickable { selectedTab = i }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) tokens.accent else tokens.inkSoft,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(6.dp))
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
        Spacer(modifier = Modifier.size(6.dp))
        // Body: emoji grid.
        if (visibleEmojis.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selectedTab == 0)
                        "최근 사용한 이모지가 없어요.\n다른 탭에서 이모지를 눌러 보세요."
                    else
                        "이 카테고리에 이모지가 없어요.",
                    fontSize = 12.sp,
                    color = tokens.inkSoft,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visibleEmojis) { emoji ->
                    val variants = variantsOf(emoji)
                    EmojiCell(
                        emoji = emoji,
                        hasVariants = variants.isNotEmpty(),
                        tokens = tokens,
                        onClick = { onPick(emoji) },
                        onLongPress = {
                            if (variants.isNotEmpty()) {
                                variantPopover = emoji to (listOf(emoji) + variants)
                            }
                        },
                    )
                }
            }
        }
    }
    // Long-press skin tone popover overlay — translucent backdrop
    // dismisses on tap; the variant strip in the centre lets the user
    // pick a tone without leaving the panel.
    variantPopover?.let { (_, variants) ->
        VariantPopover(
            variants = variants,
            tokens = tokens,
            onPick = {
                onPick(it)
                variantPopover = null
            },
            onDismiss = { variantPopover = null },
        )
    }
    }  // outer Box
}

@Composable
private fun EmojiCell(
    emoji: String,
    hasVariants: Boolean,
    tokens: KeyboardTokens,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .pointerInput(emoji) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 22.sp)
        // Tiny corner dot signals "long-press for skin-tone variants".
        if (hasVariants) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(tokens.inkSoft.copy(alpha = 0.5f)),
            )
        }
    }
}

/**
 * Long-press popover showing skin-tone variants of a base emoji.
 * Translucent backdrop dismisses on tap; the variant strip in the
 * centre is opaque + clipped to a card.  [variants] is the full list
 * (base first, then 5 skin tones) so the user can also re-pick the
 * base without losing the popover.
 */
@Composable
private fun VariantPopover(
    variants: List<String>,
    tokens: KeyboardTokens,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(tokens.sheet)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            variants.forEach { v ->
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tokens.keyAlt)
                        .clickable { onPick(v) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = v, fontSize = 26.sp)
                }
            }
        }
    }
}

/** Korean tab labels for Unicode group names. */
private fun categoryLabel(group: String): String = when (group) {
    "Smileys & Emotion" -> "😀 감정"
    "People & Body" -> "🤝 사람"
    "Animals & Nature" -> "🐶 동물"
    "Food & Drink" -> "🍎 음식"
    "Travel & Places" -> "🚗 장소"
    "Activities" -> "🎉 활동"
    "Objects" -> "💡 사물"
    "Symbols" -> "❤️ 심볼"
    "Flags" -> "🏳️ 깃발"
    else -> group
}
