#pragma once
#include <windows.h>
#include <msctf.h>

// MinGW's msctf.h omits TF_CLIENTID_NULL — define it when missing
#ifndef TF_CLIENTID_NULL
#  define TF_CLIENTID_NULL ((TfClientId)0)
#endif

// ----- GUIDs ---------------------------------------------------------------
// CLSID_KorJpnIme  {CADA0001-1234-5678-90AB-CDEF00000001}
DEFINE_GUID(CLSID_KorJpnIme,
    0xCADA0001, 0x1234, 0x5678, 0x90,0xAB,0xCD,0xEF,0x00,0x00,0x00,0x01);

// GUID_LangProfile  {CADA0002-1234-5678-90AB-CDEF00000002}
DEFINE_GUID(GUID_LangProfile,
    0xCADA0002, 0x1234, 0x5678, 0x90,0xAB,0xCD,0xEF,0x00,0x00,0x00,0x02);

// GUID_DisplayAttribute (underline on composition text)
DEFINE_GUID(GUID_DisplayAttribute,
    0xCADA0003, 0x1234, 0x5678, 0x90,0xAB,0xCD,0xEF,0x00,0x00,0x00,0x03);

// ----- Constants -----------------------------------------------------------
constexpr LANGID KOREAN_LANGID  = MAKELANGID(LANG_KOREAN, SUBLANG_DEFAULT); // 0x0412
constexpr LANGID JAPANESE_LANGID = MAKELANGID(LANG_JAPANESE, SUBLANG_DEFAULT); // 0x0411

// Registry paths
constexpr wchar_t kIMEName[]      = L"Korean-Japanese IME";
constexpr wchar_t kIMENameShort[] = L"KorJpn";

// ----- Module handle -------------------------------------------------------
extern HINSTANCE g_hModule;
extern LONG      g_cDllRef;

inline void DllAddRef()    { InterlockedIncrement(&g_cDllRef); }
inline void DllRelease()   { InterlockedDecrement(&g_cDllRef); }
