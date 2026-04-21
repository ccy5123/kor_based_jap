// test_dll_load.cpp
// Standalone tester: simulates what ctfmon does to our DLL, but in a process
// we control.  Each step prints its progress to stdout AND a log file so we can
// see exactly where a crash occurs without breaking the user's keyboard.
//
// Build (from MSYS/Strawberry):
//   x86_64-w64-mingw32-g++ -std=gnu++20 -O0 -g \
//     -o test_dll_load.exe test_dll_load.cpp -lole32 -loleaut32
//
// Run:
//   test_dll_load.exe C:\KorJpnIme3\KorJpnIme.dll

#define INITGUID
#include <windows.h>
#include <initguid.h>
#include <objbase.h>
#include <msctf.h>
#include <cstdio>
#include <cstdlib>

// Match the GUIDs from Globals.h
DEFINE_GUID(CLSID_KorJpnIme,
    0xCADA0001, 0x1234, 0x5678, 0x90, 0xAB, 0xCD, 0xEF, 0x00, 0x00, 0x00, 0x01);

// MinGW msctf.h declares CLSID_TF_ThreadMgr extern but we need actual storage.
// Defining INITGUID + including initguid.h SHOULD provide it; on some MinGW
// builds it doesn't, so define it here ourselves. Value from official Windows SDK.
DEFINE_GUID(CLSID_TF_ThreadMgr_local,
    0x529a9e6b, 0x6587, 0x4f23, 0xab, 0x9e, 0x9c, 0x7d, 0x68, 0x3e, 0x3c, 0x50);
#define CLSID_TF_ThreadMgr CLSID_TF_ThreadMgr_local

static FILE *g_log = nullptr;

static void STEP(const char *msg) {
    printf("[STEP] %s\n", msg);
    fflush(stdout);
    if (g_log) { fprintf(g_log, "[STEP] %s\n", msg); fflush(g_log); }
}

static void ERR(const char *msg, HRESULT hr = S_OK) {
    printf("[ERR ] %s  hr=0x%08lX\n", msg, (long)hr);
    fflush(stdout);
    if (g_log) { fprintf(g_log, "[ERR ] %s  hr=0x%08lX\n", msg, (long)hr); fflush(g_log); }
}

typedef HRESULT (STDAPICALLTYPE *DllGetClassObject_t)(REFCLSID, REFIID, void **);

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("Usage: %s <path-to-KorJpnIme.dll>\n", argv[0]);
        return 1;
    }

    g_log = fopen("test_dll_load.log", "w");

    STEP("CoInitializeEx");
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (FAILED(hr)) { ERR("CoInitializeEx failed", hr); return 1; }

    STEP("LoadLibraryW");
    HMODULE hMod = LoadLibraryA(argv[1]);
    if (!hMod) {
        ERR("LoadLibraryA failed", HRESULT_FROM_WIN32(GetLastError()));
        return 1;
    }
    printf("       module @ %p\n", (void*)hMod);

    STEP("GetProcAddress(DllGetClassObject)");
    auto pDllGetClassObject = (DllGetClassObject_t)
        GetProcAddress(hMod, "DllGetClassObject");
    if (!pDllGetClassObject) { ERR("GetProcAddress failed"); return 1; }

    STEP("DllGetClassObject(CLSID_KorJpnIme, IID_IClassFactory)");
    IClassFactory *pCF = nullptr;
    hr = pDllGetClassObject(CLSID_KorJpnIme, IID_IClassFactory, (void**)&pCF);
    if (FAILED(hr) || !pCF) { ERR("DllGetClassObject failed", hr); return 1; }

    STEP("CreateInstance(IID_ITfTextInputProcessor)");
    ITfTextInputProcessor *pTip = nullptr;
    hr = pCF->CreateInstance(nullptr, IID_ITfTextInputProcessor, (void**)&pTip);
    pCF->Release();
    if (FAILED(hr) || !pTip) { ERR("CreateInstance failed", hr); return 1; }

    STEP("CoCreateInstance(CLSID_TF_ThreadMgr)");
    ITfThreadMgr *pThreadMgr = nullptr;
    hr = CoCreateInstance(CLSID_TF_ThreadMgr, nullptr, CLSCTX_INPROC_SERVER,
                          IID_ITfThreadMgr, (void**)&pThreadMgr);
    if (FAILED(hr)) { ERR("CoCreateInstance(ThreadMgr) failed", hr); return 1; }

    STEP("ITfThreadMgr::Activate");
    TfClientId tid = 0;
    hr = pThreadMgr->Activate(&tid);
    if (FAILED(hr)) { ERR("ThreadMgr::Activate failed", hr); return 1; }
    printf("       tid = %lu\n", (unsigned long)tid);

    STEP("ITfTextInputProcessor::Activate (this is where ctfmon crashes!)");
    hr = pTip->Activate(pThreadMgr, tid);
    if (FAILED(hr)) { ERR("TIP::Activate failed", hr); }
    else            { STEP("TIP::Activate returned S_OK"); }

    STEP("ITfTextInputProcessor::Deactivate");
    pTip->Deactivate();

    STEP("Cleanup");
    pTip->Release();
    pThreadMgr->Deactivate();
    pThreadMgr->Release();
    FreeLibrary(hMod);
    CoUninitialize();

    STEP("ALL OK — no crash");
    if (g_log) fclose(g_log);
    return 0;
}
