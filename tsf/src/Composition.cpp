#include "Composition.h"
#include "KorJpnIme.h"
#include "DebugLog.h"

// ============================================================================
// Composition manager — preedit display + commit.
//
// Pattern (from MS TSF samples):
//   1. GetSelection to find the insertion point (a real range owned by the doc).
//   2. Clone + Collapse(ANCHOR_START) → a zero-length range AT the insertion point.
//   3. StartComposition() with that range — this gives us a live ITfComposition.
//   4. Get the composition's range and SetText() to write the preedit.
//   5. On commit: SetText(final), then EndComposition(), then move selection
//      past the inserted text.
//
// All of this happens inside an async ITfEditSession (we cannot SYNC inside
// OnKeyDown), so the session writes the resulting ITfComposition* directly into
// Composition's member to avoid stack-pointer dangling.
// ============================================================================

namespace {

// Helper: clone the current selection's range and collapse to the start.
// Returns a zero-length range at the insertion point, owned by the caller.
HRESULT GetInsertionRange(TfEditCookie ec, ITfContext *pCtx, ITfRange **ppOut) {
    *ppOut = nullptr;
    TF_SELECTION sel = {};
    ULONG fetched = 0;
    HRESULT hr = pCtx->GetSelection(ec, TF_DEFAULT_SELECTION, 1, &sel, &fetched);
    if (FAILED(hr) || fetched == 0 || !sel.range) return FAILED(hr) ? hr : E_FAIL;

    ITfRange *pClone = nullptr;
    hr = sel.range->Clone(&pClone);
    sel.range->Release();
    if (FAILED(hr)) return hr;

    pClone->Collapse(ec, TF_ANCHOR_START);
    *ppOut = pClone;
    return S_OK;
}

} // namespace

// ============================================================================
// Session: start a composition AND set its preedit text in one shot.
// ============================================================================
STDMETHODIMP StartAndSetTextSession::DoEditSession(TfEditCookie ec) {
    DBGF("StartAndSetTextSession::Do  text.len=%zu  term=%d", _text.size(), (int)_terminate);

    ITfRange *pInsRange = nullptr;
    HRESULT hr = GetInsertionRange(ec, _pCtx, &pInsRange);
    if (FAILED(hr)) { DBGF("  GetInsertionRange hr=0x%08lX", (long)hr); return hr; }

    ITfContextComposition *pCtxComp = nullptr;
    hr = _pCtx->QueryInterface(IID_ITfContextComposition, (void**)&pCtxComp);
    if (FAILED(hr)) { pInsRange->Release(); return hr; }

    ITfComposition *pNewComp = nullptr;
    hr = pCtxComp->StartComposition(ec, pInsRange,
                                     static_cast<ITfCompositionSink*>(_pCompMgr),
                                     &pNewComp);
    pCtxComp->Release();
    pInsRange->Release();
    if (FAILED(hr) || !pNewComp) {
        DBGF("  StartComposition hr=0x%08lX  pNewComp=%p", (long)hr, (void*)pNewComp);
        return FAILED(hr) ? hr : E_FAIL;
    }

    // Write preedit text into the composition range
    if (!_text.empty()) {
        ITfRange *pRange = nullptr;
        if (SUCCEEDED(pNewComp->GetRange(&pRange))) {
            pRange->SetText(ec, 0, _text.c_str(), static_cast<LONG>(_text.size()));
            pRange->Release();
        }
    }

    if (_terminate) {
        // Commit-and-end path: move selection past the text, then end composition
        ITfRange *pRange = nullptr;
        if (SUCCEEDED(pNewComp->GetRange(&pRange))) {
            pRange->Collapse(ec, TF_ANCHOR_END);
            TF_SELECTION sel = {};
            sel.range = pRange;
            sel.style.ase = TF_AE_END;
            sel.style.fInterimChar = FALSE;
            _pCtx->SetSelection(ec, 1, &sel);
            pRange->Release();
        }
        pNewComp->EndComposition(ec);
        pNewComp->Release();
        _pCompMgr->_ClearComposition();
    } else {
        _pCompMgr->_SetComposition(pNewComp);  // ownership transfer
        _pCompMgr->_UpdateCaretRect(ec, _pCtx, pNewComp);
    }
    DBG("  StartAndSetTextSession OK");
    return S_OK;
}

// ============================================================================
// Session: replace text of an existing composition; optionally end it.
// ============================================================================
STDMETHODIMP SetTextSession::DoEditSession(TfEditCookie ec) {
    DBGF("SetTextSession::Do  text.len=%zu  term=%d", _text.size(), (int)_terminate);
    if (!_pComp) return E_FAIL;

    ITfRange *pRange = nullptr;
    HRESULT hr = _pComp->GetRange(&pRange);
    if (FAILED(hr)) return hr;

    hr = pRange->SetText(ec, 0, _text.c_str(), static_cast<LONG>(_text.size()));
    if (FAILED(hr)) { pRange->Release(); return hr; }

    if (_terminate) {
        // Move caret to end of inserted text
        pRange->Collapse(ec, TF_ANCHOR_END);
        TF_SELECTION sel = {};
        sel.range = pRange;
        sel.style.ase = TF_AE_END;
        sel.style.fInterimChar = FALSE;
        _pCtx->SetSelection(ec, 1, &sel);
        pRange->Release();
        _pComp->EndComposition(ec);
        _pCompMgr->_ClearComposition();
    } else {
        pRange->Release();
        // Re-cache the caret rect after preedit text changes — the box
        // typically grows/shrinks with each keystroke and we want the
        // candidate window pinned to its CURRENT bottom edge.
        _pCompMgr->_UpdateCaretRect(ec, _pCtx, _pComp);
    }
    return S_OK;
}

// ============================================================================
// Composition — also acts as ITfCompositionSink (passed to StartComposition)
// ============================================================================
Composition::Composition(KorJpnIme *pIme) : _pIme(pIme) {}
Composition::~Composition() { _ClearComposition(); }

STDMETHODIMP Composition::QueryInterface(REFIID riid, void **ppv) {
    if (riid == IID_IUnknown || riid == IID_ITfCompositionSink) {
        *ppv = static_cast<ITfCompositionSink*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}
STDMETHODIMP_(ULONG) Composition::AddRef()  { return InterlockedIncrement(&_cRef); }
STDMETHODIMP_(ULONG) Composition::Release() {
    LONG c = InterlockedDecrement(&_cRef);
    // Composition is owned by KorJpnIme via plain `delete`, so don't self-delete.
    return c;
}

STDMETHODIMP Composition::OnCompositionTerminated(TfEditCookie /*ecWrite*/,
                                                   ITfComposition * /*pComposition*/) {
    DBG("Composition::OnCompositionTerminated");
    _ClearComposition();
    return S_OK;
}

void Composition::_SetComposition(ITfComposition *pComp) {
    if (_pComposition) _pComposition->Release();
    _pComposition = pComp;
}
void Composition::_ClearComposition() {
    if (_pComposition) {
        _pComposition->Release();
        _pComposition = nullptr;
    }
}

void Composition::_UpdateCaretRect(TfEditCookie ec,
                                    ITfContext *pCtx,
                                    ITfComposition *pComp) {
    if (!pCtx || !pComp || !_pIme) return;

    ITfContextView *pView = nullptr;
    if (FAILED(pCtx->GetActiveView(&pView)) || !pView) return;

    ITfRange *pRange = nullptr;
    if (SUCCEEDED(pComp->GetRange(&pRange)) && pRange) {
        RECT rc = {};
        BOOL fClipped = FALSE;
        HRESULT hr = pView->GetTextExt(ec, pRange, &rc, &fClipped);
        if (SUCCEEDED(hr) && (rc.left | rc.top | rc.right | rc.bottom) != 0) {
            _pIme->SetCaretRect(rc);
        }
        pRange->Release();
    }
    pView->Release();
}

HRESULT Composition::UpdatePreedit(ITfContext *pCtx, const std::wstring& text) {
    DBGF("Composition::UpdatePreedit len=%zu _pComp=%p", text.size(), (void*)_pComposition);
    if (text.empty()) {
        if (_pComposition) return _SetText(pCtx, L"", /*terminate=*/true);
        return S_OK;
    }
    if (!_pComposition) {
        return _StartAndSetText(pCtx, text, /*terminateAfter=*/false);
    }
    return _SetText(pCtx, text, /*terminate=*/false);
}

HRESULT Composition::Commit(ITfContext *pCtx, const std::wstring& text) {
    DBGF("Composition::Commit len=%zu _pComp=%p", text.size(), (void*)_pComposition);
    if (!_pComposition) {
        if (text.empty()) return S_OK;
        return _StartAndSetText(pCtx, text, /*terminateAfter=*/true);
    }
    return _SetText(pCtx, text, /*terminate=*/true);
}

HRESULT Composition::_StartAndSetText(ITfContext *pCtx, const std::wstring& text, bool terminateAfter) {
    auto *pSession = new (std::nothrow)
        StartAndSetTextSession(this, pCtx, text, terminateAfter);
    if (!pSession) return E_OUTOFMEMORY;

    HRESULT hrSession = S_OK;
    HRESULT hr = pCtx->RequestEditSession(_pIme->GetClientId(), pSession,
                                           TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &hrSession);
    pSession->Release();
    DBGF("  _StartAndSetText hr=0x%08lX hrSession=0x%08lX", (long)hr, (long)hrSession);
    if (FAILED(hr)) return hr;
    return hrSession;
}

HRESULT Composition::_SetText(ITfContext *pCtx, const std::wstring& text, bool terminate) {
    auto *pSession = new (std::nothrow) SetTextSession(this, _pComposition, text, terminate);
    if (!pSession) return E_OUTOFMEMORY;
    pSession->_pCtx = pCtx;          // SetTextSession needs pCtx for SetSelection on terminate
    pSession->_pCtx->AddRef();

    HRESULT hrSession = S_OK;
    HRESULT hr = pCtx->RequestEditSession(_pIme->GetClientId(), pSession,
                                           TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &hrSession);
    pSession->Release();
    DBGF("  _SetText hr=0x%08lX hrSession=0x%08lX", (long)hr, (long)hrSession);
    if (FAILED(hr)) return hr;
    return hrSession;
}
