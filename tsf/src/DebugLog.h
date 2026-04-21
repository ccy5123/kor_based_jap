// DebugLog.h — file-based crash log so we can trace where ctfmon dies.
//
// Writes to C:\KorJpnIme4\debug.log (append mode).  Every entry includes:
//   - timestamp
//   - process ID + thread ID
//   - the message
//   - flushed immediately (so a crash mid-line still leaves us a partial trail)
//
// Macro DBG(msg) takes a const char*.  DBGF is printf-style.
//
// To disable in release builds, define KORJPNIME_NO_DEBUGLOG before including.

#pragma once
#include <windows.h>
#include <stdio.h>
#include <stdarg.h>

#ifndef KORJPNIME_NO_DEBUGLOG

namespace dbg {

inline const char *log_path() { return "C:\\KorJpnIme4\\debug.log"; }

inline void write_line(const char *msg) {
    FILE *f = nullptr;
    fopen_s(&f, log_path(), "ab");
    if (!f) return;
    SYSTEMTIME st;
    GetLocalTime(&st);
    fprintf(f, "[%02d:%02d:%02d.%03d pid=%lu tid=%lu] %s\n",
            st.wHour, st.wMinute, st.wSecond, st.wMilliseconds,
            (unsigned long)GetCurrentProcessId(),
            (unsigned long)GetCurrentThreadId(),
            msg);
    fflush(f);
    fclose(f);
}

inline void writef(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    write_line(buf);
}

// Format a GUID as a string for logging
inline void guid_to_str(REFGUID g, char *out, size_t cch) {
    snprintf(out, cch,
        "{%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X}",
        (unsigned)g.Data1, (unsigned)g.Data2, (unsigned)g.Data3,
        g.Data4[0], g.Data4[1], g.Data4[2], g.Data4[3],
        g.Data4[4], g.Data4[5], g.Data4[6], g.Data4[7]);
}

} // namespace dbg

#define DBG(msg)        ::dbg::write_line(msg)
#define DBGF(...)       ::dbg::writef(__VA_ARGS__)
#define DBG_GUID(label, g) do { char _gb[64]; ::dbg::guid_to_str((g), _gb, sizeof(_gb)); ::dbg::writef("%s %s", (label), _gb); } while(0)

#else
#define DBG(msg)        ((void)0)
#define DBGF(...)       ((void)0)
#define DBG_GUID(l, g)  ((void)0)
#endif
