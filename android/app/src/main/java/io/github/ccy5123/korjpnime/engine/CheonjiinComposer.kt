package io.github.ccy5123.korjpnime.engine

/**
 * Cheonjiin (천지인) state machine — a layer above [HangulComposer] that turns
 * the 3×4 keypad's multi-tap consonant cycling and ㅣ/ㆍ/ㅡ vowel composition
 * into a stream of basic jamo for HangulComposer to absorb.
 *
 * Two parallel buffer kinds:
 *
 *  - **Consonant cycle.** Tapping the same group key within the cycle window
 *    rotates through its cycle list (e.g., ㄱ → ㅋ → ㄲ → ㄱ).  Each cycle
 *    step emits an [Op.Undo] then [Op.Emit] so HangulComposer reflects the
 *    new cycle position.  Tapping a different key (or any other input)
 *    starts a fresh cycle on that key.
 *
 *  - **Vowel build.** Sequences of ㅣ/ㆍ/ㅡ build up nucleus vowels via a
 *    transition table.  ㆍ alone is intermediate (no emit) until a stroke
 *    that makes it concrete arrives.  Compound vowels (ㅘ ㅙ ㅝ ㅞ) are
 *    NOT directly produced — instead this layer commits the current vowel
 *    and emits the new basic vowel as a fresh tap, letting HangulComposer's
 *    own compound-vowel fusion combine them.
 *
 * Cycle window: [WINDOW_MS].  Outside it, every tap starts a fresh cycle.
 * No active timer is used — the check happens lazily at the next tap, since
 * the inter-tap state has no editor visibility (the jamo was already emitted
 * to HangulComposer).
 *
 * NOT thread-safe.  Instantiate one per IME service.
 */
class CheonjiinComposer {

    /** Operation the host should apply to the underlying [HangulComposer]. */
    sealed class Op {
        /** Peel the most recently emitted jamo (cycle replacement). */
        object Undo : Op()
        /** Feed [jamo] into HangulComposer.input. */
        data class Emit(val jamo: Char) : Op()
    }

    /** Punctuation-cycle operation applied directly to the editor. */
    sealed class PunctOp {
        /** Insert [c] (after flushing any in-progress hangul). */
        data class Insert(val c: Char) : PunctOp()
        /** Delete the previously inserted punct char and commit [next]. */
        data class Replace(val next: Char) : PunctOp()
    }

    private sealed class Buffer {
        object None : Buffer()
        data class Consonant(val cycle: List<Char>, val pos: Int) : Buffer()
        sealed class Vowel : Buffer() {
            object DotIntermediate : Vowel()        // ㆍ, no emit
            object DoubleDotIntermediate : Vowel()  // ㆍㆍ, no emit
            data class Concrete(val jamo: Char) : Vowel()
        }
        data class Punct(val cycle: List<Char>, val pos: Int) : Buffer()
    }

    private var buffer: Buffer = Buffer.None
    private var lastTapMs: Long = 0L

    /** Drop all buffer state.  Call on mode switch / focus loss / backspace. */
    fun reset() {
        buffer = Buffer.None
        lastTapMs = 0L
    }

    /**
     * True when the last tap left an open consonant cycle that a same-key tap
     * could continue.  Used by the host to decide whether Space should act as
     * a cycle-break (e.g. so 안 + ㄴ → 안녕 instead of 알) or a literal space.
     * Vowel buffers don't trigger this since a stray vowel-only buffer doesn't
     * cause same-key collisions on the next consonant.
     */
    fun isInConsonantCycle(): Boolean = buffer is Buffer.Consonant

    /**
     * Process a consonant key tap.  Each Cheonjiin consonant key has a fixed
     * cycle (e.g., `[ㄱ, ㅋ, ㄲ]`).  Returns the ops the host should apply.
     */
    fun tapConsonant(cycle: List<Char>, nowMs: Long): List<Op> {
        if (cycle.isEmpty()) return emptyList()
        decayBufferIfStale(nowMs)
        lastTapMs = nowMs

        val current = buffer
        return if (current is Buffer.Consonant && current.cycle === cycle) {
            // Same key → advance cycle position
            val nextPos = (current.pos + 1) % cycle.size
            buffer = Buffer.Consonant(cycle, nextPos)
            listOf(Op.Undo, Op.Emit(cycle[nextPos]))
        } else {
            // Different key (or different buffer kind) → start fresh cycle
            buffer = Buffer.Consonant(cycle, 0)
            listOf(Op.Emit(cycle[0]))
        }
    }

    /**
     * Process a vowel-stroke key tap.  [stroke] must be one of ㅣ ㆍ ㅡ.
     */
    fun tapVowel(stroke: Char, nowMs: Long): List<Op> {
        decayBufferIfStale(nowMs)
        lastTapMs = nowMs

        // If we were in a consonant / punct cycle, that prior emit is now
        // committed (the editor / HangulComposer already saw it).  Switch
        // into the vowel state machine.
        val current = buffer
        return when (current) {
            is Buffer.Vowel.Concrete -> transitionConcrete(current.jamo, stroke)
            Buffer.Vowel.DotIntermediate -> transitionDotIntermediate(stroke)
            Buffer.Vowel.DoubleDotIntermediate -> transitionDoubleDotIntermediate(stroke)
            // Punct / Consonant / None all enter the vowel state machine fresh.
            is Buffer.Consonant, is Buffer.Punct, Buffer.None -> startVowelFromEmpty(stroke)
        }
    }

    /**
     * Used after the upstream HangulComposer is reset / committed without a
     * Cheonjiin tap (Space / Enter / SwitchIme / external clear).  The cycle
     * window is meaningless once the composer breaks, so drop our state.
     */
    fun onComposerCommitted() {
        reset()
    }

    /**
     * Process a punctuation-cycle key tap.  [cycle] is the multi-tap rotation
     * (e.g., `['.', ',', '?', '!']`).  Returns ops for the host to apply
     * directly to the editor — punctuation chars never enter HangulComposer.
     */
    fun tapPunct(cycle: List<Char>, nowMs: Long): List<PunctOp> {
        if (cycle.isEmpty()) return emptyList()
        decayBufferIfStale(nowMs)
        lastTapMs = nowMs

        val current = buffer
        return if (current is Buffer.Punct && current.cycle === cycle) {
            // Cycle continuation — replace previously inserted char in place.
            val nextPos = (current.pos + 1) % cycle.size
            buffer = Buffer.Punct(cycle, nextPos)
            listOf(PunctOp.Replace(cycle[nextPos]))
        } else {
            // Fresh punct buffer (or different kind).  Caller will flush any
            // pending hangul before applying the Insert.
            buffer = Buffer.Punct(cycle, 0)
            listOf(PunctOp.Insert(cycle[0]))
        }
    }

    // ── Vowel transition helpers ────────────────────────────────────────────

    private fun startVowelFromEmpty(stroke: Char): List<Op> = when (stroke) {
        STROKE_I -> {
            buffer = Buffer.Vowel.Concrete('ㅣ')
            listOf(Op.Emit('ㅣ'))
        }
        STROKE_DOT -> {
            buffer = Buffer.Vowel.DotIntermediate
            emptyList()  // ㆍ alone does not emit
        }
        STROKE_EU -> {
            buffer = Buffer.Vowel.Concrete('ㅡ')
            listOf(Op.Emit('ㅡ'))
        }
        else -> emptyList()
    }

    private fun transitionDotIntermediate(stroke: Char): List<Op> = when (stroke) {
        STROKE_I -> {
            buffer = Buffer.Vowel.Concrete('ㅓ')
            listOf(Op.Emit('ㅓ'))
        }
        STROKE_DOT -> {
            buffer = Buffer.Vowel.DoubleDotIntermediate
            emptyList()
        }
        STROKE_EU -> {
            buffer = Buffer.Vowel.Concrete('ㅗ')
            listOf(Op.Emit('ㅗ'))
        }
        else -> emptyList()
    }

    private fun transitionDoubleDotIntermediate(stroke: Char): List<Op> = when (stroke) {
        STROKE_I -> {
            buffer = Buffer.Vowel.Concrete('ㅕ')
            listOf(Op.Emit('ㅕ'))
        }
        STROKE_EU -> {
            buffer = Buffer.Vowel.Concrete('ㅛ')
            listOf(Op.Emit('ㅛ'))
        }
        STROKE_DOT -> {
            // 3 dots aren't standard — drop accumulated dots, restart with 1.
            buffer = Buffer.Vowel.DotIntermediate
            emptyList()
        }
        else -> emptyList()
    }

    private fun transitionConcrete(current: Char, stroke: Char): List<Op> {
        // Post-extension revert: when the user is sitting on a "second-dot"
        // extension (ㅠ from ㅜ+ㆍ) and types ㅣ, they almost always meant the
        // diphthong ㅝ (ㅜ+ㅓ via composer compound), not ㅠ+ㅣ.  Peel the
        // extension, restore ㅜ, then emit ㅓ so HangulComposer's compoundVowel
        // table fuses ㅜ+ㅓ→ㅝ.  Setting buffer = Concrete('ㅓ') lets a follow-up
        // ㅣ extend to ㅔ → composer ㅜ+ㅔ→ㅞ → 뭬 for free.
        if (current == 'ㅠ' && stroke == STROKE_I) {
            buffer = Buffer.Vowel.Concrete('ㅓ')
            return listOf(Op.Undo, Op.Emit('ㅜ'), Op.Emit('ㅓ'))
        }

        // UndoEmit transitions: extend the current vowel into a richer one,
        // peeling the previous emit and pushing the upgrade.
        val extended = extendUndoEmit(current, stroke)
        if (extended != null) {
            buffer = Buffer.Vowel.Concrete(extended)
            return listOf(Op.Undo, Op.Emit(extended))
        }
        // No extension → commit current (already in HangulComposer) and
        // start a fresh vowel buffer with the new stroke.  HangulComposer's
        // own compound-vowel fusion (ㅗ+ㅏ→ㅘ etc.) handles the cross-vowel
        // combinations.
        return startVowelFromEmpty(stroke)
    }

    /**
     * Returns the upgraded vowel jamo for the [current] vowel + new [stroke],
     * or null if no extension applies (caller should commit + restart).
     */
    private fun extendUndoEmit(current: Char, stroke: Char): Char? = when (current) {
        'ㅣ' -> when (stroke) {
            STROKE_DOT -> 'ㅏ'   // ㅣ ㆍ
            else -> null
        }
        'ㅡ' -> when (stroke) {
            STROKE_DOT -> 'ㅜ'   // ㅡ ㆍ
            else -> null
        }
        'ㅏ' -> when (stroke) {
            STROKE_DOT -> 'ㅑ'   // ㅣ ㆍ ㆍ
            STROKE_I -> 'ㅐ'     // ㅣ ㆍ ㅣ
            else -> null
        }
        'ㅓ' -> when (stroke) {
            STROKE_I -> 'ㅔ'     // ㆍ ㅣ ㅣ
            else -> null
        }
        'ㅑ' -> when (stroke) {
            STROKE_I -> 'ㅒ'     // ㅣ ㆍ ㆍ ㅣ
            else -> null
        }
        'ㅕ' -> when (stroke) {
            STROKE_I -> 'ㅖ'     // ㆍ ㆍ ㅣ ㅣ
            else -> null
        }
        'ㅜ' -> when (stroke) {
            STROKE_DOT -> 'ㅠ'   // ㅡ ㆍ ㆍ
            else -> null
        }
        // ㅗ / ㅛ / ㅐ ㅒ ㅔ ㅖ / ㅠ — no further extension; let composer fuse
        // any cross-vowel combinations (ㅗ+ㅣ→ㅚ, ㅗ+ㅏ→ㅘ, etc.).
        else -> null
    }

    private fun decayBufferIfStale(nowMs: Long) {
        if (lastTapMs != 0L && nowMs - lastTapMs > WINDOW_MS) {
            buffer = Buffer.None
        }
    }

    companion object {
        /** Cycle window: same key within this many ms continues the cycle. */
        const val WINDOW_MS: Long = 1500L

        // Vowel stroke keys
        const val STROKE_I: Char = 'ㅣ'
        const val STROKE_DOT: Char = 'ㆍ'  // U+318D, Hangul archaic letter areah
        const val STROKE_EU: Char = 'ㅡ'

        /** Cheonjiin consonant cycles, keyed by visual primary. */
        val CONSONANT_CYCLES: Map<Char, List<Char>> = mapOf(
            'ㄱ' to listOf('ㄱ', 'ㅋ', 'ㄲ'),
            'ㄴ' to listOf('ㄴ', 'ㄹ'),
            'ㄷ' to listOf('ㄷ', 'ㅌ', 'ㄸ'),
            'ㅂ' to listOf('ㅂ', 'ㅍ', 'ㅃ'),
            'ㅅ' to listOf('ㅅ', 'ㅎ', 'ㅆ'),
            'ㅈ' to listOf('ㅈ', 'ㅊ', 'ㅉ'),
            'ㅇ' to listOf('ㅇ', 'ㅁ'),
        )
    }
}
