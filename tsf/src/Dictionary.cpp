#include "Dictionary.h"
#include "DebugLog.h"
#include <algorithm>
#include <cstring>

Dictionary::~Dictionary() {
    if (_data)     UnmapViewOfFile(_data);
    if (_hMapping) CloseHandle(_hMapping);
    if (_hFile != INVALID_HANDLE_VALUE) CloseHandle(_hFile);
}

bool Dictionary::Load(const wchar_t *utf16Path) {
    DBGF("Dictionary::Load %ls", utf16Path);
    _hFile = CreateFileW(utf16Path, GENERIC_READ, FILE_SHARE_READ, nullptr,
                         OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (_hFile == INVALID_HANDLE_VALUE) {
        DBGF("  CreateFileW failed err=%lu", (unsigned long)GetLastError());
        return false;
    }

    LARGE_INTEGER sz;
    if (!GetFileSizeEx(_hFile, &sz)) return false;
    _size = static_cast<size_t>(sz.QuadPart);
    if (_size == 0) return false;

    _hMapping = CreateFileMappingW(_hFile, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!_hMapping) { DBGF("  CreateFileMappingW failed err=%lu",
                           (unsigned long)GetLastError()); return false; }

    _data = static_cast<const char*>(MapViewOfFile(_hMapping, FILE_MAP_READ, 0, 0, 0));
    if (!_data) { DBGF("  MapViewOfFile failed err=%lu",
                       (unsigned long)GetLastError()); return false; }

    bool ok = BuildIndex();
    DBGF("Dictionary::Load %s  keys=%zu", ok ? "OK" : "FAIL", _index.size());
    return ok;
}

bool Dictionary::BuildIndex() {
    _index.clear();
    _index.reserve(200000);

    const char *p   = _data;
    const char *end = _data + _size;

    while (p < end) {
        const char *lineStart = p;
        // Find end of line
        while (p < end && *p != '\n') ++p;
        const char *lineEnd = p;     // points at LF or end
        if (p < end) ++p;            // skip LF

        if (lineStart == lineEnd)         continue;  // blank line
        if (*lineStart == '#')            continue;  // header / comment

        // First TAB separates kana from candidates
        const char *tab = static_cast<const char*>(
            memchr(lineStart, '\t', static_cast<size_t>(lineEnd - lineStart)));
        if (!tab) continue;  // malformed line

        Row r;
        r.kanaStart = lineStart;
        r.kanaLen   = static_cast<size_t>(tab - lineStart);
        r.line      = lineStart;
        r.lineLen   = static_cast<size_t>(lineEnd - lineStart);
        _index.push_back(r);
    }

    // The dictionary file is already sorted by kana, so we don't need to sort
    // again — just verify ordering with a sample to catch corruption.
    bool sorted = std::is_sorted(_index.begin(), _index.end(),
        [](const Row& a, const Row& b) {
            int cmp = std::memcmp(a.kanaStart, b.kanaStart,
                std::min(a.kanaLen, b.kanaLen));
            if (cmp != 0) return cmp < 0;
            return a.kanaLen < b.kanaLen;
        });
    if (!sorted) {
        DBG("  WARNING: dictionary not sorted by kana — sorting now");
        std::sort(_index.begin(), _index.end(),
            [](const Row& a, const Row& b) {
                int cmp = std::memcmp(a.kanaStart, b.kanaStart,
                    std::min(a.kanaLen, b.kanaLen));
                if (cmp != 0) return cmp < 0;
                return a.kanaLen < b.kanaLen;
            });
    }
    return !_index.empty();
}

std::vector<std::wstring> Dictionary::Lookup(std::wstring_view kanaKey) const {
    if (_index.empty() || kanaKey.empty()) return {};

    std::string keyUtf8 = Utf16ToUtf8(kanaKey);
    auto cmp = [](const Row& r, const std::string& key) {
        int c = std::memcmp(r.kanaStart, key.data(),
                            std::min(r.kanaLen, key.size()));
        if (c != 0) return c < 0;
        return r.kanaLen < key.size();
    };
    auto it = std::lower_bound(_index.begin(), _index.end(), keyUtf8, cmp);
    if (it == _index.end()) return {};
    if (it->kanaLen != keyUtf8.size()) return {};
    if (std::memcmp(it->kanaStart, keyUtf8.data(), keyUtf8.size()) != 0) return {};

    // Parse the line: kana<TAB>kanji<TAB>kanji<TAB>...
    std::vector<std::wstring> out;
    const char *p   = it->line + it->kanaLen + 1;       // skip kana + first TAB
    const char *end = it->line + it->lineLen;
    while (p < end) {
        const char *next = static_cast<const char*>(
            memchr(p, '\t', static_cast<size_t>(end - p)));
        const char *fieldEnd = next ? next : end;
        out.push_back(Utf8ToUtf16(p, static_cast<size_t>(fieldEnd - p)));
        if (!next) break;
        p = next + 1;
    }
    return out;
}

// ----- UTF conversions ------------------------------------------------------
std::string Dictionary::Utf16ToUtf8(std::wstring_view ws) {
    if (ws.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, ws.data(),
                                static_cast<int>(ws.size()),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, ws.data(), static_cast<int>(ws.size()),
                        s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring Dictionary::Utf8ToUtf16(const char *p, size_t len) {
    if (len == 0) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, p, static_cast<int>(len), nullptr, 0);
    if (n <= 0) return {};
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, p, static_cast<int>(len), w.data(), n);
    return w;
}
