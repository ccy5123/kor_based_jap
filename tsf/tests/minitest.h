// Minimal header-only test framework.  Chosen over Catch2 / GoogleTest
// because our one-exe test runner has exactly zero third-party dependency
// concerns and the assertion surface we need is tiny.
//
// Usage:
//
//     #include "minitest.h"
//     MT_TEST(some_behaviour) {
//         MT_EQ(2 + 2, 4);
//         MT_EXPECT(condition);
//     }
//     // in exactly one translation unit per executable:
//     MT_MAIN()
//
// All assertions increment a per-case failure counter instead of aborting
// so one failing case doesn't mask everything else in a suite.  Exit code
// is 0 iff every registered case passed without recording a failure.

#pragma once

#include <cstdio>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace minitest {

struct Case {
    const char *name;
    void (*fn)();
};

inline std::vector<Case>& Registry() {
    static std::vector<Case> v;
    return v;
}

struct Stats {
    int cases_passed       = 0;
    int cases_failed       = 0;
    int cur_case_failures  = 0;
    const char *cur_case   = "";
};

inline Stats& GetStats() {
    static Stats s;
    return s;
}

struct Register {
    Register(const char *name, void (*fn)()) {
        Registry().push_back({name, fn});
    }
};

// Dump a wstring as \uXXXX escape sequences so the console doesn't need a
// matching codepage to show non-ASCII test values.  Used only on failure.
inline void DumpW(const std::wstring& s) {
    for (wchar_t c : s) {
        if (c >= 32 && c < 127) std::fputc((int)c, stderr);
        else std::fprintf(stderr, "\\u%04X", (unsigned)c);
    }
}

inline int RunAll() {
    auto& st = GetStats();
    for (auto& c : Registry()) {
        st.cur_case          = c.name;
        st.cur_case_failures = 0;
        c.fn();
        if (st.cur_case_failures == 0) {
            std::printf("  OK   %s\n", c.name);
            ++st.cases_passed;
        } else {
            std::printf("  FAIL %s (%d assertion%s)\n",
                        c.name, st.cur_case_failures,
                        st.cur_case_failures == 1 ? "" : "s");
            ++st.cases_failed;
        }
    }
    std::printf("\n%d passed, %d failed\n", st.cases_passed, st.cases_failed);
    return st.cases_failed == 0 ? 0 : 1;
}

} // namespace minitest

// --- Test registration ------------------------------------------------------

#define MT_TEST(name)                                                         \
    static void _mt_##name();                                                 \
    static minitest::Register _mt_reg_##name(#name, _mt_##name);              \
    static void _mt_##name()

// --- Assertions -------------------------------------------------------------

#define MT_EXPECT(cond)                                                       \
    do {                                                                      \
        if (!(cond)) {                                                        \
            std::fprintf(stderr,                                              \
                "  [%s] FAIL %s:%d  expected %s\n",                           \
                minitest::GetStats().cur_case, __FILE__, __LINE__, #cond);    \
            ++minitest::GetStats().cur_case_failures;                         \
        }                                                                     \
    } while (0)

#define MT_EQ(a, b)                                                           \
    do {                                                                      \
        auto _mt_a = (a);                                                     \
        auto _mt_b = (b);                                                     \
        if (!(_mt_a == _mt_b)) {                                              \
            std::fprintf(stderr,                                              \
                "  [%s] FAIL %s:%d  %s != %s\n",                              \
                minitest::GetStats().cur_case, __FILE__, __LINE__, #a, #b);   \
            ++minitest::GetStats().cur_case_failures;                         \
        }                                                                     \
    } while (0)

// Comparison of wstrings with on-failure hex dump of both sides.
#define MT_EQ_W(actual, expected)                                             \
    do {                                                                      \
        std::wstring _mt_a = (actual);                                        \
        std::wstring _mt_b = (expected);                                      \
        if (_mt_a != _mt_b) {                                                 \
            std::fprintf(stderr,                                              \
                "  [%s] FAIL %s:%d  %s != %s\n"                               \
                "    got : '",                                                \
                minitest::GetStats().cur_case, __FILE__, __LINE__,            \
                #actual, #expected);                                          \
            minitest::DumpW(_mt_a);                                           \
            std::fprintf(stderr, "'\n    want: '");                           \
            minitest::DumpW(_mt_b);                                           \
            std::fprintf(stderr, "'\n");                                      \
            ++minitest::GetStats().cur_case_failures;                         \
        }                                                                     \
    } while (0)

// --- main() ------------------------------------------------------------------

#define MT_MAIN()                                                             \
    int main() {                                                              \
        return minitest::RunAll();                                            \
    }
