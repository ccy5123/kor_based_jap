package io.github.ccy5123.korjpnime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
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
import io.github.ccy5123.korjpnime.keyboard.KeyAction
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode

/**
 * D2: hosts a ComposeView containing [KeyboardSurface] and routes taps to
 * the active [android.view.inputmethod.InputConnection].
 *
 * D2 emits raw jamo (no Hangul composition). The Hangul composer hooks in at D3.
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

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun handleAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Commit -> ic.commitText(action.text, 1)
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Backspace -> ic.deleteSurroundingText(1, 0)
            KeyAction.Enter -> handleEnter()
            KeyAction.SwitchIme -> switchIme()
            KeyAction.Shift -> Unit       // D3+
            KeyAction.Symbols -> Unit     // future milestone
        }
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
