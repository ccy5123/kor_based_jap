#include "KeyHandler.h"
#include "KorJpnIme.h"
#include "Composition.h"
#include "BatchimLookup.h"  // includes mapping_table.h + batchim_rules.h
#include "Dictionary.h"
#include "CandidateWindow.h"
#include "KanaConv.h"
#include "DebugLog.h"
#include <algorithm>

// (Hiragana <-> katakana helpers live in KanaConv.h, shared with KorJpnIme.)
using kana::toKatakanaStr;

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

        // Special patterns: doubled final-vowel jamo after a silent-ㅇ
        // onset have no native Korean meaning (the second vowel would
        // normally split into a new syllable), so the keystroke pattern
        // is repurposed for Japanese particles whose written form differs
        // from pronunciation.
        //
        //   ㅇ-ㅗ-ㅗ -> を   (object particle, normally read as "o")
        //   ㅇ-ㅘ-ㅏ -> は   (topic particle,  normally read as "wa";
        //                     ㅘ comes from the ㅗ+ㅏ compound vowel)
        //   ㅇ-ㅔ-ㅔ -> へ   (direction particle, normally read as "e")
        //
        // Real long-vowel forms (おお, ええ, etc.) require the explicit
        // ㅇ between vowels (ㅇ-ㅗ-ㅇ-ㅗ, ㅇ-ㅔ-ㅇ-ㅔ) and are unaffected.
        if (_cho == 11) {
            if (_rawJung == L'ㅗ' && jamo == L'ㅗ') {
                reset(); return std::wstring(1, kWoMarker);
            }
            if (_rawJung == L'ㅘ' && jamo == L'ㅏ') {
                reset(); return std::wstring(1, kWaMarker);
            }
            if (_rawJung == L'ㅔ' && jamo == L'ㅔ') {
                reset(); return std::wstring(1, kEMarker);
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
            // Vowel arriving with a jong present — the jong "migrates" to be
            // the cho of the next syllable.

            // Compound jong (ㄳ ㄵ ㄶ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅄ): keep the FIRST
            // part as the jong of the current syllable, and use the SECOND
            // part as the cho of the new syllable.  Bug fixed: previously this
            // case fell through to silent ㅇ, turning 칹+ㅣ into かい instead
            // of かんじ.
            batchim::SplitJong sj = batchim::splitCompoundJong(_jong);
            if (sj.firstJong >= 0 && sj.secondCho >= 0) {
                _jong    = sj.firstJong;
                _rawJong = kJong[sj.firstJong];
                wchar_t syllable = compose();    // 칸 (with reduced jong)
                std::wstring out(1, syllable);
                reset();
                _cho     = sj.secondCho;
                _jung    = vi;
                _rawJung = jamo;
                return out;
            }

            // Single jong: choFromJong gives the migrating cho, current
            // syllable emits without jong (e.g. 칸 → 카 + 나).
            int newCho = choFromJong(_jong);
            if (newCho < 0) {
                // Truly unknown jong (shouldn't happen for valid input) —
                // fall back to silent ㅇ as last resort.
                wchar_t syllable = compose();
                std::wstring out(1, syllable);
                reset();
                _cho     = 11;
                _jung    = vi;
                _rawJung = jamo;
                return out;
            }

            int savedJong = _jong;
            wchar_t savedRawJong = _rawJong;
            _jong    = -1;
            _rawJong = 0;
            wchar_t syllable = compose();
            std::wstring out(1, syllable);

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

// Drop the most recently added piece of the in-progress syllable.  Order of
// undo (the reverse of typing order):
//   1. Compound jong → simplify to its first part (ㄳ→ㄱ, ㄵ→ㄴ, …)
//   2. Single jong   → remove
//   3. Compound vowel → simplify to first part (ㅘ→ㅗ, ㅝ→ㅜ, …)
//   4. Jung (vowel)  → remove
//   5. Cho (consonant) → remove (composer becomes empty)
// Returns true if anything was undone.
bool HangulComposer::undoLastJamo() {
    if (_jong >= 0) {
        // Try simplify compound jong first
        batchim::SplitJong sj = batchim::splitCompoundJong(_jong);
        if (sj.firstJong >= 0) {
            _jong    = sj.firstJong;
            _rawJong = kJong[_jong];
        } else {
            _jong    = -1;
            _rawJong = 0;
        }
        return true;
    }
    if (_jung >= 0) {
        // Try simplify compound vowel (ㅘ → ㅗ etc.) by reverse-lookup of kVowelCompound
        if (_rawJung != 0) {
            for (const auto& vp : kVowelCompound) {
                if (vp.result == _rawJung) {
                    _jung    = jungIndex(vp.v1);
                    _rawJung = vp.v1;
                    return _jung >= 0;
                }
            }
        }
        _jung    = -1;
        _rawJung = 0;
        return true;
    }
    if (_cho >= 0) {
        _cho = -1;
        return true;
    }
    return false;
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

    // Cheap settings hot-reload check (kernel event poll, no I/O when nothing
    // changed).  Means edits to %APPDATA%\KorJpnIme\settings.ini take effect
    // on the next keystroke instead of requiring a logout/login cycle.
    _pIme->GetSettingsMutable().MaybeReload();

    // While the candidate window is up we eat navigation / selection / commit
    // keys so OnKeyDown can drive the conversion UI.
    if (_pIme->IsInConversion()) {
        if (vk == VK_SPACE  || vk == VK_TAB   || vk == VK_RETURN
         || vk == VK_ESCAPE || vk == VK_UP    || vk == VK_DOWN
         || vk == VK_PRIOR  || vk == VK_NEXT
         || (vk >= '1' && vk <= '9')) {
            *pfEaten = TRUE; return S_OK;
        }
        // Anything else: also eat (we'll commit selection then re-process key
        // — the key event still needs to reach OnKeyDown).
        *pfEaten = TRUE; return S_OK;
    }

    // Modifier-key combinations (Ctrl+X, Alt+X, Win+X) belong to the host
    // application as shortcuts — never eat them.  Plain Shift is allowed
    // because it's part of normal Korean jamo input (e.g. Shift+Q → ㅃ).
    // Track LEFT/RIGHT Alt independently so RAlt-only / LAlt-only hotkeys
    // (e.g. the Korean 한/영 key) can be detected.
    bool ctrl    = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    bool altL    = (GetKeyState(VK_LMENU)   & 0x8000) != 0;
    bool altR    = (GetKeyState(VK_RMENU)   & 0x8000) != 0;
    bool alt     = altL || altR;
    bool win     = (GetKeyState(VK_LWIN)    & 0x8000) != 0
                || (GetKeyState(VK_RWIN)    & 0x8000) != 0;
    bool sh2     = (GetKeyState(VK_SHIFT)   & 0x8000) != 0;
    // Exception: the user-configured katakana toggle hotkey is always eaten
    // even if it looks like a Ctrl/Alt/Win combo.
    {
        const Settings::Hotkey& hk = _pIme->GetSettings().KatakanaToggle();
        if (hk.IsValid() && hk.Matches(vk, ctrl, sh2, altL, altR, win)) {
            *pfEaten = TRUE; return S_OK;
        }
    }
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

    // Digits and ASCII punctuation are converted to full-width while the IME
    // is on (handled in OnKeyDown).  Eat them here so TSF doesn't pass through.
    if (_pIme->GetSettings().FullWidthAscii()) {
        if ((vk >= '0' && vk <= '9')
         || vk == VK_OEM_1 || vk == VK_OEM_2 || vk == VK_OEM_3
         || vk == VK_OEM_4 || vk == VK_OEM_5 || vk == VK_OEM_6
         || vk == VK_OEM_7 || vk == VK_OEM_PLUS) {
            *pfEaten = TRUE; return S_OK;
        }
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

// Try to start kanji conversion.  Strategy:
//   1. Flush any in-progress syllable into _pendingKana.
//   2. Find the LONGEST prefix of _pendingKana that has any dictionary
//      candidates (UserDict first, then system Dictionary).  If nothing
//      matches at any prefix length, fall back to the full pending string
//      committed raw as hiragana.
//   3. Build the candidate list for THAT prefix:
//        a. user-learned picks (UserDict.GetPreferred, sorted by usage count
//           desc — this is what makes the IME "remember" your choices)
//        b. system dictionary entries (Mozc OSS data, intrinsic cost order)
//        c. the raw matched prefix as kana (fallback)
//        d. katakana of the matched prefix (auto-katakana, always)
//      Dedup while preserving order so user picks bubble to the top.
//   4. When the matched prefix is shorter than the full pending (the
//      loanword case — dict couldn't find the whole word, e.g.
//      はんばーがー), also append full pending hiragana + full pending
//      katakana with the FULL pending as their prefix.  These let the
//      user commit the entire typed string in one shot via the candidate
//      window without having to flip a mode toggle.
//   5. Per-candidate prefixes are stored in `prefixes` parallel to `cands`;
//      anything after the chosen candidate's prefix in _pendingKana stays
//      as preedit after commit so the user can convert the rest as the
//      next segment.
//
// (No multi-prefix gathering, no 2-segment composition — those were tried
// previously and disrupted both the user-learning ordering and obvious
// candidate visibility.)
static bool TryStartConversion(KorJpnIme *pIme, HangulComposer& composer, ITfContext *pCtx) {
    std::wstring tail = composer.flush();
    if (!tail.empty()) AppendKanaFor(pIme, tail[0], 0);

    const std::wstring pending = pIme->PendingKana();
    if (pending.empty()) return false;

    const Dictionary *dict  = pIme->GetDictionary();
    const UserDict&   udict = pIme->GetUserDict();

    auto candidatesFor = [&](const std::wstring& key) {
        std::vector<std::wstring> v;
        auto pushU = [&](const std::wstring& s) {
            if (s.empty()) return;
            if (std::find(v.begin(), v.end(), s) != v.end()) return;
            v.push_back(s);
        };
        for (auto& k : udict.GetPreferred(key)) pushU(k);     // user-learned first
        if (dict) for (auto& k : dict->Lookup(key)) pushU(k); // system dict
        return v;
    };

    // Find the LONGEST prefix that has any candidates.
    std::wstring matched;
    std::vector<std::wstring> cands;
    for (size_t len = pending.size(); len > 0; --len) {
        std::wstring prefix = pending.substr(0, len);
        auto v = candidatesFor(prefix);
        if (!v.empty()) {
            matched = std::move(prefix);
            cands   = std::move(v);
            break;
        }
    }
    if (matched.empty()) {
        // No prefix had any dict entries — let the user commit the whole
        // pending as raw kana via the candidate window's fallback.
        matched = pending;
    }

    // Always include the raw matched kana at the end as a fallback.
    if (std::find(cands.begin(), cands.end(), matched) == cands.end()) {
        cands.push_back(matched);
    }
    if (cands.empty()) return false;

    // Every kanji/dict candidate so far consumes the matched prefix.
    std::vector<std::wstring> prefixes(cands.size(), matched);

    // ---- Auto-katakana suggestions ---------------------------------------
    // The dict builder strips pure-kana surfaces, so loanwords like
    // ハンバーガー never reach the candidate list through the normal lookup
    // path.  Synthesize them here so the user always has a katakana option
    // without needing a mode toggle.
    //
    // Two layers:
    //   (a) katakana of the matched prefix -- always offered, sits next to
    //       the raw-hiragana fallback (so わたし also gets ワタシ in the list).
    //   (b) full pending hiragana + katakana -- only when the longest dict
    //       prefix is shorter than what the user actually typed.  This is
    //       the loanword case (e.g. はんばーがー matches only は in the
    //       dict, leaving んばーがー stranded).  These carry the FULL
    //       pending as their prefix so picking them consumes everything in
    //       one commit.
    auto addCandidate = [&](const std::wstring& s, const std::wstring& prefix) {
        if (s.empty()) return;
        if (std::find(cands.begin(), cands.end(), s) != cands.end()) return;
        cands.push_back(s);
        prefixes.push_back(prefix);
    };

    addCandidate(kana::toKatakanaStr(matched), matched);

    if (matched.size() < pending.size()) {
        addCandidate(pending, pending);
        addCandidate(kana::toKatakanaStr(pending), pending);
    }

    pIme->EnterConversion(cands, prefixes, pCtx);
    return true;
}

// Commit the currently-selected candidate, learn it, drop ONLY that
// candidate's prefix from pending (so any unmatched suffix stays as preedit
// for the next conversion round), and exit conversion mode.
static void CommitSelectedCandidate(KorJpnIme *pIme, ITfContext *pCtx) {
    std::wstring sel    = pIme->GetCandidateWindow().GetSelected();
    std::wstring prefix = pIme->SelectedPrefix();      // per-candidate prefix
    std::wstring fullPending = pIme->PendingKana();

    if (!sel.empty()) {
        pIme->CommitText(pCtx, sel);
        if (sel != prefix) pIme->GetUserDict().Record(prefix, sel);
    }

    std::wstring remaining;
    if (fullPending.size() >= prefix.size()
     && fullPending.compare(0, prefix.size(), prefix) == 0) {
        remaining = fullPending.substr(prefix.size());
    }

    pIme->ClearPending();
    pIme->ExitConversion();

    if (remaining.empty()) {
        pIme->UpdatePreedit(pCtx, L"");
    } else {
        pIme->AppendKana(remaining);
        pIme->UpdatePreedit(pCtx, remaining);
    }
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
    // LEFT/RIGHT Alt are tracked independently so the user-configured hotkey
    // can be RAlt-only (e.g. the Korean 한/영 key) without firing on LAlt.
    bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
    bool altL = (GetKeyState(VK_LMENU)   & 0x8000) != 0;
    bool altR = (GetKeyState(VK_RMENU)   & 0x8000) != 0;
    bool alt  = altL || altR;
    bool win  = (GetKeyState(VK_LWIN)    & 0x8000) != 0
             || (GetKeyState(VK_RWIN)    & 0x8000) != 0;

    // IME-specific shortcut taking precedence over the generic modifier
    // pass-through: the user-configured katakana toggle (default RAlt+K).
    // Lives in %APPDATA%\KorJpnIme\settings.ini under [Hotkeys] KatakanaToggle.
    {
        const Settings::Hotkey& hk = _pIme->GetSettings().KatakanaToggle();
        if (hk.IsValid() && hk.Matches(vk, ctrl, shifted, altL, altR, win)) {
            _pIme->ToggleKatakanaMode();
            if (!_pIme->PendingKana().empty()) {
                _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
            }
            *pfEaten = TRUE;
            return S_OK;
        }
    }

    if (ctrl || alt || win) {
        // Flush so any in-progress preedit is committed before the shortcut runs.
        FlushAndCommit(_pIme, _composer, pCtx);
        *pfEaten = FALSE;
        return S_OK;
    }

    // ---- F6 / F7: character-type conversion --------------------------------
    // F6 → commit pending as hiragana (cancels any active conversion).
    // F7 → commit pending as full-width katakana.
    // Both work whether or not the candidate window is up — Standard JP-IME
    // convention is to use them as a quick "I don't want kanji, just commit
    // the kana form" shortcut.
    if (vk == VK_F6 || vk == VK_F7) {
        // Drop the candidate window if it's open — F6/F7 mean "ignore conversion".
        if (_pIme->IsInConversion()) _pIme->ExitConversion();

        std::wstring tail = _composer.flush();
        if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);

        std::wstring text = _pIme->PendingKana();
        if (text.empty()) {
            *pfEaten = FALSE;
            return S_OK;
        }
        if (vk == VK_F7) text = toKatakanaStr(text);

        _pIme->CommitText(pCtx, text);
        _pIme->ClearPending();
        _pIme->UpdatePreedit(pCtx, L"");
        *pfEaten = TRUE;
        return S_OK;
    }

    // ---- CONVERSION MODE keys ---------------------------------------------
    // When the candidate window is up the only keys we honour are navigation,
    // selection, and the F6/F7 character-type shortcuts; everything else
    // commits the current selection first and then falls through to be
    // processed against an empty composer.
    if (_pIme->IsInConversion()) {
        CandidateWindow& cw = _pIme->GetCandidateWindow();
        if (vk == VK_SPACE || vk == VK_DOWN) {
            cw.SelectNext();
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_TAB) {
            // Tab "expands" the candidate window so the user can browse a
            // larger list with arrow keys or pick with the mouse.  Once
            // expanded it stays that way for the rest of this conversion.
            cw.SetExpanded(true);
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_UP) {
            cw.SelectPrev();
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_NEXT) {        // PageDown
            cw.NextPage();
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_PRIOR) {       // PageUp
            cw.PrevPage();
            *pfEaten = TRUE; return S_OK;
        }
        if (vk >= '1' && vk <= '9') {
            int slot = vk - '1';     // 0..8 within current page
            if (cw.SelectOnPage(slot)) {
                CommitSelectedCandidate(_pIme, pCtx);
            }
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_RETURN) {
            CommitSelectedCandidate(_pIme, pCtx);
            *pfEaten = TRUE; return S_OK;
        }
        if (vk == VK_ESCAPE) {
            // Cancel conversion — keep the kana preedit so user can keep typing.
            _pIme->ExitConversion();
            _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
            *pfEaten = TRUE; return S_OK;
        }
        // F6 / F7: discard candidate selection, commit the raw kana as
        // hiragana / katakana respectively.
        if (vk == VK_F6 || vk == VK_F7) {
            std::wstring text = _pIme->PendingKana();
            if (vk == VK_F7) text = toKatakanaStr(text);
            _pIme->ExitConversion();
            _pIme->CommitText(pCtx, text);
            _pIme->ClearPending();
            _pIme->UpdatePreedit(pCtx, L"");
            *pfEaten = TRUE; return S_OK;
        }
        // Anything else: commit current selection and let the key be re-processed
        // by the normal path below (so e.g. typing a new jamo starts a new word).
        CommitSelectedCandidate(_pIme, pCtx);
        // fall through
    }

    // ---- Space / Enter / Tab: commit accumulated preedit ------------------
    // Space tries to enter kanji conversion first (if there's a pending kana
    // word); Enter / Tab commit raw without conversion.
    if (vk == VK_SPACE) {
        bool hadComposing = !_composer.empty() || !_pIme->PendingKana().empty();
        if (hadComposing && TryStartConversion(_pIme, _composer, pCtx)) {
            *pfEaten = TRUE; return S_OK;
        }
        FlushAndCommit(_pIme, _composer, pCtx);
        *pfEaten = hadComposing ? TRUE : FALSE;
        return S_OK;
    }
    if (vk == VK_RETURN || vk == VK_TAB) {
        bool hadComposing = !_composer.empty() || !_pIme->PendingKana().empty();
        FlushAndCommit(_pIme, _composer, pCtx);
        (void)hadComposing;
        *pfEaten = FALSE;       // pass Enter/Tab through after commit
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

    // Backspace — undo strategy:
    //   1. If the composer has an in-progress syllable, peel ONE jamo off it
    //      (per-jamo undo, including simplifying compound jongs / vowels).
    //   2. Otherwise, if there's pending kana, drop the last kana codepoint.
    //   3. Otherwise, pass through so the host app can delete characters.
    if (vk == VK_BACK) {
        if (!_composer.empty()) {
            _composer.undoLastJamo();
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
    if (vk == VK_OEM_COMMA || vk == VK_OEM_PERIOD) {
        std::wstring tail = _composer.flush();
        if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);
        _pIme->AppendKana(vk == VK_OEM_COMMA ? L"\u3001" : L"\u3002");
        _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
        *pfEaten = TRUE;
        return S_OK;
    }

    // Digits and ASCII symbols → full-width (zenkaku) equivalents while the
    // IME is on.  Can be disabled via [Behavior] FullWidthAscii = false in
    // settings.ini (then these keys fall through as plain ASCII).
    if (_pIme->GetSettings().FullWidthAscii()) {
        wchar_t zen = 0;
        if (vk >= '0' && vk <= '9' && !shifted) {
            zen = static_cast<wchar_t>(0xFF10 + (vk - '0'));   // 0xFF10 = '０'
        } else {
            // Common ASCII punctuation → zenkaku
            // Each entry maps a (shifted, vk) pair to its full-width char.
            struct Punc { bool shifted; UINT vk; wchar_t zen; };
            static constexpr Punc kPunc[] = {
                // unshifted symbols on a US keyboard
                { false, VK_OEM_1,         L'\uFF1B' }, // ;  → ；
                { false, VK_OEM_2,         L'\uFF0F' }, // /  → ／
                { false, VK_OEM_3,         L'\uFF40' }, // `  → ｀
                { false, VK_OEM_4,         L'\uFF3B' }, // [  → ［
                { false, VK_OEM_5,         L'\uFFE5' }, // \  → ¥ (Japanese yen sign)
                { false, VK_OEM_6,         L'\uFF3D' }, // ]  → ］
                { false, VK_OEM_7,         L'\uFF07' }, // '  → ＇
                { false, VK_OEM_PLUS,      L'\uFF1D' }, // =  → ＝
                // shifted symbols
                { true,  '1',              L'\uFF01' }, // !  → ！
                { true,  '2',              L'\uFF20' }, // @  → ＠
                { true,  '3',              L'\uFF03' }, // #  → ＃
                { true,  '4',              L'\uFF04' }, // $  → ＄
                { true,  '5',              L'\uFF05' }, // %  → ％
                { true,  '6',              L'\uFF3E' }, // ^  → ＾
                { true,  '7',              L'\uFF06' }, // &  → ＆
                { true,  '8',              L'\uFF0A' }, // *  → ＊
                { true,  '9',              L'\uFF08' }, // (  → （
                { true,  '0',              L'\uFF09' }, // )  → ）
                { true,  VK_OEM_1,         L'\uFF1A' }, // :  → ：
                { true,  VK_OEM_2,         L'\uFF1F' }, // ?  → ？
                { true,  VK_OEM_3,         L'\uFF5E' }, // ~  → ～
                { true,  VK_OEM_4,         L'\uFF5B' }, // {  → ｛
                { true,  VK_OEM_5,         L'\uFF5C' }, // |  → ｜
                { true,  VK_OEM_6,         L'\uFF5D' }, // }  → ｝
                { true,  VK_OEM_7,         L'\uFF02' }, // "  → ＂
                { true,  VK_OEM_PLUS,      L'\uFF0B' }, // +  → ＋
            };
            for (const auto& p : kPunc) {
                if (p.vk == vk && p.shifted == shifted) { zen = p.zen; break; }
            }
        }
        if (zen != 0) {
            std::wstring tail = _composer.flush();
            if (!tail.empty()) AppendKanaFor(_pIme, tail[0], 0);
            _pIme->AppendKana(std::wstring(1, zen));
            _pIme->UpdatePreedit(pCtx, BuildPreedit(_pIme, _composer));
            *pfEaten = TRUE;
            return S_OK;
        }
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
        // sokuon_strict / sokuon_universal decisions.  Doubled-vowel sentinels
        // produced by the special patterns get translated directly to the
        // Japanese particle they represent; AppendKana honours the persistent
        // katakana toggle so the result becomes ヲ / ハ / ヘ when that mode
        // is active.
        const wchar_t *particle = nullptr;
        switch (completed[0]) {
            case HangulComposer::kWoMarker: particle = L"\u3092"; break;  // を
            case HangulComposer::kWaMarker: particle = L"\u306F"; break;  // は
            case HangulComposer::kEMarker:  particle = L"\u3078"; break;  // へ
        }
        if (particle) {
            _pIme->AppendKana(particle);
        } else {
            AppendKanaFor(_pIme, completed[0], jamo);
        }
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
