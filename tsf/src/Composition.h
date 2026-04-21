#pragma once
#include "Globals.h"
#include <string>

// Manages a TSF composition (preedit) and commits finalized text.
// Implements ITfCompositionSink so StartComposition has a valid sink (some
// Windows versions reject NULL sinks with E_INVALIDARG).
class Composition : public ITfCompositionSink {
public:
    explicit Composition(class KorJpnIme *pIme);
    ~Composition();

    HRESULT UpdatePreedit(ITfContext *pCtx, const std::wstring& text);
    HRESULT Commit(ITfContext *pCtx, const std::wstring& text);

    bool IsComposing() const { return _pComposition != nullptr; }

    void _SetComposition(ITfComposition *pComp);
    void _ClearComposition();

    // Called from edit-session callbacks: queries TSF for the screen-coordinate
    // bounding box of the composition range and caches it on the IME so the
    // candidate window can pop up next to the actual preedit.
    void _UpdateCaretRect(TfEditCookie ec, ITfContext *pCtx, ITfComposition *pComp);

    // IUnknown
    STDMETHODIMP         QueryInterface(REFIID riid, void **ppv) override;
    STDMETHODIMP_(ULONG) AddRef()  override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfCompositionSink — fires when host app terminates the composition
    // (e.g., user clicks elsewhere).  We just clear our pointer.
    STDMETHODIMP OnCompositionTerminated(TfEditCookie ecWrite,
                                          ITfComposition *pComposition) override;

private:
    HRESULT _StartAndSetText(ITfContext *pCtx, const std::wstring& text, bool terminateAfter);
    HRESULT _SetText(ITfContext *pCtx, const std::wstring& text, bool terminateAfter);

    LONG              _cRef = 1;
    KorJpnIme        *_pIme;
    ITfComposition   *_pComposition = nullptr;
};

// ============================================================================
// Edit session: start a new composition AND set its initial text in one go.
// On success, calls Composition::_SetComposition() with the new ITfComposition*.
// ============================================================================
struct StartAndSetTextSession : public ITfEditSession {
    StartAndSetTextSession(Composition *pCompMgr, ITfContext *pCtx,
                           std::wstring text, bool terminateAfter)
        : _cRef(1), _pCompMgr(pCompMgr), _pCtx(pCtx),
          _text(std::move(text)), _terminate(terminateAfter) {
        _pCtx->AddRef();
    }
    ~StartAndSetTextSession() { _pCtx->Release(); }

    STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override {
        if (riid == IID_IUnknown || riid == IID_ITfEditSession) {
            *ppv = static_cast<ITfEditSession*>(this);
            AddRef(); return S_OK;
        }
        *ppv = nullptr; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef()  override { return InterlockedIncrement(&_cRef); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&_cRef);
        if (!c) delete this;
        return c;
    }
    STDMETHODIMP DoEditSession(TfEditCookie ec) override;

    LONG          _cRef;
    Composition  *_pCompMgr;
    ITfContext   *_pCtx;
    std::wstring  _text;
    bool          _terminate;
};

// ============================================================================
// Edit session: replace text of an EXISTING composition (and optionally end it).
// ============================================================================
struct SetTextSession : public ITfEditSession {
    SetTextSession(Composition *pCompMgr, ITfComposition *pComp,
                   std::wstring text, bool terminate)
        : _cRef(1), _pCompMgr(pCompMgr), _pComp(pComp), _pCtx(nullptr),
          _text(std::move(text)), _terminate(terminate) {
        if (_pComp) _pComp->AddRef();
    }
    ~SetTextSession() {
        if (_pComp) _pComp->Release();
        if (_pCtx)  _pCtx->Release();
    }

    STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override {
        if (riid == IID_IUnknown || riid == IID_ITfEditSession) {
            *ppv = static_cast<ITfEditSession*>(this);
            AddRef(); return S_OK;
        }
        *ppv = nullptr; return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef()  override { return InterlockedIncrement(&_cRef); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&_cRef);
        if (!c) delete this;
        return c;
    }
    STDMETHODIMP DoEditSession(TfEditCookie ec) override;

    LONG            _cRef;
    Composition    *_pCompMgr;
    ITfComposition *_pComp;
    ITfContext     *_pCtx;       // needed only when _terminate=true (for SetSelection)
    std::wstring    _text;
    bool            _terminate;
};
