#pragma once
#include "Globals.h"
#include "Dictionary.h"
#include "CandidateWindow.h"
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

    // Kana accumulation buffer for standard JP-IME-style preedit.
    // KeyHandler appends to this buffer when a syllable completes; Space
    // triggers conversion (kanji candidate window); Enter commits raw kana.
    const std::wstring& PendingKana() const { return _pendingKana; }
    void                AppendKana(const std::wstring& kana) { _pendingKana += kana; }
    void                ClearPending()                         { _pendingKana.clear(); }

    // ---- Kanji conversion mode -------------------------------------------
    // Enter: dictionary lookup just produced candidates; show the popup and
    // remember that future Space/arrow/digit keys cycle/select candidates.
    // Exit: hide the popup; whether to commit a candidate or restore preedit
    // is decided by the caller.
    bool IsInConversion() const { return _inConversion; }
    CandidateWindow& GetCandidateWindow() { return _candidateWindow; }
    void EnterConversion(const std::vector<std::wstring>& candidates) {
        _inConversion = true;
        _candidateWindow.Show(candidates);
    }
    void ExitConversion() {
        _inConversion = false;
        _candidateWindow.Hide();
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
    bool            _dictTried = false;
    std::wstring    _pendingKana;     // accumulated kana waiting for commit/conversion
    CandidateWindow _candidateWindow;
    bool            _inConversion = false;
};
