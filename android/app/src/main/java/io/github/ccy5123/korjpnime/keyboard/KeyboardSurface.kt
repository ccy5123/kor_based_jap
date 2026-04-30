package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.theme.Direction
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.resolveTokens

/**
 * Full keyboard: top chrome + candidate strip + key grid for the selected mode.
 * Height is controlled by [heightDp] (default 360 dp); user-adjustable via the
 * Settings slider, persisted in [io.github.ccy5123.korjpnime.data.KeyboardPreferences].
 */
// @MX:ANCHOR: [AUTO] Single rendering entry point for the IME — invariant across
// MainActivity preview, KorJpnImeService.onCreateInputView (D2), and the @Preview matrix.
// @MX:REASON: Token + layout selection happens here; downstream composables rely on
// receiving a fully-resolved KeyboardTokens. Changing this signature requires updating
// all 14 @Preview entries and the IME service host.
@Composable
fun KeyboardSurface(
    direction: Direction,
    dark: Boolean,
    mode: KeyboardMode,
    modifier: Modifier = Modifier,
    heightDp: Int = 360,
    inputLanguage: InputLanguage = InputLanguage.JAPANESE,
    onLanguageCycle: () -> Unit = {},
    candidates: List<String> = emptyList(),
    onCandidatePick: (String) -> Unit = {},
    onAction: (KeyAction) -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
    onSystemImeSettings: (() -> Unit)? = null,
    clipboardItems: List<String> = emptyList(),
    onClipboardPick: (String) -> Unit = {},
    onClipboardDelete: (String) -> Unit = {},
    emojiCategories: List<io.github.ccy5123.korjpnime.engine.EmojiData.Category> = emptyList(),
    emojiRecents: List<String> = emptyList(),
    onEmojiPick: (String) -> Unit = {},
) {
    val tokens = resolveTokens(direction, dark, LocalContext.current)
    var expanded by remember { mutableStateOf(false) }
    var showClipboard by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    // Auto-collapse when candidates clear (user picked, run reset, etc.)
    LaunchedEffect(candidates) { if (candidates.isEmpty()) expanded = false }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(tokens.sheet),
    ) {
        // Top chrome (lang badge + ⋯ menu) is now merged into the
        // candidate strip row — saves 30 dp of vertical space and gives
        // the always-on chrome anchors a single home alongside the
        // per-tap candidates.
        CandidateStrip(
            tokens = tokens,
            treatment = direction.strip,
            candidates = candidates,
            onPick = onCandidatePick,
            onExpand = if (candidates.isNotEmpty()) {
                { expanded = !expanded }
            } else null,
            inputLanguage = inputLanguage,
            onSettingsClick = onSettingsClick,
            onSystemImeSettings = onSystemImeSettings,
            onClipboardClick = { showClipboard = true },
            onEmojiClick = { showEmoji = true },
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // ENGLISH mode always renders QWERTY regardless of the user's
            // 두벌식 / 천지인 keyboardMode preference — Cheonjiin's 4×4 grid
            // can't host a sensible English layout without T9-style multi-tap,
            // and QWERTY is the universal expectation for ASCII input.
            when {
                inputLanguage == InputLanguage.ENGLISH -> QwertyLayout(
                    tokens = tokens, shape = direction.shape, onAction = onAction,
                    inputLanguage = inputLanguage, onLanguageCycle = onLanguageCycle,
                )
                mode == KeyboardMode.BEOLSIK -> BeolsikLayout(
                    tokens = tokens, shape = direction.shape, onAction = onAction,
                    inputLanguage = inputLanguage, onLanguageCycle = onLanguageCycle,
                )
                else -> CheonjiinLayout(
                    tokens = tokens, shape = direction.shape, onAction = onAction,
                    inputLanguage = inputLanguage, onLanguageCycle = onLanguageCycle,
                )
            }
            if (expanded && candidates.isNotEmpty()) {
                ExpandedCandidatesPanel(
                    tokens = tokens,
                    candidates = candidates,
                    onPick = {
                        onCandidatePick(it)
                        expanded = false
                    },
                    onClose = { expanded = false },
                )
            }
            if (showClipboard) {
                ClipboardPanel(
                    tokens = tokens,
                    items = clipboardItems,
                    onPick = {
                        onClipboardPick(it)
                        showClipboard = false
                    },
                    onClose = { showClipboard = false },
                    onDelete = onClipboardDelete,
                )
            }
            if (showEmoji) {
                EmojiPanel(
                    tokens = tokens,
                    categories = emojiCategories,
                    recents = emojiRecents,
                    onPick = onEmojiPick,
                    onClose = { showEmoji = false },
                )
            }
        }
    }
}
