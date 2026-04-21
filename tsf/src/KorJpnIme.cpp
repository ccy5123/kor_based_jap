#include "KorJpnIme.h"
#include "KeyHandler.h"
#include "Composition.h"
#include "DisplayAttributes.h"
#include "DebugLog.h"
#include <strsafe.h>
#include <new>

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
        DBG("  -> S_OK (ITfTextInputProcessor)");
        return S_OK;
    }
    if (riid == IID_ITfDisplayAttributeProvider) {
        *ppv = static_cast<ITfDisplayAttributeProvider*>(this);
        AddRef();
        DBG("  -> S_OK (ITfDisplayAttributeProvider)");
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

// ITfDisplayAttributeProvider -------------------------------------------------
STDMETHODIMP KorJpnIme::EnumDisplayAttributeInfo(IEnumTfDisplayAttributeInfo **ppEnum) {
    if (!ppEnum) return E_INVALIDARG;
    auto *e = new (std::nothrow) ::EnumDisplayAttributeInfo();
    if (!e) return E_OUTOFMEMORY;
    *ppEnum = e;
    return S_OK;
}

STDMETHODIMP KorJpnIme::GetDisplayAttributeInfo(REFGUID guid,
                                                 ITfDisplayAttributeInfo **ppInfo) {
    if (!ppInfo) return E_INVALIDARG;
    *ppInfo = nullptr;
    if (!IsEqualGUID(guid, GUID_DISPLAYATTR_INPUT)) {
        return E_INVALIDARG;   // we only expose the one attribute
    }
    auto *info = new (std::nothrow) ::DisplayAttributeInfo();
    if (!info) return E_OUTOFMEMORY;
    *ppInfo = info;
    return S_OK;
}

// @MX:ANCHOR: Activate wires all subsystems together — the single entry point for IME lifecycle
// @MX:REASON: Called by TSF exactly once per language profile activation; order matters
STDMETHODIMP KorJpnIme::Activate(ITfThreadMgr *pThreadMgr, TfClientId tid) {
    DBGF("KorJpnIme::Activate START  pTM=%p tid=%lu", (void*)pThreadMgr, (unsigned long)tid);
    _pThreadMgr = pThreadMgr;
    _tid        = tid;

    // Wire ourselves to the candidate window so mouse clicks can call back.
    _candidateWindow.SetOwner(this);

    // Lazy-load the kana→kanji dictionary + user settings on first activation.
    if (!_dictTried) {
        _dictTried = true;
        _settings.Load();
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

    // Persist user-dictionary changes.  Cheap (file is small) and ensures
    // learning survives across IME deactivations / app close.  Prune to
    // the configured cap before saving so the file stays bounded over time
    // — entries with the lowest pick-count get evicted (LFU).
    if (_userDict.IsDirty()) {
        _userDict.Prune(_settings.UserDictMaxEntries());
        _userDict.Save();
    }

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

// ----- Conversion mode lifecycle -------------------------------------------
void KorJpnIme::EnterConversion(const std::vector<std::wstring>& candidates,
                                 const std::vector<std::wstring>& candidatePrefixes,
                                 ITfContext *pCtx) {
    if (_convCtx) { _convCtx->Release(); _convCtx = nullptr; }
    if (pCtx) { pCtx->AddRef(); _convCtx = pCtx; }
    _candidatePrefixes = candidatePrefixes;
    _inConversion = true;
    _candidateWindow.Show(candidates,
                          _hasCaretRect ? &_caretRect : nullptr);
}

void KorJpnIme::ExitConversion() {
    _inConversion = false;
    _candidateWindow.Hide();
    if (_convCtx) { _convCtx->Release(); _convCtx = nullptr; }
    _candidatePrefixes.clear();
}

std::wstring KorJpnIme::PrefixOf(int idx) const {
    if (idx < 0 || idx >= (int)_candidatePrefixes.size()) return {};
    return _candidatePrefixes[idx];
}

std::wstring KorJpnIme::SelectedPrefix() const {
    return PrefixOf(_candidateWindow.GetSelectedIndex());
}

void KorJpnIme::OnCandidateClicked(int idx) {
    if (!_convCtx) return;
    if (idx < 0 || idx >= _candidateWindow.Count()) return;
    _candidateWindow.SetSelectedIndex(idx);

    const std::wstring sel    = _candidateWindow.GetSelected();
    const std::wstring prefix = PrefixOf(idx);     // captured before ExitConversion
    ITfContext *pCtxLocal     = _convCtx;          // ExitConversion will null it

    if (!sel.empty()) {
        CommitText(pCtxLocal, sel);
        if (sel != prefix && _settings.UserDictLearn()) {
            _userDict.Record(prefix, sel);
        }
    }

    // Drop only this candidate's prefix from pending; the rest stays as preedit
    // so the user can keep converting one segment at a time.
    if (_pendingKana.size() >= prefix.size()
     && _pendingKana.compare(0, prefix.size(), prefix) == 0) {
        _pendingKana.erase(0, prefix.size());
    } else {
        _pendingKana.clear();
    }
    ExitConversion();

    if (_pendingKana.empty()) {
        UpdatePreedit(pCtxLocal, L"");
    } else {
        UpdatePreedit(pCtxLocal, _pendingKana);
    }
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

    // User dictionary: same directory, user_dict.txt.  Missing file is OK
    // (just means a brand-new user with no learning yet).
    wchar_t userPath[MAX_PATH] = {};
    StringCchCopyW(userPath, MAX_PATH, dllPath);
    StringCchCatW (userPath, MAX_PATH, L"user_dict.txt");
    _userDict.Load(userPath);
    DBGF("KorJpnIme::_LoadDictionary user_dict %zu kana keys", _userDict.KeyCount());

    // Viterbi engine inputs (rich dictionary with lid/rid/cost + bigram
    // connection-cost matrix).  Both are optional -- if either fails to
    // load, TryStartConversion silently falls back to the legacy
    // longest-prefix path over Dictionary.
    wchar_t kjDictPath[MAX_PATH] = {};
    StringCchCopyW(kjDictPath, MAX_PATH, dllPath);
    StringCchCatW (kjDictPath, MAX_PATH, L"kj_dict.bin");
    if (_richDict.Load(kjDictPath)) {
        DBGF("KorJpnIme::_LoadDictionary kj_dict.bin OK kana=%zu entries=%zu",
             _richDict.KeyCount(), _richDict.EntryCount());
    } else {
        DBG("KorJpnIme::_LoadDictionary kj_dict.bin not loaded "
            "(viterbi engine disabled, falling back to legacy lookup)");
    }

    wchar_t kjConnPath[MAX_PATH] = {};
    StringCchCopyW(kjConnPath, MAX_PATH, dllPath);
    StringCchCatW (kjConnPath, MAX_PATH, L"kj_conn.bin");
    if (_connector.Load(kjConnPath)) {
        DBGF("KorJpnIme::_LoadDictionary kj_conn.bin OK dim=%u",
             (unsigned)_connector.Dim());
    } else {
        DBG("KorJpnIme::_LoadDictionary kj_conn.bin not loaded");
    }
}
