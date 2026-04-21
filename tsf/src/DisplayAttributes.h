#pragma once
#include "Globals.h"   // pulls <windows.h> + <msctf.h>

// ----------------------------------------------------------------------------
// Custom display attribute surfaced to TSF via ITfDisplayAttributeProvider on
// KorJpnIme.  Without this, Windows falls back to a system default underline
// for our composition range; with this, the preedit shows a distinctive
// dotted blue underline that's visually identical to MS-IME / Mozc and lets
// the user see at a glance which text is still composing vs. committed.
//
// We expose exactly one attribute today (INPUT) -- the one applied to the
// composition range while the user is typing.  A future extension might add
// CONVERTED / SELECTED variants for multi-stage preedit UIs, at which point
// the enumerator below lists all of them in one shot.
// ----------------------------------------------------------------------------

// {CADA0003-1234-5678-90AB-CDEF00000003}
//
// Chosen to match the CADA000n family already used for our CLSID (CADA0001)
// and language profile (CADA0002) so all our registry entries cluster under
// a recognisable prefix.
extern const GUID GUID_DISPLAYATTR_INPUT;

// ----------------------------------------------------------------------------
// DisplayAttributeInfo -- one instance per exposed attribute.  Hands Windows
// the concrete TF_DISPLAYATTRIBUTE (colour/line-style/etc.) whenever it
// needs to render our composition range.
// ----------------------------------------------------------------------------
class DisplayAttributeInfo : public ITfDisplayAttributeInfo {
public:
    DisplayAttributeInfo() : _cRef(1) {}
    virtual ~DisplayAttributeInfo() = default;

    // IUnknown
    STDMETHODIMP         QueryInterface(REFIID riid, void **ppv) override;
    STDMETHODIMP_(ULONG) AddRef()  override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfDisplayAttributeInfo
    STDMETHODIMP GetGUID(GUID *pGuid) override;
    STDMETHODIMP GetDescription(BSTR *pbstrDesc) override;
    STDMETHODIMP GetAttributeInfo(TF_DISPLAYATTRIBUTE *pDA) override;
    STDMETHODIMP SetAttributeInfo(const TF_DISPLAYATTRIBUTE *pDA) override;
    STDMETHODIMP Reset() override;

private:
    LONG _cRef;
};

// ----------------------------------------------------------------------------
// EnumDisplayAttributeInfo -- trivial enumerator over the single attribute
// we expose.  Windows calls this to discover all display attributes our TIP
// supports.  Must match the set returned by KorJpnIme's
// ITfDisplayAttributeProvider::GetDisplayAttributeInfo(guid, ...).
// ----------------------------------------------------------------------------
class EnumDisplayAttributeInfo : public IEnumTfDisplayAttributeInfo {
public:
    EnumDisplayAttributeInfo() : _cRef(1), _idx(0) {}
    virtual ~EnumDisplayAttributeInfo() = default;

    // IUnknown
    STDMETHODIMP         QueryInterface(REFIID riid, void **ppv) override;
    STDMETHODIMP_(ULONG) AddRef()  override;
    STDMETHODIMP_(ULONG) Release() override;

    // IEnumTfDisplayAttributeInfo
    STDMETHODIMP Clone(IEnumTfDisplayAttributeInfo **ppEnum) override;
    STDMETHODIMP Next(ULONG ulCount, ITfDisplayAttributeInfo **rgInfo,
                     ULONG *pcFetched) override;
    STDMETHODIMP Reset() override;
    STDMETHODIMP Skip(ULONG ulCount) override;

private:
    LONG  _cRef;
    ULONG _idx;   // 0 = next returns INPUT, 1 = exhausted
};
