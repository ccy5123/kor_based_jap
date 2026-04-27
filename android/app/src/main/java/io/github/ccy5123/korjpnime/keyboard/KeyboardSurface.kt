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
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.theme.Direction
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.tokens

/**
 * Full keyboard: top chrome + candidate strip + key grid for the selected mode.
 * Sized to the design's 312 dp keyboard height by default; pass [Modifier.height]
 * to override.
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
    candidates: List<String> = emptyList(),
    onCandidatePick: (String) -> Unit = {},
    onAction: (KeyAction) -> Unit = {},
    onSettingsClick: (() -> Unit)? = null,
) {
    val tokens = tokens(direction.palette, dark)
    var expanded by remember { mutableStateOf(false) }
    // Auto-collapse when candidates clear (user picked, run reset, etc.)
    LaunchedEffect(candidates) { if (candidates.isEmpty()) expanded = false }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(312.dp)
            .background(tokens.sheet),
    ) {
        TopChrome(tokens = tokens, onSettingsClick = onSettingsClick)
        CandidateStrip(
            tokens = tokens,
            treatment = direction.strip,
            candidates = candidates,
            onPick = onCandidatePick,
            onExpand = if (candidates.isNotEmpty()) {
                { expanded = !expanded }
            } else null,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (mode) {
                KeyboardMode.BEOLSIK -> BeolsikLayout(
                    tokens = tokens, shape = direction.shape, onAction = onAction,
                )
                KeyboardMode.CHEONJIIN -> CheonjiinLayout(
                    tokens = tokens, shape = direction.shape, onAction = onAction,
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
        }
    }
}
