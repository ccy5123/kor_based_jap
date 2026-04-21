#pragma once
#include "Globals.h"
#include "Dictionary.h"
#include "UserDict.h"
#include "CandidateWindow.h"
#include "KanaConv.h"
#include "Settings.h"
#include <string>
#include <vector>

// Forward declarations
class KeyHandler;
class Composition;

// @MX:ANCHOR: KorJpnIme is the root COM object — ITfTextInputProcessor entry point
// @MX:REASON: All TSF subsystems (key handler, composition) are owned and lifetime-managed here
class KorJpnIme : public ITfTextInputProcessor {
public:
    KorJpnIme();
    ~KorJpnIme();

    // IUnknown
    STDMETHODIMP         QueryInterface(REFIID, void**) override;
    STDMETHODIMP_(ULONG) AddRef()  override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfTextInputProcessor
    STDMETHODIMP Activate(ITfThreadMgr *pThreadMgr, TfClientId tid) override;
    STDMETHODIMP Deactivate() override;

    // Accessors used by KeyHandler and Composition
    TfClientId  GetClientId() const { return _tid; }
    ITfThreadMgr* GetThreadMgr() const { return _pThreadMgr; }

    // Called by KeyHandler to update UI
    HRESULT CommitText(ITfContext *pCtx, const std::wstring& text);
    HRESULT UpdatePreedit(ITfContext *pCtx, const std::wstring& text);

    // IME On/Off state (toggled by VK_CONVERT / VK_NONCONVERT)
    bool IsActive() const { return _active; }
    void SetActive(bool active) { _active = active; }

    // Kana→kanji dictionary (loaded from jpn_dict.txt next to the DLL).
    // Returns nullptr if the dictionary file was missing or failed to load.
    const Dictionary* GetDictionary() const {
        return _dict.IsLoaded() ? &_dict : nullptr;
    }

    // Per-user learning dictionary (user_dict.txt next to the DLL).
    UserDict&       GetUserDict()       { return _userDict; }
    const UserDict& GetUserDict() const { return _userDict; }

    // User settings loaded from %APPDATA%\KorJpnIme\settings.ini
    const Settings& GetSettings()        const { return _settings; }
    // Mutable accessor used by KeyHandler::OnTestKeyDown to drive
    // Settings::MaybeReload() — the hot-reload poll mutates the cached
    // notification handle and may re-Load() the INI in place.
    Settings&       GetSettingsMutable()       { return _settings; }

    // Kana accumulation buffer for standard JP-IME-style preedit.
    // KeyHandler appends to this buffer when a syllable completes; Space
    // triggers conversion (kanji candidate window); Enter commits raw kana.
    // When katakana mode is on, appended kana is upper-cased to katakana.
    const std::wstring& PendingKana() const { return _pendingKana; }
    void AppendKana(const std::wstring& kana) {
        _pendingKana += _katakanaMode ? kana::toKatakanaStr(kana) : kana;
    }
    void ClearPending() { _pendingKana.clear(); }

    // Katakana mode toggle (F9 by default).  When on, all newly-appended kana
    // are stored as katakana and the preedit display reflects that.  Existing
    // pending kana is converted in place when the mode changes so the user
    // sees an immediate visual effect.
    bool IsKatakanaMode() const { return _katakanaMode; }
    void ToggleKatakanaMode() {
        _katakanaMode = !_katakanaMode;
        if (!_pendingKana.empty()) {
            _pendingKana = _katakanaMode
                ? kana::toKatakanaStr(_pendingKana)
                : kana::toHiraganaStr(_pendingKana);
        }
    }

    // ---- Kanji conversion mode -------------------------------------------
    // Enter: dictionary lookup just produced candidates; show the popup and
    // remember that future Space/arrow/digit keys cycle/select candidates.
    // Exit: hide the popup; whether to commit a candidate or restore preedit
    // is decided by the caller.
    bool IsInConversion() const { return _inConversion; }
    CandidateWindow& GetCandidateWindow() { return _candidateWindow; }

    // EnterConversion remembers
    //   - the active context (AddRef'd) so mouse-triggered commits can fire
    //     outside the original OnKeyDown call;
    //   - a parallel array of the kana PREFIX each candidate corresponds to.
    //     When the user commits a candidate, only that candidate's prefix is
    //     consumed from _pendingKana — anything after the prefix stays as
    //     preedit so the user can keep converting one segment at a time.
    void EnterConversion(const std::vector<std::wstring>& candidates,
                         const std::vector<std::wstring>& candidatePrefixes,
                         ITfContext *pCtx);
    void ExitConversion();

    // The kana prefix that the currently-selected candidate would consume on
    // commit.  Empty before EnterConversion is called.
    std::wstring SelectedPrefix() const;
    std::wstring PrefixOf(int candidateIdx) const;

    // Called by CandidateWindow when the user picks a candidate with the mouse.
    // Invokes the same commit + learn + remaining-kana logic as the keyboard path.
    void OnCandidateClicked(int idx);

    // Caret rect (screen coordinates of the preedit text), updated by
    // Composition's edit sessions via ITfContextView::GetTextExt so the
    // candidate window can pop up exactly under the user's preedit.
    void SetCaretRect(const RECT& r) { _caretRect = r; _hasCaretRect = true; }
    bool GetCaretRect(RECT *out) const {
        if (!_hasCaretRect) return false;
        *out = _caretRect;
        return true;
    }

private:
    void _LoadDictionary();   // called once from Activate()
    LONG          _cRef;
    TfClientId    _tid    = TF_CLIENTID_NULL;
    ITfThreadMgr *_pThreadMgr = nullptr;  // not AddRef'd — owned by TSF runtime

    KeyHandler   *_pKeyHandler  = nullptr;
    Composition  *_pComposition = nullptr;
    // IME on by default — when the user switches to our TIP via Win+Space they
    // expect Japanese output immediately (한자/VK_CONVERT toggles to/from passthrough).
    bool          _active       = true;

    Dictionary      _dict;            // loaded once on first Activate()
    UserDict        _userDict;
    Settings        _settings;
    bool            _dictTried = false;
    std::wstring    _pendingKana;     // accumulated kana waiting for commit/conversion
    CandidateWindow _candidateWindow;
    bool            _inConversion = false;

    RECT            _caretRect = {};  // screen coords of last known preedit
    bool            _hasCaretRect = false;
    ITfContext     *_convCtx = nullptr;        // AddRef'd while in conversion mode
    std::vector<std::wstring> _candidatePrefixes;   // parallel to candidates list
    bool            _katakanaMode = false;          // F9 toggle
};
