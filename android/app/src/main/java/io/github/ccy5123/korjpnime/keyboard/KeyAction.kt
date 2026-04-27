package io.github.ccy5123.korjpnime.keyboard

/**
 * What a single key tap means to the IME service.
 *
 * D2: raw jamo / character emission via [Commit].
 * D3: Hangul composer absorbs [Commit]'d jamo (BeolsikLayout path).
 * M1 Cheonjiin: keypad layout dispatches [CjConsonant] / [CjVowel] which
 * the service routes through CheonjiinComposer before HangulComposer.
 */
sealed class KeyAction {
    /** Commit literal text to the editor (jamo, punctuation, etc.). */
    data class Commit(val text: String) : KeyAction()

    /**
     * 천지인 consonant key tap.  [cycle] is the full multi-tap cycle
     * (e.g., `[ㄱ, ㅋ, ㄲ]`); the service consults CheonjiinComposer for
     * the current cycle position and emits the appropriate jamo.
     */
    data class CjConsonant(val cycle: List<Char>) : KeyAction()

    /**
     * 천지인 vowel-stroke key tap.  [stroke] is one of ㅣ ㆍ ㅡ; the
     * service builds nucleus vowels from sequences of these via
     * CheonjiinComposer.
     */
    data class CjVowel(val stroke: Char) : KeyAction()

    /**
     * 천지인 punctuation key tap (typically `.,?!`).  Multi-tap cycles through
     * [cycle] in place — first tap commits the first char, subsequent fast
     * taps replace it with the next.
     */
    data class CjPunct(val cycle: List<Char>) : KeyAction()

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
