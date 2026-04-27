package io.github.ccy5123.korjpnime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import io.github.ccy5123.korjpnime.data.KeyboardPreferences
import io.github.ccy5123.korjpnime.engine.CheonjiinComposer
import io.github.ccy5123.korjpnime.engine.HangulComposer
import io.github.ccy5123.korjpnime.keyboard.KeyAction
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.ui.SettingsActivity
import kotlinx.coroutines.launch

/**
 * D3: routes key taps through a [HangulComposer] so that ㅎ ㅏ ㄴ becomes 한
 * (composed Hangul as preedit, committed when the syllable closes).  Kana
 * conversion still lives in M2.
 *
 * Mode/direction are hard-coded to d1 Stratus + 두벌식; the Settings screen
 * will flip these via DataStore at M1's tail.
 */
// @MX:ANCHOR: ComposeView host for the IME. Owners are attached to rootView in
// [KorJpnImeView.onAttachedToWindow] (before super) — IME framework wraps our
// view in a system-managed parentPanel that doesn't carry owners, and Compose
// looks UP from rootView to find them.
class KorJpnImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    /**
     * Hangul composer state.  Survives across taps on the same edit field.
     * Reset on focus loss ([onFinishInputView]) and on [KeyAction.SwitchIme].
     */
    private val composer = HangulComposer()

    /**
     * Cheonjiin (천지인) cycle / vowel-build buffer.  Sits in front of
     * [composer] when the user is in Cheonjiin mode; ignored (and reset) for
     * any non-CjConsonant / non-CjVowel input.
     */
    private val cheonjiin = CheonjiinComposer()

    /**
     * Latest haptics setting cached from [KeyboardPreferences.hapticsFlow].
     * @Volatile because it's read on the IME UI thread (handleAction) and
     * written from the lifecycleScope coroutine.
     */
    @Volatile private var hapticsEnabled: Boolean = true

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        // Cache haptics preference for handleAction's sync read.  Mode is read
        // directly via collectAsState in onCreateInputView's composable since
        // that path is already async-friendly.
        lifecycleScope.launch {
            KeyboardPreferences.hapticsFlow(applicationContext).collect { hapticsEnabled = it }
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return KorJpnImeView(context = this, owner = this) {
            val mode by KeyboardPreferences.modeFlow(applicationContext)
                .collectAsState(initial = KeyboardMode.BEOLSIK)
            MaterialTheme {
                KeyboardSurface(
                    direction = DIRECTIONS.first(),
                    dark = false,
                    mode = mode,
                    onAction = ::handleAction,
                    onSettingsClick = ::openSettings,
                )
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden / input session ending. Just drop composer state —
        // the framework auto-finalizes the editor's composing region into
        // committed text when the connection ends, so we don't need to (and
        // shouldn't) call commitText ourselves.  Earlier we did, which leaked
        // the trailing preedit ("요") into the next field after KakaoTalk's
        // external clear-on-send.
        composer.reset()
        cheonjiin.reset()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // True end of input session (target field is gone).  Belt-and-suspenders
        // reset for cases where onFinishInputView didn't fire.
        composer.reset()
        cheonjiin.reset()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        // If we still hold composer state but the editor reports no active
        // composing region (candidatesStart == -1), the field was reset
        // externally — KakaoTalk clears the EditText after its own send button
        // fires, the user tapped a different EditText, the app called
        // setText(""), etc.  Drop our state so the next jamo doesn't hand off
        // its leftover (cho, jung, jong) into a fresh context (the bug that
        // produced "요감사합니다").
        if (!composer.empty() && candidatesStart < 0) {
            composer.reset()
            cheonjiin.reset()
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun handleAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        performHapticIfEnabled()
        // Catch external field changes that bypass onUpdateSelection (KakaoTalk's
        // send button clears the field but doesn't notify our IME, so the leak
        // detector below misses it).  Sync IPC, runs only when composer is
        // non-empty and only at the start of an action.
        resyncComposerIfStale(ic)
        // Cheonjiin cycle window dissolves on any non-Cj tap, EXCEPT Space:
        // Space conditionally serves as cycle-break (handled inside
        // [handleSpace]) and decides on its own when to reset.
        val isCjTap = action is KeyAction.CjConsonant ||
            action is KeyAction.CjVowel ||
            action is KeyAction.CjPunct
        if (!isCjTap && action != KeyAction.Space) {
            cheonjiin.reset()
        }
        when (action) {
            is KeyAction.Commit -> handleCommit(ic, action.text)
            is KeyAction.CjConsonant -> handleCjOps(ic, cheonjiin.tapConsonant(action.cycle, System.currentTimeMillis()))
            is KeyAction.CjVowel -> handleCjOps(ic, cheonjiin.tapVowel(action.stroke, System.currentTimeMillis()))
            is KeyAction.CjPunct -> handleCjPunct(ic, action.cycle)
            KeyAction.Space -> handleSpace(ic)
            KeyAction.Backspace -> handleBackspace(ic)
            KeyAction.Enter -> { batched(ic) { flushComposerInner(ic) }; handleEnter() }
            KeyAction.SwitchIme -> { batched(ic) { flushComposerInner(ic) }; switchIme() }
            KeyAction.Shift -> Unit       // BeolsikLayout owns the Shift state; service receives the action only for haptic
            KeyAction.Symbols -> Unit     // future milestone
        }
    }

    /**
     * Space behaviour:
     *
     *  - If Cheonjiin is mid-consonant-cycle (e.g., user just tapped ㄴ for
     *    안's jong and is about to tap ㄴ again for 녕's cho), Space acts as
     *    a cycle-break only — no editor change, no haptic side effects beyond
     *    the one already fired.  This is what lets `안녕` be typed without an
     *    intervening literal space.
     *  - Otherwise (vowel buffer or empty), Space behaves as a regular space:
     *    flush any pending hangul and commit a literal space.
     */
    private fun handleSpace(ic: InputConnection) {
        if (cheonjiin.isInConsonantCycle()) {
            cheonjiin.reset()
            return
        }
        cheonjiin.reset()
        batched(ic) {
            flushComposerInner(ic)
            ic.commitText(" ", 1)
        }
    }

    /**
     * Apply punctuation-cycle ops directly to the editor.  The first tap
     * flushes any pending hangul and commits the first char; subsequent taps
     * within the cycle window replace the previously committed char with the
     * next in the cycle (delete-then-commit).
     */
    private fun handleCjPunct(ic: InputConnection, cycle: List<Char>) {
        val ops = cheonjiin.tapPunct(cycle, System.currentTimeMillis())
        if (ops.isEmpty()) return
        batched(ic) {
            for (op in ops) {
                when (op) {
                    is CheonjiinComposer.PunctOp.Insert -> {
                        flushComposerInner(ic)
                        ic.commitText(op.c.toString(), 1)
                    }
                    is CheonjiinComposer.PunctOp.Replace -> {
                        ic.deleteSurroundingText(1, 0)
                        ic.commitText(op.next.toString(), 1)
                    }
                }
            }
        }
    }

    /**
     * Apply a sequence of CheonjiinComposer ops (Undo / Emit) to the
     * underlying HangulComposer + editor.  Wrapped in a single batchEdit so
     * intermediate composing-region states aren't reported via onUpdateSelection
     * (same reasoning as [handleCommit]).
     */
    private fun handleCjOps(ic: InputConnection, ops: List<CheonjiinComposer.Op>) {
        if (ops.isEmpty()) return
        batched(ic) {
            for (op in ops) {
                when (op) {
                    CheonjiinComposer.Op.Undo -> composer.undoLastJamo()
                    is CheonjiinComposer.Op.Emit -> composer.input(op.jamo).also { finalized ->
                        if (finalized.isNotEmpty()) ic.commitText(finalized, 1)
                    }
                }
            }
            ic.setComposingText(composer.preedit(), 1)
        }
    }

    private fun performHapticIfEnabled() {
        if (!hapticsEnabled) return
        // currentInputView is the AbstractComposeView the IME framework attached.
        // It (or its rootView) is what the haptic API needs as a host View.
        val view = window?.window?.decorView ?: return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Source-of-truth check: verify the editor's text-before-cursor still ends
     * with our composer's preedit.  If not, the field was changed externally
     * (KakaoTalk's send-then-clear, an autofill insertion, the user tapped a
     * different EditText that didn't trigger onStartInput, etc.) and our
     * composer state is stale.
     *
     * Cheaper to fix on next keystroke than to chase every async callback that
     * apps may or may not send: KakaoTalk's clear doesn't fire onUpdateSelection
     * on this device, so the [onUpdateSelection] override below isn't enough on
     * its own.
     */
    private fun resyncComposerIfStale(ic: InputConnection) {
        if (composer.empty()) return
        val expected = composer.preedit()
        if (expected.isEmpty()) return
        val actual = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return
        if (actual != expected) {
            composer.reset()
            cheonjiin.reset()
        }
    }

    /**
     * Wrap an IC operation in [InputConnection.beginBatchEdit] /
     * [InputConnection.endBatchEdit].  The framework holds back its
     * `onUpdateSelection` callback until the batch ends, so the caller
     * sees ONE consolidated post-batch state instead of every intermediate
     * step (notably the brief no-composing-region window between
     * `commitText` and `setComposingText`, which would otherwise trip the
     * external-clear detector in [onUpdateSelection]).
     */
    private inline fun batched(ic: InputConnection, block: () -> Unit) {
        ic.beginBatchEdit()
        try {
            block()
        } finally {
            ic.endBatchEdit()
        }
    }

    private fun handleCommit(ic: InputConnection, text: String) {
        batched(ic) {
            if (text.length == 1 && HangulComposer.isHangulJamo(text[0])) {
                val finalized = composer.input(text[0])
                if (finalized.isNotEmpty()) ic.commitText(finalized, 1)
                ic.setComposingText(composer.preedit(), 1)
            } else {
                // Punctuation, digits, kana, anything non-jamo — flush
                // in-progress syllable first so it commits BEFORE the new text.
                flushComposerInner(ic)
                ic.commitText(text, 1)
            }
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        batched(ic) {
            if (!composer.empty()) {
                composer.undoLastJamo()
                // Empty preedit clears the composing region; non-empty replaces it.
                ic.setComposingText(composer.preedit(), 1)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
    }

    /**
     * Commit any in-progress syllable.  Wrap with [batched] yourself when
     * combining with other IC operations in the same handleAction branch.
     */
    private fun flushComposerInner(ic: InputConnection) {
        if (composer.empty()) return
        val flushed = composer.flush()
        if (flushed.isNotEmpty()) ic.commitText(flushed, 1)
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val flagNoEnter = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED ||
            flagNoEnter
        ) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
    }

    private fun switchIme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
