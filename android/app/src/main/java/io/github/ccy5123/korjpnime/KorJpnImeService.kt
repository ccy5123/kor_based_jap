package io.github.ccy5123.korjpnime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.ccy5123.korjpnime.engine.BatchimLookup
import io.github.ccy5123.korjpnime.engine.CheonjiinComposer
import io.github.ccy5123.korjpnime.engine.Connector
import io.github.ccy5123.korjpnime.engine.Dictionary
import io.github.ccy5123.korjpnime.engine.HangulComposer
import io.github.ccy5123.korjpnime.engine.RichDictionary
import io.github.ccy5123.korjpnime.engine.UserDict
import io.github.ccy5123.korjpnime.engine.Viterbi
import io.github.ccy5123.korjpnime.keyboard.KeyAction
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.keyboard.LocalHapticsEnabled
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.ThemeMode
import io.github.ccy5123.korjpnime.ui.SettingsActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * Kana → kanji dictionary, loaded once asynchronously from
     * `assets/jpn_dict.txt` in [onCreate].  Null candidates while loading.
     */
    private val dictionary = Dictionary()

    /**
     * M3 Viterbi engine — RichDictionary + Connector mmap'd from
     * `assets/kj_dict.bin` and `assets/kj_conn.bin`.  Once loaded, the
     * top-K segmented results surface ABOVE the simple-dict candidates.
     * Falls back silently when the engine isn't ready (still loading or
     * load failed).
     */
    private val richDict = RichDictionary()
    private val connector = Connector()
    private val viterbi: Viterbi by lazy { Viterbi(richDict, connector) }

    /**
     * Per-kana user pick history (SharedPreferences-backed).  Lazy because
     * SharedPreferences needs a Context — initialised once [onCreate] runs.
     */
    private lateinit var userDict: UserDict

    /**
     * Run of consecutive hiragana characters most recently committed to the
     * editor.  Updated by [emit] / [onCharsDeleted]; reset on any non-kana
     * commit, on candidate selection, on backspace into committed text past
     * the run, and on external clears.  This is what [refreshCandidates]
     * looks up against the dictionary.
     */
    private var currentKanaRun: String = ""

    private val _candidates = MutableStateFlow<List<String>>(emptyList())
    val candidates: StateFlow<List<String>> = _candidates.asStateFlow()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        userDict = UserDict(applicationContext)
        // Cache haptics preference for handleAction's sync read.  Mode is read
        // directly via collectAsState in onCreateInputView's composable since
        // that path is already async-friendly.
        lifecycleScope.launch {
            KeyboardPreferences.hapticsFlow(applicationContext).collect { hapticsEnabled = it }
        }
        lifecycleScope.launch {
            // Async dict load — ~19 MB, takes ~200 ms on Note20 first run.
            // Candidates stay empty until this completes.
            dictionary.load(applicationContext)
            refreshCandidates()  // in case kana was already committed pre-load
        }
        lifecycleScope.launch {
            // Viterbi data is bigger (~71 MB across two files) and gets
            // extracted to internal storage on first run, so first-launch
            // load is ~1–2 s.  Subsequent launches just mmap the cached
            // copy and finish in tens of ms.
            richDict.load(applicationContext)
            connector.load(applicationContext)
            refreshCandidates()
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return KorJpnImeView(context = this, owner = this) {
            val mode by KeyboardPreferences.modeFlow(applicationContext)
                .collectAsState(initial = KeyboardMode.BEOLSIK)
            val directionId by KeyboardPreferences.directionFlow(applicationContext)
                .collectAsState(initial = KeyboardPreferences.DEFAULT_DIRECTION_ID)
            val themeMode by KeyboardPreferences.themeModeFlow(applicationContext)
                .collectAsState(initial = ThemeMode.AUTO)
            val haptics by KeyboardPreferences.hapticsFlow(applicationContext)
                .collectAsState(initial = true)
            val candidateList by candidates.collectAsState()

            val direction = DIRECTIONS.firstOrNull { it.id == directionId } ?: DIRECTIONS.first()
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeMode) {
                ThemeMode.AUTO -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MaterialTheme {
                CompositionLocalProvider(LocalHapticsEnabled provides haptics) {
                    KeyboardSurface(
                        direction = direction,
                        dark = dark,
                        mode = mode,
                        candidates = candidateList,
                        onCandidatePick = ::handleCandidatePick,
                        onAction = ::handleAction,
                        onSettingsClick = ::openSettings,
                    )
                }
            }
        }
    }

    /**
     * Commit [text] to the editor and update the kana-run tracker.  All
     * commit paths route through here so [currentKanaRun] / [candidates]
     * stay in sync with the editor's actual content.
     */
    private fun emit(ic: InputConnection, text: String) {
        if (text.isEmpty()) return
        ic.commitText(text, 1)
        if (isAllHiragana(text)) {
            currentKanaRun += text
        } else {
            // Non-hiragana terminator (kana mixed with kanji / punct / digit /
            // English / etc.).  Run breaks; subsequent kana starts fresh.
            currentKanaRun = ""
        }
        refreshCandidates()
    }

    /** True if every char in [s] is hiragana (U+3040..U+309F). */
    private fun isAllHiragana(s: String): Boolean = s.isNotEmpty() && s.all { it.code in 0x3040..0x309F }

    /** Update [currentKanaRun] after [count] chars deleted from the editor. */
    private fun onCharsDeleted(count: Int) {
        if (currentKanaRun.isEmpty() || count <= 0) return
        currentKanaRun = if (count >= currentKanaRun.length) "" else currentKanaRun.dropLast(count)
        refreshCandidates()
    }

    private fun refreshCandidates() {
        if (currentKanaRun.isEmpty()) {
            _candidates.value = emptyList()
            return
        }
        val combined = LinkedHashSet<String>()
        // 1. User pick history (most-recent-first).
        combined.addAll(userDict.getUserCandidates(currentKanaRun))
        // 2. Viterbi top-K segmented kanji conversions — the M3 engine.
        //    Surfaces best-cost segmentations like わたしの → 私の that the
        //    simple dict can't produce because it requires an exact-key
        //    match.  Skipped when richDict / connector haven't finished
        //    loading yet; the simple dict below still fires.
        if (viterbi.isReady) {
            val topK = viterbi.searchTopK(currentKanaRun, VITERBI_TOP_K)
            for (r in topK) combined.add(r.joinedSurface())
        }
        // 3. Static dictionary candidates (most-frequent-first), capped to
        //    leave room for the katakana + hiragana fallbacks below — even
        //    when a high-frequency kana like あい has many kanji entries.
        if (dictionary.isLoaded) {
            val dictBudget = (MAX_CANDIDATES - 2).coerceAtLeast(0)
            combined.addAll(dictionary.lookup(currentKanaRun).take(dictBudget))
        }
        // 4. Auto-katakana fallback — guaranteed slot regardless of dict size.
        val katakana = hiraganaToKatakana(currentKanaRun)
        if (katakana != currentKanaRun) combined.add(katakana)
        // 5. Raw hiragana stay-as-is.
        combined.add(currentKanaRun)
        _candidates.value = combined.toList().take(MAX_CANDIDATES)
    }

    /** Hiragana (U+3041..U+3096) → Katakana (U+30A1..U+30F6) by +0x60 shift. */
    private fun hiraganaToKatakana(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c.code in 0x3041..0x3096) (c.code + 0x60).toChar() else c)
        }
        return sb.toString()
    }

    /**
     * Replace the recent kana run with the user-picked kanji.  Wrapped in a
     * batch so the delete + commit pair applies atomically.  Records the
     * pick in [UserDict] so the same kana surfaces this kanji first next
     * time.
     */
    private fun handleCandidatePick(kanji: String) {
        val ic = currentInputConnection ?: return
        val pickedFromKana = currentKanaRun
        if (pickedFromKana.isEmpty()) return
        userDict.recordPick(pickedFromKana, kanji)
        batched(ic) {
            ic.deleteSurroundingText(pickedFromKana.length, 0)
            ic.commitText(kanji, 1)
        }
        currentKanaRun = ""
        refreshCandidates()
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
        resetAllState()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        // True end of input session (target field is gone).  Belt-and-suspenders
        // reset for cases where onFinishInputView didn't fire.
        resetAllState()
        super.onFinishInput()
    }

    /** Drop composer / Cheonjiin / kana-run state and clear candidates. */
    private fun resetAllState() {
        composer.reset()
        cheonjiin.reset()
        currentKanaRun = ""
        refreshCandidates()
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
            resetAllState()
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun handleAction(action: KeyAction) {
        val ic = currentInputConnection ?: return
        // Haptic now fires from the UI's press-down handler (Key.kt /
        // BackspaceKey.kt) instead of here on tap-up — the prior tap-up
        // path had perceptible (~100 ms) latency.  performHapticIfEnabled
        // is kept on the class as a fallback but no longer invoked per-tap.
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
            is KeyAction.CjVowel -> handleCjVowel(ic, action.stroke)
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
     * Space behaviour, layered (each takes precedence over the next):
     *
     *  1. Cheonjiin mid-consonant-cycle (e.g., user just tapped ㄴ for 안's
     *     jong and may tap ㄴ again for 녕's cho): break the cycle window,
     *     no editor change.  Lets `안녕` be typed without an intervening
     *     literal space.
     *  2. Hangul composer non-empty (preedit shown): flush only — commit the
     *     pending kana (e.g., 루 → る) without inserting a literal space.
     *     Mirrors standard Japanese IME convention where Space converts /
     *     finalises the in-progress reading and the user picks a candidate
     *     next.  Tapping Space again (now with composer empty) inserts the
     *     literal space.
     *  3. Both empty: literal space.
     */
    private fun handleSpace(ic: InputConnection) {
        if (cheonjiin.isInConsonantCycle()) {
            cheonjiin.reset()
            return
        }
        cheonjiin.reset()
        if (!composer.empty()) {
            batched(ic) { flushComposerInner(ic) }
            return
        }
        batched(ic) { emit(ic, " ") }
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
                        emit(ic, op.c.toString())
                    }
                    is CheonjiinComposer.PunctOp.Replace -> {
                        ic.deleteSurroundingText(1, 0)
                        onCharsDeleted(1)
                        emit(ic, op.next.toString())
                    }
                }
            }
        }
    }

    /**
     * Cheonjiin vowel-stroke handler with a particle-marker shortcut.
     *
     * The 12-key keypad has no direct ㅏ / ㅔ / ㅗ keys, so the second-vowel
     * trigger that fires kWoMarker / kWaMarker / kEMarker on Beolsik (오+ㅗ,
     * 와+ㅏ, 에+ㅔ) is unreachable.  We re-bind it to a single ㆍ tap on top
     * of the matching syllable: 오/와/에 already in the composer (silent ㅇ
     * + ㅗ/ㅘ/ㅔ jung), tap ㆍ → feed the matching trigger jamo to
     * HangulComposer, which emits the marker via its existing CHO_JUNG
     * branch.  The Cheonjiin state machine is bypassed for this case.
     */
    private fun handleCjVowel(ic: InputConnection, stroke: Char) {
        if (stroke == CheonjiinComposer.STROKE_DOT) {
            val triggerJamo = particleMarkerTrigger()
            if (triggerJamo != null) {
                cheonjiin.reset()
                handleCjOps(ic, listOf(CheonjiinComposer.Op.Emit(triggerJamo)))
                return
            }
        }
        handleCjOps(
            ic,
            cheonjiin.tapVowel(stroke, System.currentTimeMillis(), composer.currentRawJung()),
        )
    }

    /**
     * Returns the jamo to feed [HangulComposer.input] so that
     * its existing particle-marker branch fires for the current state, or
     * null when the composer isn't sitting on one of the trigger syllables.
     * Marker patterns require silent-ㅇ cho + a specific compound/single
     * vowel; the composer recognises them via `cho==11 && rawJung==jamo`.
     */
    private fun particleMarkerTrigger(): Char? {
        // Markers fire only on an open syllable (cho + jung, no jong).
        // Without this guard, 엗 + ㆍ — user mid-typing 에도 with ㄷ-jong
        // captured — would mis-trigger kEMarker because the cho+jung
        // pair (ㅇ, ㅔ) still matches.  HangulComposer's own Beolsik
        // marker logic lives in its CHO_JUNG branch, never CHO_JUNG_JONG;
        // we mirror that constraint here.
        if (!composer.isOpenChoJung()) return null
        if (composer.currentChoJamo() != 'ㅇ') return null
        return when (composer.currentRawJung()) {
            'ㅗ' -> 'ㅗ'  // 오 + ㆍ → kWoMarker → を
            'ㅘ' -> 'ㅏ'  // 와 + ㆍ → kWaMarker → は
            'ㅔ' -> 'ㅔ'  // 에 + ㆍ → kEMarker → へ
            else -> null
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
                        if (finalized.isNotEmpty()) {
                            val kana = convertHangulToKana(finalized, composer.currentChoJamo())
                            emit(ic, kana)
                        }
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
            resetAllState()
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
                if (finalized.isNotEmpty()) {
                    val kana = convertHangulToKana(finalized, composer.currentChoJamo())
                    emit(ic, kana)
                }
                // Preedit shows the in-progress Hangul syllable so the user
                // sees what they're typing; the kana commit happens once the
                // syllable closes (above branch).
                ic.setComposingText(composer.preedit(), 1)
            } else {
                // Punctuation, digits, kana, anything non-jamo — flush
                // in-progress syllable first so it commits BEFORE the new text.
                flushComposerInner(ic)
                emit(ic, digitsToFullWidth(text))
            }
        }
    }

    /**
     * Convert ASCII digits to full-width Japanese counterparts (０..９,
     * U+FF10..U+FF19).  Applied in [handleCommit]'s non-jamo branch so
     * every digit the user types in our IME's Japanese-output context
     * gets the Japanese-correct width.  Non-digit chars pass through
     * unchanged.  No-op when there are no ASCII digits.
     */
    private fun digitsToFullWidth(text: String): String {
        if (text.isEmpty()) return text
        if (!text.any { it in '0'..'9' }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c in '0'..'9') (c.code - '0'.code + 0xFF10).toChar() else c)
        }
        return sb.toString()
    }

    /**
     * Convert a finalized Hangul string (one or more syllables emitted by
     * [HangulComposer.input] or [HangulComposer.flush]) into the kana to
     * commit.  Each char's `nextJamo` lookahead is the next char's leading
     * cho, falling back to [fallbackNext] for the last char (typically the
     * composer's new cho after migration, or NUL at flush boundaries).
     */
    private fun convertHangulToKana(finalized: String, fallbackNext: Char): String {
        val sb = StringBuilder()
        for (i in finalized.indices) {
            val nextJamo = if (i + 1 < finalized.length) {
                BatchimLookup.firstCho(finalized[i + 1])
            } else {
                fallbackNext
            }
            sb.append(BatchimLookup.lookup(finalized[i], nextJamo))
        }
        return sb.toString()
    }

    private fun handleBackspace(ic: InputConnection) {
        batched(ic) {
            if (!composer.empty()) {
                composer.undoLastJamo()
                // Empty preedit clears the composing region; non-empty replaces it.
                ic.setComposingText(composer.preedit(), 1)
            } else {
                ic.deleteSurroundingText(1, 0)
                onCharsDeleted(1)
            }
        }
    }

    /**
     * Commit any in-progress syllable.  Wrap with [batched] yourself when
     * combining with other IC operations in the same handleAction branch.
     * Terminal flush — no following jamo, so [BatchimLookup.suffix] sees
     * NUL nextJamo (sokuon_terminal branch fires for ㅅ/ㅆ jong).
     */
    private fun flushComposerInner(ic: InputConnection) {
        if (composer.empty()) return
        val flushed = composer.flush()
        if (flushed.isNotEmpty()) {
            val kana = convertHangulToKana(flushed, ' ')
            emit(ic, kana)
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
            emit(ic, "\n")
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

    companion object {
        // Enough to fill several scrolls + room for the expanded panel.
        // Beyond this users want the full grid view (vertical expand).
        private const val MAX_CANDIDATES = 32

        /**
         * Viterbi top-K depth — small because near-duplicates of the top
         * pick provide diminishing return in the candidate strip, and
         * each extra K linearly grows the per-position lattice memory.
         * Up to [Viterbi.MAX_TOP_K] (10) internally; 5 fits the strip
         * without crowding out the simple-dict + katakana fallbacks.
         */
        private const val VITERBI_TOP_K = 5
    }
}
