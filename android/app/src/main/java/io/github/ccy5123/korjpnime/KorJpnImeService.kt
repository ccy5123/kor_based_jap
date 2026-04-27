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
        // Editor lost focus / IME hidden — commit any pending syllable into
        // the now-fading field, then drop composer state so the next field
        // starts clean.  Without this the next field would carry a stale
        // (cho, jung, jong) trio.
        currentInputConnection?.let { flushComposer(it) }
        composer.reset()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun handleAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Commit -> handleCommit(ic, action.text)
            KeyAction.Space -> { flushComposer(ic); ic.commitText(" ", 1) }
            KeyAction.Backspace -> handleBackspace(ic)
            KeyAction.Enter -> { flushComposer(ic); handleEnter() }
            KeyAction.SwitchIme -> { flushComposer(ic); switchIme() }
            KeyAction.Shift -> Unit       // 쌍자음 lands with Shift state machine (M1 closeout)
            KeyAction.Symbols -> Unit     // future milestone
        }
    }

    private fun handleCommit(ic: InputConnection, text: String) {
        if (text.length == 1 && HangulComposer.isHangulJamo(text[0])) {
            val finalized = composer.input(text[0])
            if (finalized.isNotEmpty()) ic.commitText(finalized, 1)
            ic.setComposingText(composer.preedit(), 1)
        } else {
            // Punctuation, digits, kana, anything non-jamo — flush in-progress
            // syllable first so it commits BEFORE the new text.
            flushComposer(ic)
            ic.commitText(text, 1)
        }
    }

    private fun handleBackspace(ic: InputConnection) {
        if (!composer.empty()) {
            composer.undoLastJamo()
            // Empty preedit clears the composing region; non-empty replaces it.
            ic.setComposingText(composer.preedit(), 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    /** Commit any in-progress syllable and clear the composing region. */
    private fun flushComposer(ic: InputConnection) {
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
