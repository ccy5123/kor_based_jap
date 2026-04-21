#include "KeyHandler.h"
#include "KorJpnIme.h"
#include "Composition.h"
#include "BatchimLookup.h"  // includes mapping_table.h + batchim_rules.h
#include "DebugLog.h"

// ============================================================================
// Katakana conversion helpers
// ============================================================================

// Convert a single hiragana codepoint to full-width katakana.
// Hiragana U+3041..U+3096 → Katakana U+30A1..U+30F6 (offset +0x60).
// Characters outside this block (e.g., ー U+30FC, ん U+3093→ン) are also handled.
static wchar_t toKatakana(wchar_t h) noexcept {
    if (h >= 0x3041 && h <= 0x3096) return static_cast<wchar_t>(h + 0x60);
    if (h == 0x3093) return 0x30F3; // ん → ン
    if (h == 0x3063) return 0x30C3; // っ → ッ
    return h; // already katakana, ー, or other — pass through
}

static std::wstring toKatakanaStr(const std::wstring& s) {
    std::wstring out;
    out.reserve(s.size());
    for (wchar_t c : s) out += toKatakana(c);
    return out;
}

// ============================================================================
// VkToJamo — Korean 2-beolsik layout
// Standard layout: unshifted + shifted jamo
// ============================================================================

wchar_t VkToJamo(UINT vk, bool shifted) {
    // @MX:ANCHOR: VK→jamo mapping is the single authoritative 2-beolsik table
    // @MX:REASON: All key handling passes through here; any edit affects every keystroke
    struct Entry { UINT vk; wchar_t normal; wchar_t shifted; };
    static constexpr Entry kTable[] = {
        // Row 1 (number row) — no Korean on unshifted; shifted give special jamo
        { 'Q', L'ㅂ', L'ㅃ' },
        { 'W', L'ㅈ', L'ㅉ' },
        { 'E', L'ㄷ', L'ㄸ' },
        { 'R', L'ㄱ', L'ㄲ' },
        { 'T', L'ㅅ', L'ㅆ' },
        { 'Y', L'ㅛ', L'ㅛ' },
        { 'U', L'ㅕ', L'ㅕ' },
        { 'I', L'ㅑ', L'ㅑ' },
        { 'O', L'ㅐ', L'ㅒ' },
        { 'P', L'ㅔ', L'ㅖ' },
        { 'A', L'ㅁ', L'ㅁ' },
        { 'S', L'ㄴ', L'ㄴ' },
        { 'D', L'ㅇ', L'ㅇ' },
        { 'F', L'ㄹ', L'ㄹ' },
        { 'G', L'ㅎ', L'ㅎ' },
        { 'H', L'ㅗ', L'ㅗ' },
        { 'J', L'ㅓ', L'ㅓ' },
        { 'K', L'ㅏ', L'ㅏ' },
        { 'L', L'ㅣ', L'ㅣ' },
        { 'Z', L'ㅋ', L'ㅋ' },
        { 'X', L'ㅌ', L'ㅌ' },
        { 'C', L'ㅊ', L'ㅊ' },
        { 'V', L'ㅍ', L'ㅍ' },
        { 'B', L'ㅠ', L'ㅠ' },
        { 'N', L'ㅜ', L'ㅜ' },
        { 'M', L'ㅡ', L'ㅡ' },
    };
    for (const auto& e : kTable) {
        if (e.vk == vk)
            return shifted ? e.shifted : e.normal;
    }
    return 0; // not a Korean key
}

// ============================================================================
// HangulComposer — 3-state syllable state machine
// States: EMPTY → CHO_ONLY → CHO_JUNG → CHO_JUNG_JONG
// Unicode formula: (cho*21 + jung)*28 + jong + 0xAC00
// ============================================================================

// @MX:ANCHOR: These index tables are used by both input() and compose()
// @MX:REASON: Indices must stay in sync with Unicode Hangul syllable block offsets

// 초성 (onset) — 19 entries, offsets 0-18
static constexpr wchar_t kCho[] = {
    L'ㄱ',L'ㄲ',L'ㄴ',L'ㄷ',L'ㄸ',L'ㄹ',L'ㅁ',L'ㅂ',L'ㅃ',
    L'ㅅ',L'ㅆ',L'ㅇ',L'ㅈ',L'ㅉ',L'ㅊ',L'ㅋ',L'ㅌ',L'ㅍ',L'ㅎ'
};

// 중성 (nucleus vowel) — 21 entries
static constexpr wchar_t kJung[] = {
    L'ㅏ',L'ㅐ',L'ㅑ',L'ㅒ',L'ㅓ',L'ㅔ',L'ㅕ',L'ㅖ',L'ㅗ',
    L'ㅘ',L'ㅙ',L'ㅚ',L'ㅛ',L'ㅜ',L'ㅝ',L'ㅞ',L'ㅟ',L'ㅠ',
    L'ㅡ',L'ㅢ',L'ㅣ'
};

// 종성 (coda) — 28 entries (index 0 = no coda)
static constexpr wchar_t kJong[] = {
    0,    // 0 = no coda
    L'ㄱ',L'ㄲ',L'ㄳ',L'ㄴ',L'ㄵ',L'ㄶ',L'ㄷ',L'ㄹ',L'ㄺ',L'ㄻ',
    L'ㄼ',L'ㄽ',L'ㄾ',L'ㄿ',L'ㅀ',L'ㅁ',L'ㅂ',L'ㅄ',L'ㅅ',L'ㅆ',
    L'ㅇ',L'ㅈ',L'ㅊ',L'ㅋ',L'ㅌ',L'ㅍ',L'ㅎ'
};

// Compound jong pairs → jong index (ㄱ+ㅅ=ㄳ, ㄴ+ㅈ=ㄵ, etc.)
struct JongPair { wchar_t j1, j2; int result; };
static constexpr JongPair kJongCompound[] = {
    { L'ㄱ', L'ㅅ',  3 }, // ㄳ
    { L'ㄴ', L'ㅈ',  5 }, // ㄵ
    { L'ㄴ', L'ㅎ',  6 }, // ㄶ
    { L'ㄹ', L'ㄱ',  9 }, // ㄺ
    { L'ㄹ', L'ㅁ', 10 }, // ㄻ
    { L'ㄹ', L'ㅂ', 11 }, // ㄼ
    { L'ㄹ', L'ㅅ', 12 }, // ㄽ
    { L'ㄹ', L'ㅌ', 13 }, // ㄾ
    { L'ㄹ', L'ㅍ', 14 }, // ㄿ
    { L'ㄹ', L'ㅎ', 15 }, // ㅀ
    { L'ㅂ', L'ㅅ', 18 }, // ㅄ
};

// Compound vowel pairs → vowel (ㅗ+ㅏ=ㅘ, ㅗ+ㅐ=ㅙ, etc.)
struct VowelPair { wchar_t v1, v2; wchar_t result; };
static constexpr VowelPair kVowelCompound[] = {
    { L'ㅗ', L'ㅏ', L'ㅘ' },
    { L'ㅗ', L'ㅐ', L'ㅙ' },
    { L'ㅗ', L'ㅣ', L'ㅚ' },
    { L'ㅜ', L'ㅓ', L'ㅝ' },
    { L'ㅜ', L'ㅔ', L'ㅞ' },
    { L'ㅜ', L'ㅣ', L'ㅟ' },
    { L'ㅡ', L'ㅣ', L'ㅢ' },
};

HangulComposer::HangulComposer() = default;

int HangulComposer::choIndex(wchar_t jamo) {
    for (int i = 0; i < CHO_COUNT; ++i)
        if (kCho[i] == jamo) return i;
    return -1;
}

int HangulComposer::jungIndex(wchar_t jamo) {
    for (int i = 0; i < JUNG_COUNT; ++i)
        if (kJung[i] == jamo) return i;
    return -1;
}

int HangulComposer::jongIndex(wchar_t jamo) {
    for (int i = 1; i < JONG_COUNT; ++i) // skip 0 (no coda)
        if (kJong[i] == jamo) return i;
    return -1;
}

// Remap jong→cho when the coda splits off to start a new syllable
// E.g., 닭+아 → 달+가, so jong ㄱ(index 1) → cho ㄱ(index 0)
int HangulComposer::choFromJong(int jongIdx) {
    wchar_t jamo = kJong[jongIdx];
    return choIndex(jamo);
}

wchar_t HangulComposer::compoundVowel(wchar_t v1, wchar_t v2) {
    for (const auto& p : kVowelCompound)
        if (p.v1 == v1 && p.v2 == v2) return p.result;
    return 0;
}

int HangulComposer::compoundJong(wchar_t j1, wchar_t j2) {
    for (const auto& p : kJongCompound)
        if (p.j1 == j1 && p.j2 == j2) return p.result;
    return 0;
}

wchar_t HangulComposer::compose() const {
    if (_cho < 0 || _jung < 0) return 0;
    int jong = (_jong < 0) ? 0 : _jong;
    return static_cast<wchar_t>((_cho * 21 + _jung) * 28 + jong + 0xAC00);
}

// @MX:NOTE: [AUTO] Main state machine — processes one jamo at a time
// Returns a completed syllable wstring when one is finalized; empty if still composing
std::wstring HangulComposer::input(wchar_t jamo) {
    // ----- State: EMPTY --------------------------------------------------
    if (_cho < 0 && _jung < 0) {
        int ci = choIndex(jamo);
        if (ci >= 0) {
            _cho = ci;
            _rawJong = jamo;
            return {};  // CHO_ONLY
        }
        // Lone vowel (no consonant onset — use ㅇ as placeholder cho index 11)
        int vi = jungIndex(jamo);
        if (vi >= 0) {
            _cho  = 11; // ㅇ silent onset
            _jung = vi;
            _rawJung = jamo;
            return {};  // CHO_JUNG (silent onset)
        }
        // Not Korean — pass through as-is
        return std::wstring(1, jamo);
    }

    // ----- State: CHO_ONLY -----------------------------------------------
    if (_cho >= 0 && _jung < 0) {
        int vi = jungIndex(jamo);
        if (vi >= 0) {
            _jung    = vi;
            _rawJung = jamo;
            return {};  // → CHO_JUNG
        }
        // Another consonant → emit current (standalone jamo letter) and restart
        std::wstring out(1, kCho[_cho]);
        reset();
        return out + input(jamo);
    }

    // ----- State: CHO_JUNG -----------------------------------------------
    if (_cho >= 0 && _jung >= 0 && _jong < 0) {
        // Try compound vowel first (ㅗ+ㅏ=ㅘ, etc.)
        wchar_t cv = compoundVowel(_rawJung, jamo);
        if (cv) {
            int vi = jungIndex(cv);
            if (vi >= 0) {
                _jung    = vi;
                _rawJung = cv;
                return {};  // still CHO_JUNG, vowel upgraded
            }
        }

        // Consonant that can be jongseong → make it the jong of current syllable
        int ji = jongIndex(jamo);
        if (ji >= 0) {
            _jong    = ji;
            _rawJong = jamo;
            return {};  // → CHO_JUNG_JONG
        }

        // Anything else (vowel-not-compound, or fortis consonant ㄸ/ㅃ/ㅉ that
        // cannot be a jongseong) → emit current syllable and recurse so the new
        // input is processed against an EMPTY state (will become a new cho or
        // a stand-alone vowel correctly).
        // Bug fixed: previously this branch hard-coded `_cho = 11` (ㅇ) and then
        // tried to use `jamo` as a vowel, which produced "たㅇ" when the second
        // ㄸ in 따따 arrived.
        wchar_t syllable = compose();
        std::wstring out(1, syllable);
        reset();
        return out + input(jamo);
    }

    // ----- State: CHO_JUNG_JONG ------------------------------------------
    if (_cho >= 0 && _jung >= 0 && _jong >= 0) {
        int vi = jungIndex(jamo);

        if (vi >= 0) {
            // Vowel: the current jong splits off to become the cho of the next syllable.
            // Try compound vowel with nothing first (no prev vowel before jong).
            // Emit syllable WITHOUT jong, jong becomes new syllable's cho.
            int newCho = choFromJong(_jong);
            if (newCho < 0) {
                // Compound jong like ㄳ — split: keep first part as jong, second as new cho
                // For simplicity in this skeleton, emit whole syllable and restart
                wchar_t syllable = compose();
                std::wstring out(1, syllable);
                reset();
                _cho     = 11;
                _jung    = vi;
                _rawJung = jamo;
                return out;
            }

            // Emit syllable without jong
            int savedJong = _jong;
            wchar_t savedRawJong = _rawJong;
            _jong    = -1;
            _rawJong = 0;
            wchar_t syllable = compose();
            std::wstring out(1, syllable);

            // New syllable: newCho + incoming vowel
            reset();
            _cho     = newCho;
            _jung    = vi;
            _rawJung = jamo;
            (void)savedJong; (void)savedRawJong;
            return out;
        }

        // Consonant: try to form compound jong (ㄱ+ㅅ=ㄳ)
        wchar_t cj = compoundJong(_rawJong, jamo);
        if (cj) {
            // compoundJong returns the jong INDEX encoded as wchar_t
            _jong    = static_cast<int>(cj);
            _rawJong = kJong[static_cast<int>(cj)];
            return {};  // compound jong formed
        }

        // Another consonant → emit current syllable, restart with new consonant
        wchar_t syllable = compose();
        std::wstring out(1, syllable);
        reset();
        return out + input(jamo);
    }

    return {};
}

std::wstring HangulComposer::flush() {
    if (_cho < 0) return {};
    std::wstring out;
    if (_jung >= 0) {
        out = std::wstring(1, compose());
    } else {
        // CHO_ONLY — emit the consonant as standalone jamo letter
        out = std::wstring(1, kCho[_cho]);
    }
    reset();
    return out;
}

std::wstring HangulComposer::preedit() {
    if (_cho < 0) return {};
    if (_jung < 0) return std::wstring(1, kCho[_cho]);
    return std::wstring(1, compose());
}

bool HangulComposer::empty() const {
    return _cho < 0;
}

void HangulComposer::reset() {
    _cho     = -1;
    _jung    = -1;
    _jong    = -1;
    _rawJung = 0;
    _rawJong = 0;
}

// ============================================================================
// KeyHandler — ITfKeyEventSink
// ============================================================================

KeyHandler::KeyHandler(KorJpnIme *pIme)
    : _cRef(1), _pIme(pIme) {
    DllAddRef();
}

KeyHandler::~KeyHandler() {
    DllRelease();
}

// IUnknown
STDMETHODIMP KeyHandler::QueryInterface(REFIID riid, void **ppv) {
    DBG_GUID("KeyHandler::QI IID=", riid);
    if (riid == IID_IUnknown || riid == IID_ITfKeyEventSink) {
        *ppv = static_cast<ITfKeyEventSink*>(this);
        AddRef();
        DBG("  -> S_OK");
        return S_OK;
    }
    *ppv = nullptr;
    DBG("  -> E_NOINTERFACE");
    return E_NOINTERFACE;
}
STDMETHODIMP_(ULONG) KeyHandler::AddRef()  { return InterlockedIncrement(&_cRef); }
STDMETHODIMP_(ULONG) KeyHandler::Release() {
    LONG c = InterlockedDecrement(&_cRef);
    if (!c) delete this;
    return c;
}

// Advise/Unadvise
//
// fForeground=TRUE: we register as the foreground key sink so ctfmon routes
// keystrokes to us when our IME is the active one.  Earlier this caused us to
// hijack Korean input system-wide because ctfmon was calling Activate() in
// every process even when the user hadn't selected us — but with the new
// install_tip.reg (proper Category metadata + Enable=0) ctfmon now activates
// us only in processes where the user actually switches to us via Win+Space.
HRESULT KeyHandler::Advise(ITfThreadMgr *pThreadMgr, TfClientId tid) {
    DBGF("KeyHandler::Advise START  pTM=%p tid=%lu", (void*)pThreadMgr, (unsigned long)tid);
    ITfKeystrokeMgr *pKsMgr = nullptr;
    HRESULT hr = pThreadMgr->QueryInterface(IID_ITfKeystrokeMgr, (void**)&pKsMgr);
    DBGF("  QI ITfKeystrokeMgr hr=0x%08lX  pKsMgr=%p", (long)hr, (void*)pKsMgr);
    if (FAILED(hr)) return hr;
    hr = pKsMgr->AdviseKeyEventSink(tid, static_cast<ITfKeyEventSink*>(this), TRUE);
    DBGF("  AdviseKeyEventSink (FG) hr=0x%08lX", (long)hr);
    pKsMgr->Release();
    return hr;
}

HRESULT KeyHandler::Unadvise(ITfThreadMgr *pThreadMgr) {
    ITfKeystrokeMgr *pKsMgr = nullptr;
    HRESULT hr = pThreadMgr->QueryInterface(IID_ITfKeystrokeMgr, (void**)&pKsMgr);
    if (FAILED(hr)) return hr;
    // TfClientId is stored in KorJpnIme; retrieve via back-pointer
    hr = pKsMgr->UnadviseKeyEventSink(_pIme->GetClientId());
    pKsMgr->Release();
    return hr;
}

// ITfKeyEventSink
STDMETHODIMP KeyHandler::OnSetFocus(BOOL fForeground) { DBGF("OnSetFocus fg=%d", (int)fForeground); return S_OK; }

STDMETHODIMP KeyHandler::OnTestKeyDown(ITfContext *pCtx, WPARAM wParam,
                                        LPARAM lParam, BOOL *pfEaten) {
    DBGF("OnTestKeyDown vk=%lu", (unsigned long)wParam);
    UINT vk = static_cast<UINT>(wParam);

    // Pure modifiers — never eat (see OnKeyDown for rationale).
    if (vk == VK_SHIFT   || vk == VK_LSHIFT   || vk == VK_RSHIFT
     || vk == VK_CONTROL || vk == VK_LCONTROL || vk == VK_RCONTROL
     || vk == VK_MENU    || vk == VK_LMENU    || vk == VK_RMENU
     || vk == VK_LWIN    || vk == VK_RWIN     || vk == VK_CAPITAL) {
        *pfEaten = FALSE;
        return S_OK;
    }

    // VK_CONVERT / VK_NONCONVERT are always eaten (IME On/Off control)
    if (vk == VK_CONVERT || vk == VK_NONCONVERT) {
        *pfEaten = TRUE;
        return S_OK;
    }

    // When IME is inactive: pass everything through
    if (!_pIme->IsActive()) {
        *pfEaten = FALSE;
        return S_OK;
    }

    // Modifier-key combinations (Ctrl+X, Alt+X, Win+X) belong to the host
    // application as shortcuts — never eat them.  Plain Shift is allowed
    // because it's part of normal Korean jamo input (e.g. Shift+Q → ㅃ).
    bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    bool alt  = (GetKeyState(VK_MENU)    & 0x8000) != 0;
    bool win  = (GetKeyState(VK_LWIN)    & 0x8000) != 0
             || (GetKeyState(VK_RWIN)    & 0x8000) != 0;
    if (ctrl || alt || win) { *pfEaten = FALSE; return S_OK; }

    bool shifted = (GetKeyState(VK_SHIFT) & 0x8000) != 0;

    // Korean jamo keys
    if (VkToJamo(vk, shifted) != 0) { *pfEaten = TRUE; return S_OK; }

    // Backspace / Esc when something is composing or pending
    bool composing = !_composer.empty() || !_pIme->PendingKana().empty();
    if (vk == VK_BACK   && composing) { *pfEaten = TRUE; return S_OK; }
    if (vk == VK_ESCAPE && composing) { *pfEaten = TRUE; return S_OK; }

    // Space commits accumulated preedit (eats the space)
    if (vk == VK_SPACE  && composing) { *pfEaten = TRUE; return S_OK; }

    // Hyphen → 장음 ー, comma → 、, period → 。
    if (vk == VK_OEM_MINUS  || vk == VK_OEM_COMMA || vk == VK_OEM_PERIOD) {
        *pfEaten = TRUE; return S_OK;
    }

    // F6 (→ hiragana) / F7 (→ katakana): eat only when composing
    if ((vk == VK_F6 || vk == VK_F7) && composing) {
        *pfEaten = TRUE;
        return S_OK;
    }

    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP KeyHandler::OnTestKeyUp(ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

// ----------------------------------------------------------------------------
// Helpers for the new accumulation-style preedit
// ----------------------------------------------------------------------------

// Append the kana form of a fully-composed Korean syllable (ch) to the IME's
// pending kana buffer.  nextJamo is the lookahead consonant for batchim rules
// (sokuon_strict / sokuon_universal); pass 0 for terminal flush.
static void AppendKanaFor(KorJpnIme *pIme, wchar_t ch, wchar_t nextJamo) {
    std::wstring kana = batchim::lookup(ch, nextJamo);
    if (!kana.empty()) pIme->AppendKana(kana);
}

// Build the visible preedit string: pending_kana + (current_in-progress syllable
// rendered as kana when possible, otherwise raw Korean jamo).
static std::wstring BuildPreedit(KorJpnIme *pIme, HangulComposer& composer) {
    std::wstring pre = pIme->PendingKana();
    std::wstring cur = composer.preedit();
    if (!cur.empty()) {
        if (batchim::isHangul(cur[0])) {
            std::wstring kana = batchim::lookup(cur[0], 0);
            pre += kana.empty() ? cur : kana;
        } else {
            pre += cur;     // bare consonant or vowel jamo
        }
    }
    return pre;
}

// Flush in-progress syllable into pending, then commit pending to the document.
// Returns true if anything was committed.
static bool FlushAndCommit(KorJpnIme *pIme, HangulComposer& composer, ITfContext *pCtx) {
    std::wstring tail = composer.flush();
    if (!tail.empty()) AppendKanaFor(pIme, tail[0], 0);

    const std::wstring& pending = pIme->PendingKana();
    if (pending.empty()) return false;

    pIme->CommitText(pCtx, pending);
    pIme->ClearPending();
    pIme->UpdatePreedit(pCtx, L"");
    return true;
}

// @MX:ANCHOR: OnKeyDown is the main dispatch for all Korean key processing
// @MX:REASON: Everything — composition, preedit update, kana lookup — flows through here
STDMETHODIMP KeyHandler::OnKeyDown(ITfContext *pCtx, WPARAM wParam,
                                    LPARAM lParam, BOOL *pfEaten) {
    DBGF("OnKeyDown vk=%lu", (unsigned long)wParam);
    UINT vk = static_cast<UINT>(wParam);

    // Pure modifier-key events (Shift, Ctrl, Alt, Win, CapsLock) are sent on
    // their own when the user presses/releases the modifier.  We must NOT
    // treat them as "non-Korean keys" and flush — that would commit the
    // pending preedit the moment the user presses Shift to type a shifted
    // jamo (e.g. Shift+W = ㅉ for 찌).  Just ignore and pass through.
    if (vk == VK_SHIFT   || vk == VK_LSHIFT   || vk == VK_RSHIFT
     || vk == VK_CONTROL || vk == VK_LCONTROL || vk == VK_RCONTROL
     || vk == VK_MENU    || vk == VK_LMENU    || vk == VK_RMENU
     || vk == VK_LWIN    || vk == VK_RWIN     || vk == VK_CAPITAL) {
        *pfEaten = FALSE;
        return S_OK;
    }

    bool shifted = (GetKeyState(VK_SHIFT) & 0x8000) != 0;

    // ---- IME On/Off --------------------------------------------------------
    if (vk == VK_NONCONVERT) {
        FlushAndCommit(_pIme, _composer, pCtx);
        _pIme->SetActive(false);
        *pfEaten = TRUE;
        return S_OK;
    }
    if (vk == VK_CONVERT) {
        _pIme->SetActive(true);
        *pfEaten = TRUE;
        return S_OK;
    }

    // When IME is inactive, pass everything through untouched
    if (!_pIme->IsActive()) {
        *pfEaten = FALSE;
        return S_OK;
    }

    // Modifier-key combinations are app shortcuts — pass them through.
    // (Plain Shift is fine; it's used for shifted Korean jamo like ㅃ.)
    bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    bool alt  = (GetKeyState(VK_MENU)    & 0x8000) != 0;
    bool win  = (GetKeyState(VK_LWIN)    & 0x8000) != 0
             || (GetKeyState(VK_RWIN)    & 0x8000) != 0;
    if (ctrl || alt || win) {
        // Flush so any in-progress preedit is committed before the shortcut runs.
        FlushAndCommit(_pIme, _composer, pCtx);
        *pfEaten = FALSE;
        return S_OK;
    }

    // ---- F6 / F7: character-type conversion --------------------------------
    // F6 → commit current preedit as hiragana
    if (vk == VK_F6) {
        bool committed = FlushAndCommit(_pIme, _composer, pCtx);
        *pfEaten = committed;
        return S_OK;
    }
    // F7 → commit as full-width katakana
    if (vk == VK_F7) {
        std::wstring tail = _composer.flush();
        if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);
        const std::wstring& pending = _pIme->PendingKana();
        if (!pending.empty()) {
            _pIme->CommitText(pCtx, toKatakanaStr(pending));
            _pIme->ClearPending();
            _pIme->UpdatePreedit(pCtx, L"");
            *pfEaten = TRUE;
        } else {
            *pfEaten = FALSE;
        }
        return S_OK;
    }

    // ---- Space / Enter / Tab: commit accumulated preedit ------------------
    // Standard JP-IME behaviour: when there's nothing to commit, pass the key
    // through (regular space, newline, tab); when there IS preedit, commit it
    // and EAT the space (Space is a commit signal, not whitespace), but pass
    // Enter/Tab through after committing (typical JP-IME convention).
    if (vk == VK_SPACE || vk == VK_RETURN || vk == VK_TAB) {
        bool hadComposing =
            !_composer.empty() || !_pIme->PendingKana().empty();
        FlushAndCommit(_pIme, _composer, pCtx);
        if (vk == VK_SPACE && hadComposing) {
            *pfEaten = TRUE;        // Space ate as commit trigger
        } else {
            *pfEaten = FALSE;       // Enter/Tab/Space-no-preedit pass through
        }
        return S_OK;
    }

    // Escape — discard preedit (no commit)
    if (vk == VK_ESCAPE) {
        if (!_composer.empty() || !_pIme->PendingKana().empty()) {
            _composer.reset();
            _pIme->ClearPending();
            _pIme->UpdatePreedit(pCtx, L"");
            *pfEaten = TRUE;
        } else {
            *pfEaten = FALSE;
        }
        return S_OK;
    }

    // Backspace — first try to peel a jamo off the in-progress syllable; if
    // the composer is already empty, peel the last codepoint off pending kana.
    if (vk == VK_BACK) {
        if (!_composer.empty()) {
            _composer.reset();          // (TODO: per-jamo undo; reset clears whole syllable)
            _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
            *pfEaten = TRUE;
            return S_OK;
        }
        const std::wstring& pending = _pIme->PendingKana();
        if (!pending.empty()) {
            std::wstring trimmed = pending.substr(0, pending.size() - 1);
            _pIme->ClearPending();
            _pIme->AppendKana(trimmed);
            _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
            *pfEaten = TRUE;
            return S_OK;
        }
        *pfEaten = FALSE;
        return S_OK;
    }

    // Hyphen → long-vowel mark ー (appended to pending)
    if (vk == VK_OEM_MINUS) {
        std::wstring tail = _composer.flush();
        if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);
        _pIme->AppendKana(L"\u30FC");
        _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
        *pfEaten = TRUE;
        return S_OK;
    }

    // Japanese punctuation — , → 、  and  . → 。
    // Behave like a normal jamo input: flush in-progress syllable, append the
    // punctuation to the pending kana so the user can keep building a phrase.
    if (vk == VK_OEM_COMMA || vk == VK_OEM_PERIOD) {
        std::wstring tail = _composer.flush();
        if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);
        _pIme->AppendKana(vk == VK_OEM_COMMA ? L"\u3001" : L"\u3002");
        _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
        *pfEaten = TRUE;
        return S_OK;
    }

    // ---- Korean jamo input ------------------------------------------------
    wchar_t jamo = VkToJamo(vk, shifted);
    if (jamo == 0) {
        // Non-Korean key (number, punctuation, etc.) — flush and let app handle key
        FlushAndCommit(_pIme, _composer, pCtx);
        *pfEaten = FALSE;
        return S_OK;
    }

    *pfEaten = TRUE;
    std::wstring completed = _composer.input(jamo);
    if (!completed.empty()) {
        // At most one syllable is completed per keystroke (see HangulComposer invariant).
        // The triggering jamo is the next initial consonant — pass as context for
        // sokuon_strict / sokuon_universal decisions.
        AppendKanaFor(_pIme, completed[0], jamo);
    }

    // Update preedit = pending + current in-progress syllable
    _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
    return S_OK;
}

STDMETHODIMP KeyHandler::OnKeyUp(ITfContext*, WPARAM, LPARAM, BOOL *pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}

STDMETHODIMP KeyHandler::OnPreservedKey(ITfContext*, REFGUID, BOOL *pfEaten) {
    *pfEaten = FALSE;
    return S_OK;
}
