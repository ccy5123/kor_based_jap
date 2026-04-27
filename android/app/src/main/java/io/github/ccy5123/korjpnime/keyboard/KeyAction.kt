package io.github.ccy5123.korjpnime.keyboard

/**
 * What a single key tap means to the IME service.
 *
 * D2: raw jamo / character emission only — no Hangul composition.
 * D3 will introduce a composer that intercepts [Commit] for jamo and
 * produces composed syllables instead.
 */
sealed class KeyAction {
    /** Commit literal text to the editor (jamo, punctuation, etc.). */
    data class Commit(val text: String) : KeyAction()

    /** Delete one code unit before the cursor. */
    object Backspace : KeyAction()

    /** Newline or editor action (Send / Done / Search) depending on EditorInfo. */
    object Enter : KeyAction()

    /** Commit a single space. */
    object Space : KeyAction()

    /** Switch to the next system IME (globe key). */
    object SwitchIme : KeyAction()

    /** Shift / caps. No-op in D2. */
    object Shift : KeyAction()

    /** Symbols / numbers page. No-op in D2. */
    object Symbols : KeyAction()
}
