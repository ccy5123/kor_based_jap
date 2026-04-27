package io.github.ccy5123.korjpnime

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * AbstractComposeView host for the IME's input view.
 *
 * Why a custom subclass instead of plain `ComposeView`:
 * the IME framework wraps whatever `onCreateInputView()` returns inside a
 * system-managed `parentPanel` LinearLayout. When Compose attaches it then
 * walks **up** to the rootView to find ViewTreeLifecycleOwner — anything we
 * set on the ComposeView itself is invisible from the root.
 *
 * Setting owners on `rootView` *before* `super.onAttachedToWindow()` runs
 * (which is what kicks off composition) lets the WindowRecomposer find them.
 */
class KorJpnImeView<T>(
    context: Context,
    private val owner: T,
    private val composable: @Composable () -> Unit,
) : AbstractComposeView(context) where T : LifecycleOwner,
                                       T : ViewModelStoreOwner,
                                       T : SavedStateRegistryOwner {

    @Composable
    override fun Content() {
        composable()
    }

    override fun onAttachedToWindow() {
        rootView.setViewTreeLifecycleOwner(owner)
        rootView.setViewTreeViewModelStoreOwner(owner)
        rootView.setViewTreeSavedStateRegistryOwner(owner)
        super.onAttachedToWindow()
    }
}
