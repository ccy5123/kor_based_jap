package io.github.ccy5123.korjpnime

import android.inputmethodservice.InputMethodService

// @MX:TODO: [AUTO] Wire ComposeView hosting KeyboardSurface via onCreateInputView() (D2).
// @MX:PRIORITY: P2
class KorJpnImeService : InputMethodService() {
    // D1b stub: registration only — system shows KorJpnIme in the keyboard list,
    // but selecting it yields the platform's blank fallback view.
}
