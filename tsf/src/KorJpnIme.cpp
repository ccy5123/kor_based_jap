#include "KorJpnIme.h"
#include "KeyHandler.h"
#include "Composition.h"
#include "DebugLog.h"
#include <strsafe.h>

extern HINSTANCE g_hModule;

KorJpnIme::KorJpnIme() : _cRef(1) {
    DBG("KorJpnIme::ctor");
    DllAddRef();
}

KorJpnIme::~KorJpnIme() {
    DBG("KorJpnIme::dtor");
    DllRelease();
}

// IUnknown
STDMETHODIMP KorJpnIme::QueryInterface(REFIID riid, void **ppv) {
    DBG_GUID("KorJpnIme::QI IID=", riid);
    if (riid == IID_IUnknown || riid == IID_ITfTextInputProcessor) {
        *ppv = static_cast<ITfTextInputProcessor*>(this);
        AddRef();
        DBG("  -> S_OK");
        return S_OK;
    }
    *ppv = nullptr;
    DBG("  -> E_NOINTERFACE");
    return E_NOINTERFACE;
}
STDMETHODIMP_(ULONG) KorJpnIme::AddRef()  { return InterlockedIncrement(&_cRef); }
STDMETHODIMP_(ULONG) KorJpnIme::Release() {
    LONG c = InterlockedDecrement(&_cRef);
    if (!c) delete this;
    return c;
}

// @MX:ANCHOR: Activate wires all subsystems together — the single entry point for IME lifecycle
// @MX:REASON: Called by TSF exactly once per language profile activation; order matters
STDMETHODIMP KorJpnIme::Activate(ITfThreadMgr *pThreadMgr, TfClientId tid) {
    DBGF("KorJpnIme::Activate START  pTM=%p tid=%lu", (void*)pThreadMgr, (unsigned long)tid);
    _pThreadMgr = pThreadMgr;
    _tid        = tid;

    // Lazy-load the kana→kanji dictionary on first activation.
    // (Subsequent activations in the same DLL instance reuse the loaded data.)
    if (!_dictTried) {
        _dictTried = true;
        _LoadDictionary();
    }

    DBG("  creating KeyHandler...");
    _pKeyHandler = new (std::nothrow) KeyHandler(this);
    if (!_pKeyHandler) { DBG("  E_OUTOFMEMORY"); return E_OUTOFMEMORY; }

    DBG("  calling KeyHandler::Advise...");
    HRESULT hr = _pKeyHandler->Advise(pThreadMgr, tid);
    DBGF("  Advise returned 0x%08lX", (long)hr);
    if (FAILED(hr)) {
        _pKeyHandler->Release();
        _pKeyHandler = nullptr;
        return hr;
    }

    DBG("  creating Composition...");
    _pComposition = new (std::nothrow) Composition(this);
    if (!_pComposition) {
        _pKeyHandler->Unadvise(pThreadMgr);
        _pKeyHandler->Release();
        _pKeyHandler = nullptr;
        return E_OUTOFMEMORY;
    }

    DBG("KorJpnIme::Activate END  S_OK");
    return S_OK;
}

STDMETHODIMP KorJpnIme::Deactivate() {
    DBG("KorJpnIme::Deactivate START");
    // Drop any pending kana — we cannot commit it without a context, and the
    // session is going away anyway.  (TODO: commit on focus-loss before deactivate.)
    _pendingKana.clear();
    if (_inConversion) ExitConversion();

    if (_pKeyHandler) {
        _pKeyHandler->Unadvise(_pThreadMgr);
        _pKeyHandler->Release();
        _pKeyHandler = nullptr;
    }

    delete _pComposition;
    _pComposition = nullptr;

    _pThreadMgr = nullptr;
    _tid        = TF_CLIENTID_NULL;
    DBG("KorJpnIme::Deactivate END");
    return S_OK;
}

// Called by KeyHandler when a syllable is finalized
HRESULT KorJpnIme::CommitText(ITfContext *pCtx, const std::wstring& text) {
    if (!_pComposition) return E_FAIL;
    return _pComposition->Commit(pCtx, text);
}

// Called by KeyHandler to update the preedit display
HRESULT KorJpnIme::UpdatePreedit(ITfContext *pCtx, const std::wstring& text) {
    if (!_pComposition) return E_FAIL;
    return _pComposition->UpdatePreedit(pCtx, text);
}

// ----------------------------------------------------------------------------
// _LoadDictionary — locate jpn_dict.txt next to the loaded DLL and mmap it.
// The install procedure copies jpn_dict.txt alongside KorJpnIme.dll, so the
// expected layout is:
//     C:\KorJpnImeNN\KorJpnIme.dll
//     C:\KorJpnImeNN\jpn_dict.txt        <-- found here
// ----------------------------------------------------------------------------
void KorJpnIme::_LoadDictionary() {
    wchar_t dllPath[MAX_PATH] = {};
    DWORD n = GetModuleFileNameW(g_hModule, dllPath, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        DBGF("KorJpnIme::_LoadDictionary GetModuleFileNameW failed err=%lu",
             (unsigned long)GetLastError());
        return;
    }
    // Strip filename, keep trailing backslash
    for (int i = static_cast<int>(n) - 1; i >= 0; --i) {
        if (dllPath[i] == L'\\' || dllPath[i] == L'/') {
            dllPath[i + 1] = L'\0';
            break;
        }
    }
    wchar_t dictPath[MAX_PATH] = {};
    StringCchCopyW(dictPath, MAX_PATH, dllPath);
    StringCchCatW (dictPath, MAX_PATH, L"jpn_dict.txt");

    DBGF("KorJpnIme::_LoadDictionary trying %ls", dictPath);
    if (!_dict.Load(dictPath)) {
        DBG("KorJpnIme::_LoadDictionary FAILED — kanji conversion disabled");
    } else {
        DBGF("KorJpnIme::_LoadDictionary OK — %zu kana keys", _dict.KeyCount());
    }
}
