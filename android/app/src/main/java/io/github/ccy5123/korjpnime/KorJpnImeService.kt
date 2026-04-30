package io.github.ccy5123.korjpnime

import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
import io.github.ccy5123.korjpnime.engine.ClipboardHistory
import io.github.ccy5123.korjpnime.engine.EmojiData
import io.github.ccy5123.korjpnime.engine.EmojiHistory
import io.github.ccy5123.korjpnime.engine.Connector
import io.github.ccy5123.korjpnime.engine.Dictionary
import io.github.ccy5123.korjpnime.engine.HangulComposer
import io.github.ccy5123.korjpnime.engine.HanjaDictionary
import io.github.ccy5123.korjpnime.engine.RichDictionary
import io.github.ccy5123.korjpnime.engine.UserDict
import io.github.ccy5123.korjpnime.engine.Viterbi
import io.github.ccy5123.korjpnime.engine.WordlistDictionary
import io.github.ccy5123.korjpnime.keyboard.KeyAction
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.keyboard.LocalHapticsEnabled
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.InputLanguage
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.ThemeMode
import io.github.ccy5123.korjpnime.ui.SettingsActivity
import io.github.ccy5123.korjpnime.voice.VoiceInputActivity
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
     * Latest input-language mode cached from [KeyboardPreferences.inputLanguageFlow].
     * Read by [digitsToFullWidth] to gate ASCII→full-width conversion (only
     * fires in JAPANESE mode).  @Volatile for the same reason as
     * [hapticsEnabled].
     */
    @Volatile private var inputLanguage: InputLanguage = InputLanguage.JAPANESE

    /**
     * User-tunable cap on the candidate strip — lower values surface
     * fewer kanji at a glance but also let users with smaller screens
     * keep the strip scannable.  Default matches the prior hard-coded
     * MAX_CANDIDATES (32).
     */
    @Volatile private var candidateCount: Int = KeyboardPreferences.DEFAULT_CANDIDATE_COUNT

    /**
     * Kana → kanji dictionary, loaded once asynchronously from
     * `assets/jpn_dict.txt` in [onCreate].  Null candidates while loading.
     */
    private val dictionary = Dictionary()

    /**
     * Hangul → Hanja syllable dictionary.  Loaded once asynchronously
     * from `assets/hanja_dict.txt`.  Used only when the user taps the
     * 한자 key in Korean mode; results surface in the candidate strip.
     */
    private val hanjaDictionary = HanjaDictionary()

    /**
     * Per-language prefix-completion wordlists.  Loaded asynchronously
     * from `assets/ko_words.txt` / `assets/en_words.txt` (FrequencyWords
     * MIT, see THIRD_PARTY_LICENSES.txt).  Consulted only in KOR / ENG
     * input modes — JP mode keeps its existing kanji candidate pipeline.
     */
    private val korWordlist = WordlistDictionary("ko_words.txt")
    private val enWordlist = WordlistDictionary("en_words.txt")

    /**
     * Word currently being typed in KOR / ENG mode.  Grows by one char
     * per [emit] of a Hangul syllable / ASCII letter; resets on space /
     * punctuation / mode switch / cursor move.  Drives the autocomplete
     * suggestions in the candidate strip.
     */
    private var currentWordPrefix: String = ""

    /**
     * Hangul syllable currently being offered for Hanja conversion.
     * Set by [handleHanja] when the user taps 한자; cleared on the
     * next non-Hanja action (so picking a candidate replaces this
     * syllable with the chosen Hanja, then disengages Hanja mode).
     */
    private var hanjaConversionSyllable: Char? = null

    /**
     * Most recent committed kana → kanji pick (`(kana, picked_kanji)`).
     * Updated in [handleCandidatePick] every time the user accepts a
     * kanji from the strip.  Consulted by [handleReconvert] (the JP-mode
     * 再変換 key) to undo the commit and re-surface the candidate strip
     * so the user can swap to a different kanji for the same reading.
     */
    private var lastKanaPick: Pair<String, String>? = null

    /**
     * Right boundary of the active "conversion window" within
     * [currentKanaRun].  When `-1` (default), the strip looks up candidates
     * for the FULL kana run — the original whole-string conversion path.
     * When `>= 0`, the user has tapped ◀ / ▶ to manually adjust the
     * window: the strip looks up `currentKanaRun[0..conversionBoundary)`
     * and a candidate pick replaces only that slice (any remaining kana
     * stays as a fresh composing region for follow-up conversion).  Reset
     * to `-1` whenever new kana is appended (the user's just-typed char
     * invalidates the previous boundary).
     */
    private var conversionBoundary: Int = -1

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
     * Most-recent-first clipboard history, populated by a system
     * [ClipboardManager.OnPrimaryClipChangedListener] registered in
     * [onCreate].  Surfaced by the ⋯ menu's 클립보드 entry as a
     * tap-to-paste panel above the keyboard.
     */
    private lateinit var clipboardHistory: ClipboardHistory

    /** StateFlow mirror of [clipboardHistory] so the Compose UI recomposes when
     *  new items arrive.  Updated synchronously from the change listener. */
    private val _clipboardItems = MutableStateFlow<List<String>>(emptyList())
    val clipboardItems: StateFlow<List<String>> = _clipboardItems.asStateFlow()

    /**
     * Bundled Unicode emoji data + per-user history of recently picked
     * emojis.  Surfaced by the ⋯ menu's 이모지 entry as a tab-and-grid
     * panel above the keyboard.
     */
    private val emojiData = EmojiData()
    private lateinit var emojiHistory: EmojiHistory
    private val _emojiRecents = MutableStateFlow<List<String>>(emptyList())
    val emojiRecents: StateFlow<List<String>> = _emojiRecents.asStateFlow()

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return@OnPrimaryClipChangedListener
        val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener
        if (clip.itemCount == 0) return@OnPrimaryClipChangedListener
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isNotEmpty()) {
            clipboardHistory.add(text)
            _clipboardItems.value = clipboardHistory.items
        }
    }

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
        clipboardHistory = ClipboardHistory(applicationContext)
        _clipboardItems.value = clipboardHistory.items
        emojiHistory = EmojiHistory(applicationContext)
        _emojiRecents.value = emojiHistory.items
        // Register the clipboard change listener so any copy events that
        // happen while the IME is alive get appended to the history.
        // (Android 10+ background apps can't read the clipboard, but our
        // IME is foreground while attached to an editor.)
        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.addPrimaryClipChangedListener(clipChangedListener)
        // Cache haptics preference for handleAction's sync read.  Mode is read
        // directly via collectAsState in onCreateInputView's composable since
        // that path is already async-friendly.
        lifecycleScope.launch {
            KeyboardPreferences.hapticsFlow(applicationContext).collect { hapticsEnabled = it }
        }
        lifecycleScope.launch {
            KeyboardPreferences.inputLanguageFlow(applicationContext).collect { inputLanguage = it }
        }
        lifecycleScope.launch {
            KeyboardPreferences.candidateCountFlow(applicationContext).collect {
                candidateCount = it
                refreshCandidates()  // recompute with new cap
            }
        }
        lifecycleScope.launch {
            // Async dict load — ~19 MB, takes ~200 ms on Note20 first run.
            // Candidates stay empty until this completes.
            dictionary.load(applicationContext)
            refreshCandidates()  // in case kana was already committed pre-load
        }
        lifecycleScope.launch {
            // Hangul→Hanja dict — small (~430 KB / 28 K entries), loads in
            // tens of ms.  Only consulted when the user taps the 한자 key.
            hanjaDictionary.load(applicationContext)
        }
        lifecycleScope.launch {
            // KOR / ENG prefix-completion wordlists — ~600 KB each, ~50 K
            // entries.  Used by [refreshCandidates] in those modes.
            korWordlist.load(applicationContext)
            enWordlist.load(applicationContext)
            refreshCandidates()  // in case prefix was already set pre-load
        }
        lifecycleScope.launch {
            // Emoji panel data (~50 KB, ~1900 base emojis).  Loaded once,
            // surfaced on first ⋯ → 이모지 panel open.
            emojiData.load(applicationContext)
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
            val heightDp by KeyboardPreferences.heightFlow(applicationContext)
                .collectAsState(initial = KeyboardPreferences.DEFAULT_HEIGHT_DP)
            val inputLang by KeyboardPreferences.inputLanguageFlow(applicationContext)
                .collectAsState(initial = InputLanguage.JAPANESE)
            val candidateList by candidates.collectAsState()
            val clipboardList by clipboardItems.collectAsState()
            val emojiRecentList by emojiRecents.collectAsState()

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
                        heightDp = heightDp,
                        inputLanguage = inputLang,
                        onLanguageCycle = ::cycleInputLanguage,
                        candidates = candidateList,
                        onCandidatePick = ::handleCandidatePick,
                        onAction = ::handleAction,
                        onSettingsClick = ::openSettings,
                        onSystemImeSettings = ::openSystemImeSettings,
                        onVoiceInput = ::openVoiceInput,
                        clipboardItems = clipboardList,
                        onClipboardPick = ::handleClipboardPick,
                        onClipboardDelete = { item ->
                            clipboardHistory.remove(item)
                            _clipboardItems.value = clipboardHistory.items
                        },
                        emojiCategories = emojiData.categories(),
                        emojiRecents = emojiRecentList,
                        onEmojiPick = ::handleEmojiPick,
                    )
                }
            }
        }
    }

    /**
     * Commit [text] to the editor and update the kana-run tracker.  All
     * commit paths route through here so [currentKanaRun] / [candidates]
     * stay in sync with the editor's actual content.
     *
     * **JP mode (hiragana)**: kana goes into the editor's COMPOSING region
     * (rendered with the system underline) so the user sees what's the
     * current conversion target — addresses the "어디부터 어디까지가
     * 변환의 대상인지 가끔 헷갈린다" feedback.  [currentKanaRun]
     * accumulates; the composing region width grows with it until the user
     * picks a candidate (which calls commitText → composing region replaced
     * by the chosen kanji) or types something that breaks the run (punct /
     * digits / cursor / mode switch — see the else branch).  Caller
     * (handleCommit / handleCjOps) appends [HangulComposer.preedit] to the
     * composing region after this call so the in-progress jamo stays
     * visible alongside the accumulated kana.
     *
     * **Non-hiragana**: Hangul (KOR mode), ASCII (ENG / punctuation /
     * digits), newline.  Finalises any pending kana run as committed text
     * first (the user's moving on from kana conversion) then commits the
     * new text.  KOR / ENG modes never accumulate kana, so currentKanaRun
     * stays "" and finishComposingText is a no-op the first time through.
     */
    private fun emit(ic: InputConnection, text: String) {
        if (text.isEmpty()) return
        if (isAllHiragana(text)) {
            currentKanaRun += text
            // New kana invalidates any prior boundary the user set via
            // ◀ / ▶ — they tapped through to land on the previous run's
            // shape, not this longer one.
            conversionBoundary = -1
            ic.setComposingText(composingSpannable(), 1)
        } else {
            if (currentKanaRun.isNotEmpty()) {
                ic.finishComposingText()
                currentKanaRun = ""
            }
            conversionBoundary = -1
            updateWordPrefix(text)
            ic.commitText(text, 1)
        }
        refreshCandidates()
    }

    /**
     * Track the in-progress word for KOR / ENG prefix-completion as
     * committed text accumulates.  Each [emit] of a single Hangul syllable
     * (KOR) or ASCII letter (ENG) extends [currentWordPrefix]; anything
     * else (punct, space, multi-char emissions, JP mode) resets it.  The
     * prefix is what [refreshCandidates] looks up against the wordlist.
     */
    private fun updateWordPrefix(text: String) {
        val isKor = inputLanguage == InputLanguage.KOREAN
        val isEng = inputLanguage == InputLanguage.ENGLISH
        if (!isKor && !isEng) {
            currentWordPrefix = ""
            return
        }
        if (text.length == 1) {
            val c = text[0]
            val isWordChar = (isKor && c in '가'..'힣') ||
                (isEng && c.isLetter())
            currentWordPrefix = if (isWordChar) currentWordPrefix + c else ""
        } else {
            currentWordPrefix = ""
        }
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
        // KOR / ENG prefix-completion path — orthogonal to JP's kana run.
        if (inputLanguage != InputLanguage.JAPANESE) {
            _candidates.value = lookupWordPrefixCandidates()
            return
        }
        if (currentKanaRun.isEmpty()) {
            _candidates.value = emptyList()
            return
        }
        // Active conversion window — full run when conversionBoundary < 0
        // (the user hasn't tapped ◀ / ▶), otherwise the user's chosen
        // prefix.  Lookups ALL run against this slice so the strip
        // matches what the next pick will replace.
        val effective = effectiveConversionWindow()
        val combined = LinkedHashSet<String>()
        // 1. User pick history (most-recent-first).
        combined.addAll(userDict.getUserCandidates(InputLanguage.JAPANESE, effective))
        // 2. Viterbi top-K segmented kanji conversions — the M3 engine.
        //    Surfaces best-cost segmentations like わたしの → 私の that the
        //    simple dict can't produce because it requires an exact-key
        //    match.  Skipped when richDict / connector haven't finished
        //    loading yet; the simple dict below still fires.
        if (viterbi.isReady) {
            val topK = viterbi.searchTopK(effective, VITERBI_TOP_K)
            for (r in topK) combined.add(r.joinedSurface())
        }
        // 3. Static dictionary candidates (most-frequent-first), capped to
        //    leave room for the katakana + hiragana fallbacks below — even
        //    when a high-frequency kana like あい has many kanji entries.
        val cap = candidateCount
        if (dictionary.isLoaded) {
            val dictBudget = (cap - 2).coerceAtLeast(0)
            combined.addAll(dictionary.lookup(effective).take(dictBudget))
        }
        // 4. Auto-katakana fallback — guaranteed slot regardless of dict size.
        val katakana = hiraganaToKatakana(effective)
        if (katakana != effective) combined.add(katakana)
        // 5. Raw hiragana stay-as-is.
        combined.add(effective)
        _candidates.value = combined.toList().take(cap)
    }

    /**
     * Top-N word completions for the current KOR / ENG prefix.  ENG
     * prefix-matches case-insensitively but capitalises results to match
     * the prefix's leading-letter case so picking "He" → "Hello" not
     * "hello" — a small but expected behaviour for English users.
     */
    private fun lookupWordPrefixCandidates(): List<String> {
        // KOR mode: include the in-progress Hangul preedit ("기" while the
        // syllable is still being built but not yet finalised) so the
        // strip stays in lockstep with what the user is seeing in the
        // editor — without this, suggestions only update when the NEXT
        // syllable starts (the user's "lag by one keystroke" bug).
        // ENG mode: composer is always empty so preedit is "".
        val effective = currentWordPrefix + composer.preedit()
        if (effective.isEmpty()) return emptyList()
        // LinkedHashSet preserves insertion order while de-duplicating —
        // user-pick history surfaces FIRST so frequent personal picks
        // outrank generic-frequency wordlist hits.
        val combined = LinkedHashSet<String>()
        return when (inputLanguage) {
            InputLanguage.KOREAN -> {
                combined.addAll(userDict.getUserCandidates(InputLanguage.KOREAN, effective))
                combined.addAll(korWordlist.lookup(effective, candidateCount))
                combined.toList().take(candidateCount)
            }
            InputLanguage.ENGLISH -> {
                val lower = effective.lowercase()
                val capitalize = effective.firstOrNull()?.isUpperCase() == true
                fun cased(w: String) =
                    if (capitalize) w.replaceFirstChar { it.uppercase() } else w
                // UserDict stored under lowercased prefix so picks across
                // capitalisation styles share the same history.
                combined.addAll(
                    userDict.getUserCandidates(InputLanguage.ENGLISH, lower).map { cased(it) }
                )
                combined.addAll(enWordlist.lookup(lower, candidateCount).map { cased(it) })
                combined.toList().take(candidateCount)
            }
            else -> emptyList()
        }
    }

    /** kana run slice that the strip / pick are currently operating on. */
    private fun effectiveConversionWindow(): String =
        if (conversionBoundary < 0) currentKanaRun
        else currentKanaRun.substring(0, conversionBoundary.coerceIn(0, currentKanaRun.length))

    /**
     * Build the composing-region content for the editor: kana run +
     * in-progress Hangul preedit, with a soft-gold background highlight on
     * the active conversion window when [conversionBoundary] has been
     * narrowed by ◀ / ▶.  When the window is the full run (boundary -1),
     * returns plain text — the standard composing-region underline is
     * enough on its own.
     */
    private fun composingSpannable(): CharSequence {
        val full = currentKanaRun + composer.preedit()
        if (full.isEmpty()) return ""
        if (conversionBoundary < 0 || currentKanaRun.isEmpty()) return full
        val spannable = SpannableString(full)
        val end = conversionBoundary.coerceAtMost(currentKanaRun.length)
        spannable.setSpan(
            BackgroundColorSpan(BUNSETSU_HIGHLIGHT_COLOR),
            0, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return spannable
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
     * Replace the recent kana run (or the Hangul syllable being offered for
     * Hanja conversion) with the user's picked candidate.  Wrapped in a
     * batch so the delete + commit pair applies atomically.  Records the
     * pick in [UserDict] when it's a kana → kanji conversion so the same
     * kana surfaces this kanji first next time.
     */
    private fun handleCandidatePick(picked: String) {
        val ic = currentInputConnection ?: return

        // Hanja path takes precedence: if the 한자 key just primed a syllable,
        // a candidate tap replaces THAT syllable (committed Hangul) rather
        // than the kana run.  Candidates carry a "<meaning> <hanja>" display
        // form (e.g. "백성 民") for disambiguation; we commit only the
        // trailing Hanja character.  Gated to one tap — picking clears the
        // priming so subsequent taps fall back to kana mode.
        val hanjaSyllable = hanjaConversionSyllable
        if (hanjaSyllable != null) {
            val hanja = picked.lastOrNull()?.toString().orEmpty()
            if (hanja.isNotEmpty()) {
                batched(ic) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(hanja, 1)
                }
            }
            hanjaConversionSyllable = null
            _candidates.value = emptyList()
            return
        }

        // KOR / ENG autocomplete (Hanja didn't fire) — replace the
        // in-progress prefix with the picked word.
        //
        // The prefix has TWO layers:
        //   - currentWordPrefix in committed text (finalised syllables /
        //     letters since the last word boundary).
        //   - composer.preedit() in the composing region (the in-progress
        //     Hangul syllable, e.g. "기" while building 기 → 기다 → ...).
        // deleteSurroundingText drops only the committed half; commitText
        // then replaces the composing region with the picked word in the
        // same atomic batch.  Both layers cleared after.
        if (inputLanguage != InputLanguage.JAPANESE) {
            val committedPrefix = currentWordPrefix
            val preedit = composer.preedit()
            if (committedPrefix.isEmpty() && preedit.isEmpty()) return
            // Record the pick under the FULL prefix (committed + preedit)
            // so a future re-typing of the same prefix surfaces this
            // word first.  ENG records under the lowercased prefix so
            // picks across capitalisation styles share one history.
            val pickHistoryKey = when (inputLanguage) {
                InputLanguage.ENGLISH -> (committedPrefix + preedit).lowercase()
                else -> committedPrefix + preedit
            }
            val pickHistoryValue = when (inputLanguage) {
                InputLanguage.ENGLISH -> picked.lowercase()
                else -> picked
            }
            userDict.recordPick(inputLanguage, pickHistoryKey, pickHistoryValue)
            composer.reset()
            batched(ic) {
                if (committedPrefix.isNotEmpty()) {
                    ic.deleteSurroundingText(committedPrefix.length, 0)
                }
                ic.commitText(picked, 1)
            }
            currentWordPrefix = ""
            refreshCandidates()
            return
        }

        val window = effectiveConversionWindow()
        if (window.isEmpty()) return
        userDict.recordPick(InputLanguage.JAPANESE, window, picked)
        // Composing region holds the entire kana run; pick replaces only
        // the active window.  When the user adjusted the boundary via
        // ◀ / ▶ there's a tail (currentKanaRun beyond the window) that
        // should stay convertible — re-establish it as a fresh composing
        // region after committing the picked text.
        val tail = if (conversionBoundary < 0) ""
                   else currentKanaRun.substring(window.length)
        batched(ic) {
            ic.commitText(picked, 1)
            if (tail.isNotEmpty()) {
                ic.setComposingText(tail, 1)
            }
        }
        // Stash the (kana, picked) pair so the JP-mode 再変換 key can roll
        // back this commit and re-surface candidates.  Replaced on every
        // subsequent pick — only the IMMEDIATE last commit is reconvertible.
        lastKanaPick = window to picked
        currentKanaRun = tail
        conversionBoundary = -1
        refreshCandidates()
    }

    /**
     * Japanese 再変換 (reconvert) handler.  If the most recent kana → kanji
     * pick is still sitting at the cursor (text-before-cursor matches the
     * stored picked kanji), peel it back off the editor and restore
     * [currentKanaRun] to the original kana so [refreshCandidates] re-
     * surfaces the strip — the user can then pick a different kanji for
     * the same reading without backspacing.
     *
     * No-op when:
     *   - no recent pick is stored (fresh session, post-flush, etc.);
     *   - the cursor moved or other text was committed since the pick
     *     (the picked kanji is no longer the immediate text-before-cursor);
     *   - the input language isn't JAPANESE — the key is wired for KOR
     *     (Hanja) and JP (Reconvert) only, but defensive guard here.
     */
    private fun handleReconvert(ic: InputConnection) {
        if (inputLanguage != InputLanguage.JAPANESE) return
        val (kana, picked) = lastKanaPick ?: return
        val before = ic.getTextBeforeCursor(picked.length, 0)?.toString() ?: ""
        if (before != picked) return  // intervening edit — not safe to reconvert
        batched(ic) {
            ic.deleteSurroundingText(picked.length, 0)
            // Restore the kana as a composing region (system underline) so
            // the user visually sees what's the conversion target after
            // reconvert.  Without this the kanji would just disappear and
            // the kana would live only in the candidate strip — the user
            // reads that as "사시미가 사라졌다".
            ic.setComposingText(kana, 1)
        }
        currentKanaRun = kana
        // Don't clear lastKanaPick yet — the next handleCandidatePick will
        // replace it.  refreshCandidates rebuilds the strip from kanaRun.
        refreshCandidates()
    }

    /**
     * Cycle the input-language preference 한 → 영 → 일 → 한.  Triggered by
     * the dedicated cycle button on the letters page (replaces the prior 2-
     * state 한/영 toggle).  The UI re-renders via the inputLanguageFlow
     * collectAsState; symbol page content + space-bar label switch in lockstep.
     *
     * Flushes any in-progress composer syllable in the CURRENT language
     * first — otherwise switching mid-syllable would commit the half-built
     * jamo as the new language's output (e.g. "ㄱㅏ" pending in JP, switch
     * to KOR, would surface "가" as Korean instead of "か" the user
     * intended).  Plus we drop the kana run so KOR→JP doesn't carry stale
     * candidate context from the previous mode.
     */
    private fun cycleInputLanguage() {
        val ic = currentInputConnection
        if (ic != null) batched(ic) {
            flushComposerInner(ic)
            // After flush, any accumulated kana run is sitting in the
            // composing region.  Finalize it as committed text before the
            // mode switch so it doesn't visually carry over (and so it
            // doesn't get accidentally replaced when the next mode emits
            // into the same region).
            if (currentKanaRun.isNotEmpty()) ic.finishComposingText()
        }
        currentKanaRun = ""
        currentWordPrefix = ""
        refreshCandidates()
        val next = when (inputLanguage) {
            InputLanguage.KOREAN -> InputLanguage.ENGLISH
            InputLanguage.ENGLISH -> InputLanguage.JAPANESE
            InputLanguage.JAPANESE -> InputLanguage.KOREAN
        }
        lifecycleScope.launch {
            KeyboardPreferences.setInputLanguage(applicationContext, next)
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Launch the voice-input bridge activity from the ⋯ menu.  The
     * activity fronts the system speech dialog; on success it stashes
     * the transcript in SharedPreferences and our [onStartInputView]
     * polls + commits when the keyboard re-attaches.
     */
    private fun openVoiceInput() {
        // BCP-47 tag for the current input mode — without this the
        // system recognizer falls back to the device locale (e.g.
        // recognises English on an English-locale phone even when the
        // user is in Korean mode).
        val languageTag = when (inputLanguage) {
            InputLanguage.KOREAN -> "ko-KR"
            InputLanguage.JAPANESE -> "ja-JP"
            InputLanguage.ENGLISH -> "en-US"
        }
        val intent = Intent(this, VoiceInputActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(VoiceInputActivity.EXTRA_LANGUAGE_TAG, languageTag)
        }
        startActivity(intent)
    }

    /**
     * Open Android's system "Languages & input" page so users can switch
     * IMEs / pick a different default keyboard without leaving our IME.
     * Reachable via the ⋯ menu in [TopChrome].
     */
    private fun openSystemImeSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Voice input bridge: when [VoiceInputActivity] finishes it stashes
        // the transcript in SharedPreferences.  The IME re-attaches here
        // once the user is back at the editor — read + clear + commit.
        val pending = VoiceInputActivity.consumePending(applicationContext)
        if (pending != null) {
            val ic = currentInputConnection
            if (ic != null) {
                finalizeForCursor(ic)
                batched(ic) { ic.commitText(pending, 1) }
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
        conversionBoundary = -1
        currentWordPrefix = ""
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
        // Nothing to track — no preedit, no JP-mode kana run, no
        // KOR/ENG autocomplete prefix — so any selection change is just
        // plain editing the user is doing on existing text.
        if (composer.empty() && currentKanaRun.isEmpty() && currentWordPrefix.isEmpty()) return

        // KOR / ENG-only state: there's a word-prefix in flight but no
        // composing region (the prefix lives in committed text, which IS
        // surrounding text from Android's POV).  Selection-change events
        // here are usually our OWN commitText callbacks moving the caret
        // forward; resetting on every cs<0 here would clear the prefix
        // 0.1 s after typing each letter (the bug the user hit — strip
        // showed suggestions then immediately wiped them).  Trust the
        // typing path (updateWordPrefix on space / punct / mode switch)
        // to reset the prefix instead.
        if (composer.empty() && currentKanaRun.isEmpty()) return

        // Case 1: composing region is gone entirely (external clear).
        // KakaoTalk clears the EditText after its own send button fires,
        // the user tapped a different EditText, the app called setText(""),
        // etc.  Drop our state so the next jamo / kana doesn't hand off its
        // leftover into a fresh context (the bug that produced
        // "요감사합니다" pre-fix; without this branch a stale kana run
        // would also leak into the next field).
        if (candidatesStart < 0) {
            resetAllState()
            return
        }

        // Case 2: composing region exists but the cursor moved OUTSIDE it.
        // The user tapped elsewhere mid-conversion (e.g. dragging the caret
        // to insert text earlier in the sentence).  Without this branch,
        // the next keystroke would call setComposingText against the OLD
        // region, replacing the underlined preedit instead of inserting at
        // the new cursor position.
        //
        // Fix: finalize the existing composing region IN PLACE (commits
        // the underlined preedit + accumulated kana as committed text
        // where they sit), then drop our state so the next keystroke
        // starts a fresh region at the new cursor position.
        val cursorOutsideRegion =
            newSelStart < candidatesStart || newSelStart > candidatesEnd ||
            newSelEnd < candidatesStart || newSelEnd > candidatesEnd
        if (cursorOutsideRegion) {
            currentInputConnection?.finishComposingText()
            resetAllState()
        }
    }

    override fun onDestroy() {
        (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.removePrimaryClipChangedListener(clipChangedListener)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    /**
     * Commit the picked clipboard text at the current cursor.  Behaves
     * like a paste — bypasses the composer entirely (any in-progress
     * preedit / kana run is finalised first via [finalizeForCursor]).
     * Reachable from the ⋯ menu's 클립보드 panel.
     */
    fun handleClipboardPick(text: String) {
        val ic = currentInputConnection ?: return
        finalizeForCursor(ic)
        batched(ic) { ic.commitText(text, 1) }
    }

    /**
     * Commit the picked emoji at the current cursor and push it to the
     * recents history.  Doesn't dismiss the panel — the user often wants
     * to chain multiple emojis (e.g. 🎉🎂🥳); the panel's ✕ button is
     * how they leave.
     */
    fun handleEmojiPick(emoji: String) {
        val ic = currentInputConnection ?: return
        finalizeForCursor(ic)
        batched(ic) { ic.commitText(emoji, 1) }
        emojiHistory.add(emoji)
        _emojiRecents.value = emojiHistory.items
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
        // Hanja conversion priming is per-tap: any action other than tapping
        // Hanja itself (or picking a candidate, which clears the priming
        // inline) invalidates the syllable selection.  Otherwise a stale
        // priming would hijack later candidate picks in JP mode.
        if (action != KeyAction.Hanja) {
            hanjaConversionSyllable = null
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
            KeyAction.CursorLeft -> handleCursorOrShrink(ic)
            KeyAction.CursorRight -> handleCursorOrExtend(ic)
            KeyAction.Hanja -> handleHanja(ic)
            KeyAction.Reconvert -> handleReconvert(ic)
            KeyAction.Shift -> Unit       // BeolsikLayout owns the Shift state; service receives the action only for haptic
            KeyAction.Symbols -> Unit     // page state lives in the layout composables
        }
    }

    /**
     * Korean 한자 conversion entry point — peeks the most recent Hangul
     * syllable in the editor, looks up Hanja candidates, and surfaces
     * them in the candidate strip.  The user picks one with a tap and
     * [handleCandidatePick] (Hanja branch) replaces the syllable in the
     * editor.
     *
     * Only meaningful in Korean mode (where the user is committing
     * Hangul); in Japanese / English mode the syllable peek will return
     * non-Hangul, the lookup returns empty, and the candidate strip
     * stays as-is.
     */
    private fun handleHanja(ic: InputConnection) {
        // Flush any in-progress preedit first so the syllable we peek is
        // committed text, not the underlined region.
        if (!composer.empty()) {
            batched(ic) { flushComposerInner(ic) }
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isEmpty()) return
        val syllable = before[0]
        val entries = hanjaDictionary.lookup(syllable)
        if (entries.isEmpty()) return
        hanjaConversionSyllable = syllable
        // Surface candidates as "<meaning> <hanja>" (e.g. "백성 民") so the
        // user can disambiguate visually.  The strip's pick handler
        // (handleCandidatePick, Hanja branch) commits just the trailing
        // Hanja char, not the gloss prefix.
        _candidates.value = entries.map { formatHanjaCandidate(it) }
    }

    /**
     * Render a Hanja entry for the candidate strip.  libhangul glosses are
     * "<meaning> <reading>" and may carry multiple comma-separated meaning
     * variants for Hanja with several readings (e.g. "감동할 가, 찌를 감").
     * We:
     *   - take the FIRST meaning segment (before any comma);
     *   - drop the trailing reading syllable from that segment (it's just
     *     the Hangul we already typed; redundant alongside the Hanja);
     *   - append the Hanja, e.g. "백성 民".
     * Glosses without a reading suffix or empty glosses fall back to the
     * raw Hanja character.
     */
    private fun formatHanjaCandidate(entry: HanjaDictionary.Entry): String {
        val firstMeaning = entry.gloss.substringBefore(',').trim()
        val withoutReading = firstMeaning.substringBeforeLast(' ', missingDelimiterValue = "")
        return if (withoutReading.isEmpty()) entry.hanja.toString()
        else "$withoutReading ${entry.hanja}"
    }

    /**
     * Space behaviour, two-tap convention (matches standard Japanese IMEs +
     * the user's "안 + space space → 안 [space]" expectation):
     *
     *  - Tap 1 with non-empty state: break any open Cheonjiin consonant
     *    cycle AND flush the composer's pending syllable in one go.  No
     *    literal space yet — same convention as Japanese IMEs where space
     *    finalises the in-progress reading first.
     *  - Tap 2 (now empty): literal space inserted.
     *
     *  `안녕` still types as one word: the tap-1 cycle break + flush commits
     *  안 with no space; the next ㄴ starts a fresh syllable for 녕.
     *
     *  Earlier this routine kept cycle-break and composer-flush as separate
     *  branches, requiring three space taps (cycle break → flush → space)
     *  after a 종성-bearing syllable.  Merging them gives the user the
     *  expected two-tap behaviour.
     */
    private fun handleSpace(ic: InputConnection) {
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
                            emit(ic, convertForOutput(finalized, composer.currentChoJamo()))
                        }
                    }
                }
            }
            // Composing region = accumulated kana run + in-progress Hangul
            // preedit (same shape as handleCommit's jamo branch).
            ic.setComposingText(composingSpannable(), 1)
            refreshCandidates()
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
        if (composer.empty() && currentKanaRun.isEmpty()) return
        // The composing region currently holds [accumulated kana run] +
        // [in-progress Hangul preedit]; getTextBeforeCursor includes the
        // composing region's contents, so the comparison is end-to-end.
        val expected = currentKanaRun + composer.preedit()
        if (expected.isEmpty()) return
        val actual = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return
        if (actual != expected) {
            // Finalize the stale composing region (the underlined preedit /
            // kana run still pinned to its old position) BEFORE resetting
            // composer state.  Without this, the subsequent setComposingText
            // call from the next keystroke would overwrite that region's
            // content in place — surfacing as "underlined 한 disappears and
            // new input appears WHERE 한 was, not where the cursor moved
            // to" in Korean mode (and the same bug in JP mode whenever a
            // mid-conversion field tap doesn't fire onUpdateSelection in
            // time).
            ic.finishComposingText()
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
                    emit(ic, convertForOutput(finalized, composer.currentChoJamo()))
                }
                // Composing region = accumulated kana run (set by emit
                // above on JP-mode hiragana emission, empty otherwise) +
                // the in-progress Hangul preedit.  Both stay underlined so
                // the user can see "what's been emitted as kana so far"
                // alongside "the syllable I'm building right now".
                ic.setComposingText(composingSpannable(), 1)
                // Refresh candidates even when the syllable didn't migrate
                // — KOR-mode autocomplete picks up the in-progress preedit
                // for prefix matching, so the strip needs to update on
                // every jamo, not just on close-syllable boundaries.
                refreshCandidates()
            } else {
                // Punctuation, digits, kana, anything non-jamo — flush
                // in-progress syllable first so it commits BEFORE the new text.
                flushComposerInner(ic)
                emit(ic, digitsToFullWidth(text))
            }
        }
    }

    /**
     * Convert a finalized Hangul string to the per-mode editor output:
     *  - JAPANESE: full kana conversion via [convertHangulToKana].
     *  - KOREAN / ENGLISH: pass the Hangul through unchanged so 두벌식 +
     *    KOREAN mode types regular Hangul (the project's secondary use case).
     *
     * ENGLISH mode shouldn't reach this path in practice — the QWERTY
     * letters page commits ASCII via the non-jamo branch — but the
     * fall-through is harmless.
     */
    private fun convertForOutput(finalized: String, fallbackNext: Char): String =
        if (inputLanguage == InputLanguage.JAPANESE) {
            convertHangulToKana(finalized, fallbackNext)
        } else {
            finalized
        }

    /**
     * Convert ASCII digits to full-width Japanese counterparts (０..９,
     * U+FF10..U+FF19) **only** when the current [inputLanguage] is JAPANESE.
     * KOREAN / ENGLISH modes pass digits through as ASCII.  Non-digit chars
     * pass through unchanged in all modes.
     */
    private fun digitsToFullWidth(text: String): String {
        if (text.isEmpty()) return text
        if (inputLanguage != InputLanguage.JAPANESE) return text
        if (!text.any { it in '0'..'9' }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(if (c in '0'..'9') (c.code - '0'.code + 0xFF10).toChar() else c)
        }
        return sb.toString()
    }

    /**
     * Move the editor caret one step in the requested direction.  Uses the
     * standard DPAD key event which every editor honours; setSelection would
     * also work but requires querying current selection first.
     */
    private fun sendCursor(ic: InputConnection, keyCode: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /**
     * ◀ key.  In Japanese conversion mode (kana run live) shrinks the
     * active conversion window by one char so the strip surfaces
     * candidates for a shorter prefix.  Otherwise, falls through to
     * plain cursor-left.  Same dual-mode shape as [handleCursorOrExtend].
     */
    private fun handleCursorOrShrink(ic: InputConnection) {
        if (inputLanguage == InputLanguage.JAPANESE) {
            // Tapping ◀ implies the user is done with whatever syllable
            // they were composing — flush any pending Hangul preedit so
            // its kana joins currentKanaRun BEFORE we shrink the boundary.
            // Without this the last-typed syllable (still sitting in
            // composer state, not yet emitted) wouldn't be part of the
            // conversion window the user is now adjusting.
            if (!composer.empty()) batched(ic) { flushComposerInner(ic) }
            if (currentKanaRun.isNotEmpty()) {
                val current = if (conversionBoundary < 0) currentKanaRun.length else conversionBoundary
                val next = (current - 1).coerceAtLeast(1)
                conversionBoundary = if (next == currentKanaRun.length) -1 else next
                // Re-render the composing region with the new highlight range.
                ic.setComposingText(composingSpannable(), 1)
                refreshCandidates()
                return
            }
        }
        finalizeForCursor(ic)
        sendCursor(ic, KeyEvent.KEYCODE_DPAD_LEFT)
    }

    /**
     * ▶ key.  In Japanese conversion mode (kana run live) extends the
     * active conversion window by one char (up to the full run length —
     * extending past the end resets to "full run" / `-1`).  Otherwise,
     * plain cursor-right.
     */
    private fun handleCursorOrExtend(ic: InputConnection) {
        if (inputLanguage == InputLanguage.JAPANESE) {
            // Mirror handleCursorOrShrink: ▶ also implies the syllable
            // is done, so flush the pending preedit first.
            if (!composer.empty()) batched(ic) { flushComposerInner(ic) }
            if (currentKanaRun.isNotEmpty()) {
                val current = if (conversionBoundary < 0) currentKanaRun.length else conversionBoundary
                val next = (current + 1).coerceAtMost(currentKanaRun.length)
                conversionBoundary = if (next == currentKanaRun.length) -1 else next
                ic.setComposingText(composingSpannable(), 1)
                refreshCandidates()
                return
            }
        }
        finalizeForCursor(ic)
        sendCursor(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    /**
     * Commit any in-progress preedit + kana run as committed text before a
     * cursor-move op.  Without this the user moves the caret while the
     * composing region is still anchored to the old position; the next
     * keystroke would then overwrite the underlined preedit / kana instead
     * of inserting at the new cursor (same root cause as the
     * onUpdateSelection cursor-outside-region path, just hit pre-emptively).
     */
    private fun finalizeForCursor(ic: InputConnection) {
        batched(ic) {
            flushComposerInner(ic)
            if (currentKanaRun.isNotEmpty()) ic.finishComposingText()
        }
        currentKanaRun = ""
        currentWordPrefix = ""
        refreshCandidates()
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
            when {
                !composer.empty() -> {
                    composer.undoLastJamo()
                    // Composing region = accumulated kana + new (possibly
                    // empty) preedit.  Empty composing region clears the
                    // underline; non-empty replaces it.
                    ic.setComposingText(composingSpannable(), 1)
                    refreshCandidates()
                }
                currentKanaRun.isNotEmpty() -> {
                    // No jamo in flight; pull the last char off the kana
                    // run (the composing region) instead of deleting from
                    // committed text.  Keeps backspace into the conversion
                    // target visible — finishComposingText drops the empty
                    // region when the run is fully consumed.
                    currentKanaRun = currentKanaRun.dropLast(1)
                    // Boundary follows the run — if backspace shrunk the
                    // run past the chosen window, reset to "full run".
                    if (conversionBoundary > currentKanaRun.length) conversionBoundary = -1
                    if (currentKanaRun.isEmpty()) ic.finishComposingText()
                    else ic.setComposingText(composingSpannable(), 1)
                    refreshCandidates()
                }
                else -> {
                    ic.deleteSurroundingText(1, 0)
                    // KOR / ENG autocomplete: shrink the prefix in lockstep
                    // with the editor so suggestions update on backspace.
                    if (currentWordPrefix.isNotEmpty()) {
                        currentWordPrefix = currentWordPrefix.dropLast(1)
                        refreshCandidates()
                    }
                }
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
            emit(ic, convertForOutput(flushed, ' '))
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
        /**
         * Viterbi top-K depth — small because near-duplicates of the top
         * pick provide diminishing return in the candidate strip, and
         * each extra K linearly grows the per-position lattice memory.
         * Up to [Viterbi.MAX_TOP_K] (10) internally; 5 fits the strip
         * without crowding out the simple-dict + katakana fallbacks.
         */
        private const val VITERBI_TOP_K = 5

        /**
         * Soft-gold background tint for the active 文節 conversion window
         * (the kana prefix the strip / next pick currently target).  35%
         * alpha keeps it readable against both light and dark themes.
         */
        private const val BUNSETSU_HIGHLIGHT_COLOR = 0x55FFD700
    }
}
