#include "UserDict.h"
#include "DebugLog.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>

namespace {

std::string Utf16ToUtf8(const std::wstring& ws) {
    if (ws.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, ws.data(), (int)ws.size(),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, ws.data(), (int)ws.size(),
                        s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring Utf8ToUtf16(const char *p, size_t len) {
    if (len == 0) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, p, (int)len, nullptr, 0);
    if (n <= 0) return {};
    std::wstring w((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, p, (int)len, w.data(), n);
    return w;
}

} // namespace

bool UserDict::Load(const wchar_t *utf16Path) {
    _path = utf16Path ? utf16Path : L"";
    _data.clear();
    _loaded = false;
    _dirty  = false;

    HANDLE h = CreateFileW(utf16Path, GENERIC_READ, FILE_SHARE_READ, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        // Missing is fine — empty user dict
        DBGF("UserDict::Load missing %ls (starting empty)", utf16Path);
        _loaded = true;
        return true;
    }
    LARGE_INTEGER sz;
    GetFileSizeEx(h, &sz);
    if (sz.QuadPart == 0) { CloseHandle(h); _loaded = true; return true; }

    std::string buf((size_t)sz.QuadPart, '\0');
    DWORD read = 0;
    ReadFile(h, buf.data(), (DWORD)buf.size(), &read, nullptr);
    CloseHandle(h);

    // Parse: kana<TAB>kanji<TAB>count<LF>
    const char *p = buf.data();
    const char *end = p + read;
    int parsed = 0;
    while (p < end) {
        const char *lineStart = p;
        while (p < end && *p != '\n') ++p;
        const char *lineEnd = p;
        if (p < end) ++p;

        if (lineStart == lineEnd || *lineStart == '#') continue;

        const char *t1 = (const char*)memchr(lineStart, '\t', lineEnd - lineStart);
        if (!t1) continue;
        const char *t2 = (const char*)memchr(t1 + 1, '\t', lineEnd - (t1 + 1));
        if (!t2) continue;

        std::wstring kana  = Utf8ToUtf16(lineStart, t1 - lineStart);
        std::wstring kanji = Utf8ToUtf16(t1 + 1, t2 - (t1 + 1));
        // count
        std::string countStr(t2 + 1, lineEnd - (t2 + 1));
        int count = std::atoi(countStr.c_str());
        if (count <= 0) count = 1;

        if (!kana.empty() && !kanji.empty()) {
            _data[kana][kanji] = count;
            ++parsed;
        }
    }
    DBGF("UserDict::Load parsed %d entries (%zu unique kana)", parsed, _data.size());
    _loaded = true;
    return true;
}

bool UserDict::Save() const {
    if (_path.empty()) return false;
    if (!_dirty) return true;

    HANDLE h = CreateFileW(_path.c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        DBGF("UserDict::Save CreateFileW failed err=%lu", (unsigned long)GetLastError());
        return false;
    }

    std::string out;
    out.reserve(4096);
    out += "# kor_based_jap user dict v1\n";
    for (const auto& kv : _data) {
        const std::string kanaUtf8 = Utf16ToUtf8(kv.first);
        for (const auto& kk : kv.second) {
            out += kanaUtf8;
            out += '\t';
            out += Utf16ToUtf8(kk.first);
            out += '\t';
            char buf[16];
            snprintf(buf, sizeof(buf), "%d\n", kk.second);
            out += buf;
        }
    }
    DWORD written = 0;
    WriteFile(h, out.data(), (DWORD)out.size(), &written, nullptr);
    CloseHandle(h);
    _dirty = false;
    DBGF("UserDict::Save wrote %zu bytes (%zu kana keys)", out.size(), _data.size());
    return written == out.size();
}

void UserDict::Record(const std::wstring& kana, const std::wstring& kanji) {
    if (kana.empty() || kanji.empty()) return;
    ++_data[kana][kanji];
    _dirty = true;
}

size_t UserDict::TotalEntries() const {
    size_t n = 0;
    for (const auto& kv : _data) n += kv.second.size();
    return n;
}

void UserDict::Prune(int maxEntries) {
    if (maxEntries <= 0) return;                  // 0 = unlimited
    const size_t cap = static_cast<size_t>(maxEntries);
    size_t total = TotalEntries();
    if (total <= cap) return;                     // already under budget

    // Flatten all (kana, kanji, count) tuples so we can sort globally by
    // count ascending (lowest first = first to evict).  Ties broken by
    // alphabetic kana then kanji so the eviction decision is deterministic
    // across runs / across OSes.
    struct Entry {
        const std::wstring *kana;
        const std::wstring *kanji;
        int count;
    };
    std::vector<Entry> flat;
    flat.reserve(total);
    for (const auto& kv : _data) {
        for (const auto& kk : kv.second) {
            flat.push_back({ &kv.first, &kk.first, kk.second });
        }
    }
    std::sort(flat.begin(), flat.end(), [](const Entry& a, const Entry& b) {
        if (a.count != b.count) return a.count < b.count;
        if (*a.kana  != *b.kana)  return *a.kana  < *b.kana;
        return *a.kanji < *b.kanji;
    });

    const size_t dropCount = total - cap;
    DBGF("UserDict::Prune total=%zu cap=%zu evicting=%zu", total, cap, dropCount);

    for (size_t i = 0; i < dropCount; ++i) {
        auto kIt = _data.find(*flat[i].kana);
        if (kIt == _data.end()) continue;
        kIt->second.erase(*flat[i].kanji);
        if (kIt->second.empty()) _data.erase(kIt);   // kana with no kanji left
    }
    _dirty = true;
}

std::vector<std::wstring> UserDict::GetPreferred(const std::wstring& kana) const {
    std::vector<std::wstring> result;
    auto it = _data.find(kana);
    if (it == _data.end()) return result;

    // Sort by count desc, then by kanji string for stable ordering
    std::vector<std::pair<std::wstring, int>> pairs(it->second.begin(),
                                                     it->second.end());
    std::sort(pairs.begin(), pairs.end(),
        [](const auto& a, const auto& b) {
            if (a.second != b.second) return a.second > b.second;
            return a.first < b.first;
        });
    result.reserve(pairs.size());
    for (auto& p : pairs) result.push_back(std::move(p.first));
    return result;
}
