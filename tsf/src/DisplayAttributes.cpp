#include "DisplayAttributes.h"
#include "DebugLog.h"
#include <new>           // std::nothrow
#include <oleauto.h>

// --------- GUID definition (one-and-only, linker needs a definition) --------
//
// Matches the DEFINE_GUID in the header.  Initialising with the integer
// literal form (not DEFINE_GUID) keeps us independent of <initguid.h>
// include order across translation units.
const GUID GUID_DISPLAYATTR_INPUT = {
    0xCADA0003, 0x1234, 0x5678,
    { 0x90, 0xAB, 0xCD, 0xEF, 0x00, 0x00, 0x00, 0x03 }
};

// ----------------------------------------------------------------------------
// DisplayAttributeInfo
// ----------------------------------------------------------------------------
STDMETHODIMP DisplayAttributeInfo::QueryInterface(REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_ITfDisplayAttributeInfo) {
        *ppv = static_cast<ITfDisplayAttributeInfo *>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) DisplayAttributeInfo::AddRef() {
    return InterlockedIncrement(&_cRef);
}

STDMETHODIMP_(ULONG) DisplayAttributeInfo::Release() {
    LONG c = InterlockedDecrement(&_cRef);
    if (!c) delete this;
    return c;
}

STDMETHODIMP DisplayAttributeInfo::GetGUID(GUID *pGuid) {
    if (!pGuid) return E_INVALIDARG;
    *pGuid = GUID_DISPLAYATTR_INPUT;
    return S_OK;
}

STDMETHODIMP DisplayAttributeInfo::GetDescription(BSTR *pbstrDesc) {
    if (!pbstrDesc) return E_INVALIDARG;
    *pbstrDesc = SysAllocString(L"Korean-Japanese IME — input");
    return *pbstrDesc ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP DisplayAttributeInfo::GetAttributeInfo(TF_DISPLAYATTRIBUTE *pDA) {
    if (!pDA) return E_INVALIDARG;
    // Dotted underline in Windows accent blue -- visually the same as
    // MS-IME / Mozc's composition indicator.  Text color / background are
    // left at TF_CT_NONE so the host app's usual foreground paints on top.
    TF_DISPLAYATTRIBUTE da = {};
    da.crText.type  = TF_CT_NONE;
    da.crBk.type    = TF_CT_NONE;
    da.lsStyle      = TF_LS_DOT;
    da.fBoldLine    = FALSE;
    da.crLine.type  = TF_CT_COLORREF;
    da.crLine.cr    = RGB(0, 120, 212);   // Windows accent blue
    da.bAttr        = TF_ATTR_INPUT;
    *pDA = da;
    return S_OK;
}

STDMETHODIMP DisplayAttributeInfo::SetAttributeInfo(const TF_DISPLAYATTRIBUTE * /*pDA*/) {
    // TSF occasionally lets users tweak attribute appearance via Text Services
    // control panel.  We don't persist runtime tweaks -- the host always sees
    // the hard-coded defaults from GetAttributeInfo.  S_OK so the framework
    // doesn't log warnings; real persistence is a follow-up if ever requested.
    return S_OK;
}

STDMETHODIMP DisplayAttributeInfo::Reset() {
    // No runtime state to reset (see SetAttributeInfo).
    return S_OK;
}

// ----------------------------------------------------------------------------
// EnumDisplayAttributeInfo
// ----------------------------------------------------------------------------
STDMETHODIMP EnumDisplayAttributeInfo::QueryInterface(REFIID riid, void **ppv) {
    if (!ppv) return E_POINTER;
    if (riid == IID_IUnknown || riid == IID_IEnumTfDisplayAttributeInfo) {
        *ppv = static_cast<IEnumTfDisplayAttributeInfo *>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) EnumDisplayAttributeInfo::AddRef() {
    return InterlockedIncrement(&_cRef);
}

STDMETHODIMP_(ULONG) EnumDisplayAttributeInfo::Release() {
    LONG c = InterlockedDecrement(&_cRef);
    if (!c) delete this;
    return c;
}

STDMETHODIMP EnumDisplayAttributeInfo::Clone(IEnumTfDisplayAttributeInfo **ppEnum) {
    if (!ppEnum) return E_INVALIDARG;
    auto *clone = new (std::nothrow) EnumDisplayAttributeInfo();
    if (!clone) return E_OUTOFMEMORY;
    clone->_idx = _idx;
    *ppEnum = clone;
    return S_OK;
}

STDMETHODIMP EnumDisplayAttributeInfo::Next(ULONG ulCount,
                                            ITfDisplayAttributeInfo **rgInfo,
                                            ULONG *pcFetched) {
    if (!rgInfo) return E_INVALIDARG;
    ULONG fetched = 0;
    // We expose exactly one attribute, so at most one is ever fetched per
    // call (subsequent calls return 0 + S_FALSE which is the standard
    // "enumeration exhausted" signal).
    while (fetched < ulCount && _idx < 1) {
        auto *info = new (std::nothrow) DisplayAttributeInfo();
        if (!info) {
            // Release any partial fetch so the caller doesn't see a hole.
            for (ULONG i = 0; i < fetched; ++i) rgInfo[i]->Release();
            return E_OUTOFMEMORY;
        }
        rgInfo[fetched] = info;
        ++fetched;
        ++_idx;
    }
    if (pcFetched) *pcFetched = fetched;
    return (fetched == ulCount) ? S_OK : S_FALSE;
}

STDMETHODIMP EnumDisplayAttributeInfo::Reset() {
    _idx = 0;
    return S_OK;
}

STDMETHODIMP EnumDisplayAttributeInfo::Skip(ULONG ulCount) {
    _idx += ulCount;
    if (_idx > 1) _idx = 1;
    return S_OK;
}
