package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyShape
import io.github.ccy5123.korjpnime.theme.KeyboardTokens

/**
 * English QWERTY layout — surfaced only in [InputLanguage.ENGLISH] mode.
 * 5-row shape mirrors [BeolsikLayout]'s letters page so toggling 한 ↔ 일
 * ↔ 영 doesn't reflow the user's muscle memory of where Backspace /
 * Enter / Space sit.
 *
 * Letters commit ASCII directly (no composer); the IME service's
 * non-jamo branch routes them through.  Symbol pages reuse Beolsik's
 * Korean ASCII symbol set since English shares the same `! @ # ,` etc.
 */

private val QW_DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
private val QW_ROW1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
private val QW_ROW2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
private val QW_ROW3 = listOf("z", "x", "c", "v", "b", "n", "m")

private enum class QwertyPage { LETTERS, SYMBOLS_1, SYMBOLS_2 }

/**
 * Shift state machine for QWERTY:
 *  - OFF: lowercase output.
 *  - SHIFT: one-shot — capitalises the next letter, then auto-resets.
 *  - CAPS: persistent caps lock — every letter is capital until the
 *    user taps shift again.  Entered via double-tap on shift (within
 *    [DOUBLE_TAP_MS]) per the standard mobile-keyboard convention.
 */
private enum class ShiftState { OFF, SHIFT, CAPS }

private const val DOUBLE_TAP_MS = 350L

@Composable
fun QwertyLayout(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit = {},
    inputLanguage: InputLanguage = InputLanguage.ENGLISH,
    onLanguageCycle: () -> Unit = {},
) {
    var page by remember { mutableStateOf(QwertyPage.LETTERS) }
    val symPages = beolsikSymbolPagesFor(inputLanguage)
    when (page) {
        QwertyPage.LETTERS -> QwertyLetters(
            tokens = tokens,
            shape = shape,
            onAction = onAction,
            onShowSymbols = { page = QwertyPage.SYMBOLS_1 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
        QwertyPage.SYMBOLS_1 -> QwertySymbols(
            tokens = tokens,
            shape = shape,
            rows = symPages[0],
            otherPageLabel = "2/2",
            onAction = onAction,
            onShowLetters = { page = QwertyPage.LETTERS },
            onCyclePage = { page = QwertyPage.SYMBOLS_2 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
        QwertyPage.SYMBOLS_2 -> QwertySymbols(
            tokens = tokens,
            shape = shape,
            rows = symPages[1],
            otherPageLabel = "1/2",
            onAction = onAction,
            onShowLetters = { page = QwertyPage.LETTERS },
            onCyclePage = { page = QwertyPage.SYMBOLS_1 },
            inputLanguage = inputLanguage,
            onLanguageCycle = onLanguageCycle,
        )
    }
}

@Composable
private fun QwertyLetters(
    tokens: KeyboardTokens,
    shape: KeyShape,
    onAction: (KeyAction) -> Unit,
    onShowSymbols: () -> Unit,
    inputLanguage: InputLanguage,
    onLanguageCycle: () -> Unit,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

    var shift by remember { mutableStateOf(ShiftState.OFF) }
    var lastShiftTapMs by remember { mutableStateOf(0L) }

    fun onShiftTap() {
        val now = System.currentTimeMillis()
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.SHIFT
            // Second tap within the double-tap window → caps lock; otherwise
            // it's a "tap to dismiss the one-shot SHIFT" gesture (back to OFF).
            ShiftState.SHIFT ->
                if (now - lastShiftTapMs < DOUBLE_TAP_MS) ShiftState.CAPS
                else ShiftState.OFF
            ShiftState.CAPS -> ShiftState.OFF
        }
        lastShiftTapMs = now
    }

    val dispatch: (KeyAction) -> Unit = { action ->
        // One-shot SHIFT auto-resets after committing a letter; CAPS persists.
        if (shift == ShiftState.SHIFT) shift = ShiftState.OFF
        onAction(action)
    }
    fun cased(c: String) = if (shift != ShiftState.OFF) c.uppercase() else c

    Column(
        modifier = Modifier.fillMaxSize().padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        QwertyRow(gap = gap) {
            QW_DIGITS.forEach { d ->
                Key(tokens, shape, label = d,
                    onClick = { dispatch(KeyAction.Commit(d)) })
            }
        }
        QwertyRow(gap = gap) {
            QW_ROW1.forEach { c ->
                val out = cased(c)
                Key(tokens, shape, label = out,
                    onClick = { dispatch(KeyAction.Commit(out)) })
            }
        }
        QwertyRow(gap = gap) {
            JamoSpacer(weight = 0.5f)
            QW_ROW2.forEach { c ->
                val out = cased(c)
                Key(tokens, shape, label = out,
                    onClick = { dispatch(KeyAction.Commit(out)) })
            }
            JamoSpacer(weight = 0.5f)
        }
        QwertyRow(gap = gap) {
            // Shift visual: pressed=true for SHIFT (highlighted) or CAPS
            // (highlighted + accent tint).  Caps differs from shift via a
            // small "A" subscript so the user can tell them apart at a
            // glance — pure "pressed" alone would conflate the two.
            Key(
                tokens, shape, weight = 1.4f, fn = true,
                pressed = shift != ShiftState.OFF,
                accent = shift == ShiftState.CAPS,
                onClick = { onShiftTap(); onAction(KeyAction.Shift) },
            ) {
                ShiftIcon(
                    color = if (shift == ShiftState.CAPS) tokens.onAccent else tokens.inkSoft,
                )
            }
            QW_ROW3.forEach { c ->
                val out = cased(c)
                Key(tokens, shape, label = out,
                    onClick = { dispatch(KeyAction.Commit(out)) })
            }
            BackspaceKey(
                tokens = tokens, shape = shape,
                onTriggerBackspace = { dispatch(KeyAction.Backspace) },
            )
        }
        QwertyRow(gap = gap) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "!#1",
                onClick = { onShowSymbols() })
            Key(
                tokens, shape, weight = 1.3f, fn = true,
                label = langCycleLabelFor(inputLanguage),
                onClick = onLanguageCycle,
                onLongPress = { dispatch(KeyAction.SwitchIme) },
            )
            Key(tokens, shape, weight = 0.7f, fn = true, label = ",",
                onClick = { dispatch(KeyAction.Commit(",")) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                label = spaceLabelFor(inputLanguage),
                onClick = { dispatch(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = ".",
                onClick = { dispatch(KeyAction.Commit(".")) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { dispatch(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

@Composable
private fun QwertySymbols(
    tokens: KeyboardTokens,
    shape: KeyShape,
    rows: List<List<String>>,
    otherPageLabel: String,
    onAction: (KeyAction) -> Unit,
    onShowLetters: () -> Unit,
    onCyclePage: () -> Unit,
    inputLanguage: InputLanguage,
    onLanguageCycle: () -> Unit,
) {
    val gap = if (shape == KeyShape.FLAT) 0 else 4
    val pad = if (shape == KeyShape.FLAT) 0 else 6

    Column(
        modifier = Modifier.fillMaxSize().padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
    ) {
        QwertyRow(gap = gap) {
            QW_DIGITS.forEach { d ->
                Key(tokens, shape, label = d,
                    onClick = { onAction(KeyAction.Commit(d)) })
            }
        }
        QwertyRow(gap = gap) {
            rows[0].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
        }
        QwertyRow(gap = gap) {
            rows[1].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
        }
        QwertyRow(gap = gap) {
            Key(tokens, shape, weight = 1.4f, fn = true, label = otherPageLabel,
                onClick = { onCyclePage() })
            rows[2].forEach { sym ->
                Key(tokens, shape, label = sym,
                    onClick = { onAction(KeyAction.Commit(sym)) })
            }
            BackspaceKey(
                tokens = tokens, shape = shape,
                onTriggerBackspace = { onAction(KeyAction.Backspace) },
            )
        }
        QwertyRow(gap = gap) {
            Key(tokens, shape, weight = 1.3f, fn = true, label = "한",
                onClick = { onShowLetters() })
            Key(tokens, shape, weight = 1.3f, fn = true,
                label = langCycleLabelFor(inputLanguage),
                onClick = onLanguageCycle,
                onLongPress = { onAction(KeyAction.SwitchIme) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = ",",
                onClick = { onAction(KeyAction.Commit(",")) })
            SpaceKey(tokens = tokens, shape = shape, weight = 3.5f,
                label = spaceLabelFor(inputLanguage),
                onClick = { onAction(KeyAction.Space) })
            Key(tokens, shape, weight = 0.7f, fn = true, label = ".",
                onClick = { onAction(KeyAction.Commit(".")) })
            Key(tokens, shape, weight = 1.3f, accent = true,
                onClick = { onAction(KeyAction.Enter) }) {
                EnterIcon(color = tokens.onAccent)
            }
        }
    }
}

@Composable
private fun ColumnScope.QwertyRow(
    gap: Int,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
