#include "KeyHandler.h"
#include "KorJpnIme.h"
#include "Composition.h"
#include "BatchimLookup.h"  // includes mapping_table.h + batchim_rules.h
#include "Dictionary.h"
#include "RichDictionary.h"
#include "Viterbi.h"
#include "CandidateWindow.h"
#include "KanaConv.h"
#include "DebugLog.h"
#include <algorithm>

// (Hiragana <-> katakana helpers live in KanaConv.h, shared with KorJpnIme.)
using kana::toKatakanaStr;

// VkToJamo and HangulComposer have been moved to HangulComposer.cpp so the
// unit-test target can link them without pulling in the full TSF / COM
// surface that the KeyHandler class below depends on.

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

// Try to start kanji conversion.  Strategy (viterbi-first with legacy
// fallback):
//
//   1. Flush any in-progress syllable into _pendingKana.
//
//   2. If the viterbi engine is loaded (kj_dict.bin + kj_conn.bin present),
//      run a 1-best segmentation over the full pending string.  The result
//      gives us:
//        - the recommended FIRST-SEGMENT length (e.g. for わたしの it picks
//          わたし -> 私 + の -> の, so the first segment is わたし)
//        - a JOINED surface for the whole pending (e.g. "私の") that the
//          user can commit in one shot
//
//      Otherwise (viterbi engine not loaded) fall back to the legacy
//      longest-prefix lookup over the text Dictionary.
//
//   3. Build the candidate list:
//        a. viterbi joined surface (top, consumes ALL pending) -- only
//           when there are 2+ real segments
//        b. user-learned picks for the first segment (UserDict.GetPreferred)
//        c. RichDictionary entries for the first segment (cost-sorted)
//        d. legacy Dictionary entries for the first segment (catches
//           anything RichDictionary missed)
//        e. raw first-segment kana (fallback)
//        f. katakana of first-segment kana (auto-katakana)
//        g. full pending hiragana + katakana when matched < pending
//           (loanword fallback so ハンバーガー still works for OOV input)
//
//   4. Per-candidate prefixes parallel to `cands`; picking a candidate
//      consumes its prefix and leaves the rest of pending as preedit for
//      the next round.
static bool TryStartConversion(KorJpnIme *pIme, HangulComposer& composer, ITfContext *pCtx) {
    std::wstring tail = composer.flush();
    if (!tail.empty()) AppendKanaFor(pIme, tail[0], 0);

    const std::wstring pending = pIme->PendingKana();
    if (pending.empty()) return false;

    const Dictionary     *dict     = pIme->GetDictionary();
    const RichDictionary &rich     = pIme->GetRichDictionary();
    const Connector      &conn     = pIme->GetConnector();
    const UserDict       &udict    = pIme->GetUserDict();

    std::vector<std::wstring> cands;
    std::vector<std::wstring> prefixes;
    auto addCandidate = [&](const std::wstring& s, const std::wstring& prefix) {
        if (s.empty()) return;
        if (std::find(cands.begin(), cands.end(), s) != cands.end()) return;
        cands.push_back(s);
        prefixes.push_back(prefix);
    };

    std::wstring matched;

    // ---- Viterbi path ---------------------------------------------------
    Viterbi viterbi(rich, conn);
    Viterbi::Result vrs;
    if (viterbi.IsReady()) {
        vrs = viterbi.Best(pending);
    }

    if (!vrs.empty()) {
        // First-segment span comes from viterbi's segmentation choice.
        const auto& first = vrs.segments.front();
        matched = pending.substr(first.kanaStart, first.kanaLen);

        // (a) Joined surface across ALL segments -- top candidate when
        // there is more than one segment AND at least one segment is a
        // real dictionary hit (otherwise the joined surface is just the
        // raw kana, which is already covered by the fallback below).
        if (vrs.segments.size() >= 2) {
            bool anyReal = false;
            for (const auto& s : vrs.segments) if (!s.isUnknown) { anyReal = true; break; }
            if (anyReal) {
                addCandidate(vrs.joinedSurface(), pending);
            }
        }

        // (b) User-learned picks for the matched prefix
        for (auto& k : udict.GetPreferred(matched)) addCandidate(k, matched);

        // (c) RichDictionary surfaces for the matched prefix (cost-sorted)
        for (auto& e : rich.Lookup(matched)) addCandidate(e.surface, matched);

        // (d) Legacy text dictionary -- usually a subset of (c) but kept
        // around as a belt-and-braces fallback during the viterbi rollout.
        if (dict) for (auto& k : dict->Lookup(matched)) addCandidate(k, matched);
    } else {
        // ---- Legacy path: longest-prefix lookup -------------------------
        auto candidatesFor = [&](const std::wstring& key) {
            std::vector<std::wstring> v;
            auto pushU = [&](const std::wstring& s) {
                if (s.empty()) return;
                if (std::find(v.begin(), v.end(), s) != v.end()) return;
                v.push_back(s);
            };
            for (auto& k : udict.GetPreferred(key)) pushU(k);
            if (dict) for (auto& k : dict->Lookup(key)) pushU(k);
            return v;
        };
        for (size_t len = pending.size(); len > 0; --len) {
            std::wstring prefix = pending.substr(0, len);
            auto v = candidatesFor(prefix);
            if (!v.empty()) {
                matched = std::move(prefix);
                for (auto& k : v) addCandidate(k, matched);
                break;
            }
        }
        if (matched.empty()) {
            matched = pending;
        }
    }

    // ---- Common tail: raw kana + auto-katakana --------------------------
    // (e) Raw matched kana as a literal commit option.
    addCandidate(matched, matched);
    // (f) Katakana of the matched prefix.  The dict builder strips
    // pure-kana surfaces, so loanwords like ワタシ never reach the list
    // through the normal lookup path; synthesize them here.
    addCandidate(kana::toKatakanaStr(matched), matched);
    // (g) Full pending hiragana + katakana when the matched prefix is
    // shorter than what the user typed -- the loanword case (e.g.
    // はんばーがー matches only は, leaving んばーがー stranded).  These
    // carry the FULL pending as their prefix so picking them consumes
    // everything in one commit.
    if (matched.size() < pending.size()) {
        addCandidate(pending, pending);
        addCandidate(kana::toKatakanaStr(pending), pending);
    }

    if (cands.empty()) return false;
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
