#pragma once
#include "Globals.h"
#include <string>

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

private:
    LONG          _cRef;
    TfClientId    _tid    = TF_CLIENTID_NULL;
    ITfThreadMgr *_pThreadMgr = nullptr;  // not AddRef'd — owned by TSF runtime

    KeyHandler   *_pKeyHandler  = nullptr;
    Composition  *_pComposition = nullptr;
    // IME on by default — when the user switches to our TIP via Win+Space they
    // expect Japanese output immediately (한자/VK_CONVERT toggles to/from passthrough).
    bool          _active       = true;
};
