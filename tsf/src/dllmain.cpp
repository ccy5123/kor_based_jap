// INITGUID must be defined before any GUID headers in exactly ONE translation unit.
// This causes DEFINE_GUID to emit actual storage; all other TUs get extern declarations.
#define INITGUID
#include "Globals.h"
#include "KorJpnIme.h"
#include "DebugLog.h"
#include <strsafe.h>
#include <string>

// ----- Module globals ------------------------------------------------------
HINSTANCE g_hModule = nullptr;
LONG      g_cDllRef = 0;

// ----- IClassFactory -------------------------------------------------------
class ClassFactory : public IClassFactory {
public:
    ClassFactory() : _cRef(1) { DllAddRef(); }

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void **ppv) override {
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef()  override { return InterlockedIncrement(&_cRef); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG c = InterlockedDecrement(&_cRef);
        if (!c) { DllRelease(); delete this; }
        return c;
    }

    // IClassFactory
    STDMETHODIMP CreateInstance(IUnknown *pOuter, REFIID riid, void **ppv) override {
        DBG_GUID("ClassFactory::CreateInstance IID=", riid);
        if (pOuter) return CLASS_E_NOAGGREGATION;
        KorJpnIme *p = new (std::nothrow) KorJpnIme();
        if (!p) return E_OUTOFMEMORY;
        HRESULT hr = p->QueryInterface(riid, ppv);
        p->Release();
        DBGF("  -> KorJpnIme created, QI hr=0x%08lX", (long)hr);
        return hr;
    }
    STDMETHODIMP LockServer(BOOL /*lock*/) override { return S_OK; }

private:
    LONG _cRef;
};

// ----- DLL entry -----------------------------------------------------------
BOOL WINAPI DllMain(HINSTANCE hInst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_hModule = hInst;
        DisableThreadLibraryCalls(hInst);
        char exe[MAX_PATH] = {};
        GetModuleFileNameA(nullptr, exe, MAX_PATH);
        DBGF("DllMain ATTACH  host=%s", exe);
    } else if (reason == DLL_PROCESS_DETACH) {
        DBG("DllMain DETACH");
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void **ppv) {
    DBG_GUID("DllGetClassObject CLSID=", rclsid);
    DBG_GUID("DllGetClassObject IID  =", riid);
    if (rclsid != CLSID_KorJpnIme) {
        DBG("  -> CLASS_E_CLASSNOTAVAILABLE");
        return CLASS_E_CLASSNOTAVAILABLE;
    }
    ClassFactory *pCF = new (std::nothrow) ClassFactory();
    if (!pCF) return E_OUTOFMEMORY;
    HRESULT hr = pCF->QueryInterface(riid, ppv);
    pCF->Release();
    DBGF("  -> ClassFactory hr=0x%08lX", (long)hr);
    return hr;
}

STDAPI DllCanUnloadNow() {
    return g_cDllRef == 0 ? S_OK : S_FALSE;
}

// ----- Registry helpers ----------------------------------------------------
static HRESULT SetRegValue(HKEY hRoot, const wchar_t *path,
                           const wchar_t *name, const wchar_t *val) {
    HKEY hKey;
    LSTATUS ls = RegCreateKeyExW(hRoot, path, 0, nullptr, 0,
                                  KEY_WRITE, nullptr, &hKey, nullptr);
    if (ls != ERROR_SUCCESS) return HRESULT_FROM_WIN32(ls);
    ls = RegSetValueExW(hKey, name, 0, REG_SZ,
                        reinterpret_cast<const BYTE*>(val),
                        static_cast<DWORD>((wcslen(val) + 1) * sizeof(wchar_t)));
    RegCloseKey(hKey);
    return HRESULT_FROM_WIN32(ls);
}

static void GuidToString(REFGUID guid, wchar_t *buf, size_t cch) {
    StringCchPrintfW(buf, cch,
        L"{%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}",
        guid.Data1, guid.Data2, guid.Data3,
        guid.Data4[0], guid.Data4[1], guid.Data4[2], guid.Data4[3],
        guid.Data4[4], guid.Data4[5], guid.Data4[6], guid.Data4[7]);
}

STDAPI DllRegisterServer() {
    // Requires elevation: writing to HKLM and TSF profile APIs both need admin rights.

    wchar_t dllPath[MAX_PATH] = {};
    if (!GetModuleFileNameW(g_hModule, dllPath, MAX_PATH))
        return HRESULT_FROM_WIN32(GetLastError());

    wchar_t clsid[64], profile[64];
    GuidToString(CLSID_KorJpnIme, clsid, 64);
    GuidToString(GUID_LangProfile, profile, 64);

    // COM CLSID registration under HKLM
    wchar_t keyPath[256];
    StringCchPrintfW(keyPath, 256, L"SOFTWARE\\Classes\\CLSID\\%s", clsid);
    HRESULT hr = SetRegValue(HKEY_LOCAL_MACHINE, keyPath, nullptr, kIMEName);
    if (FAILED(hr)) return hr;  // likely ERROR_ACCESS_DENIED — run as Administrator

    wchar_t inprocPath[256];
    StringCchPrintfW(inprocPath, 256,
                     L"SOFTWARE\\Classes\\CLSID\\%s\\InProcServer32", clsid);
    hr = SetRegValue(HKEY_LOCAL_MACHINE, inprocPath, nullptr, dllPath);
    if (FAILED(hr)) return hr;
    hr = SetRegValue(HKEY_LOCAL_MACHINE, inprocPath, L"ThreadingModel", L"Apartment");
    if (FAILED(hr)) return hr;

    // IMPORTANT: We deliberately do NOT call any TSF API (Register / RegisterProfile /
    // AddLanguageProfile) here.  Calling them sends a runtime notification to ctfmon
    // which IMMEDIATELY injects this DLL into every TSF-using process and makes us
    // the active TIP — even with bEnabledByDefault=FALSE.  That breaks Korean input
    // because the standard 한국어 IME gets displaced.
    //
    // Instead, the TSF TIP registry entries are written by a separate .reg file
    // (install.reg) imported by the user.  Those entries take effect only on the
    // next ctfmon startup (= next login), at which point ctfmon respects Enable=0
    // and does NOT auto-activate us.  The user enables us via Windows Settings.
    return S_OK;
}

STDAPI DllUnregisterServer() {
    wchar_t clsid[64];
    GuidToString(CLSID_KorJpnIme, clsid, 64);

    wchar_t keyPath[256];
    StringCchPrintfW(keyPath, 256,
        L"SOFTWARE\\Classes\\CLSID\\%s\\InProcServer32", clsid);
    RegDeleteKeyW(HKEY_LOCAL_MACHINE, keyPath);
    StringCchPrintfW(keyPath, 256, L"SOFTWARE\\Classes\\CLSID\\%s", clsid);
    RegDeleteKeyW(HKEY_LOCAL_MACHINE, keyPath);

    // Mirror DllRegisterServer: only remove our COM CLSID.  The TSF TIP entries
    // are removed by a separate uninstall.reg.
    return S_OK;
}
