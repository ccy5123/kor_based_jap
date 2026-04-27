package io.github.ccy5123.korjpnime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import io.github.ccy5123.korjpnime.engine.HangulComposer
import io.github.ccy5123.korjpnime.keyboard.KeyAction
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode

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

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return KorJpnImeView(context = this, owner = this) {
            MaterialTheme {
                KeyboardSurface(
                    direction = DIRECTIONS.first(),
                    dark = false,
                    mode = KeyboardMode.BEOLSIK,
                    onAction = ::handleAction,
                )
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // Keyboard hidden / input session ending. Just drop composer state —
        // the framework auto-finalizes the editor's composing region into
        // committed text when the connection ends, so we don't need to (and
        // shouldn't) call commitText ourselves.  Earlier we did, which leaked
        // the trailing preedit ("요") into the next field after KakaoTalk's
        // external clear-on-send.
        composer.reset()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // True end of input session (target field is gone).  Belt-and-suspenders
        // reset for cases where onFinishInputView didn't fire.
        composer.reset()
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
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun handleAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        // Catch external field changes that bypass onUpdateSelection (KakaoTalk's
        // send button clears the field but doesn't notify our IME, so the leak
        // detector below misses it).  Sync IPC, runs only when composer is
        // non-empty and only at the start of an action.
        resyncComposerIfStale(ic)
        when (action) {
            is KeyAction.Commit -> handleCommit(ic, action.text)
            KeyAction.Space -> batched(ic) { flushComposerInner(ic); ic.commitText(" ", 1) }
            KeyAction.Backspace -> handleBackspace(ic)
            KeyAction.Enter -> { batched(ic) { flushComposerInner(ic) }; handleEnter() }
            KeyAction.SwitchIme -> { batched(ic) { flushComposerInner(ic) }; switchIme() }
            KeyAction.Shift -> Unit       // BeolsikLayout owns the Shift state; service receives the action only for haptic
            KeyAction.Symbols -> Unit     // future milestone
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
        if (actual != expected) composer.reset()
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
