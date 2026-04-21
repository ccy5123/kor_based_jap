#include "RichDictionary.h"
#include "DebugLog.h"
#include <algorithm>
#include <cstring>

namespace {
constexpr char     kMagic[4] = { 'K', 'J', 'D', 'V' };
constexpr uint32_t kVersion  = 1;
} // namespace

RichDictionary::~RichDictionary() { _Close(); }

void RichDictionary::_Close() {
    if (_view) UnmapViewOfFile(_view);
    if (_map)  CloseHandle(_map);
    if (_file != INVALID_HANDLE_VALUE) CloseHandle(_file);
    _view = nullptr;
    _map  = nullptr;
    _file = INVALID_HANDLE_VALUE;
    _data = nullptr;
    _size = 0;
    _numKana = _numEntries = 0;
    _kanas   = nullptr;
    _entries = nullptr;
    _pool    = nullptr;
    _poolLen = 0;
}

std::string RichDictionary::Utf16ToUtf8(std::wstring_view ws) {
    if (ws.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, ws.data(), (int)ws.size(),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) return {};
    std::string out((size_t)n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, ws.data(), (int)ws.size(),
                        out.data(), n, nullptr, nullptr);
    return out;
}

std::wstring RichDictionary::Utf8ToUtf16(const char *p, size_t len) {
    if (len == 0) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, p, (int)len, nullptr, 0);
    if (n <= 0) return {};
    std::wstring out((size_t)n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, p, (int)len, out.data(), n);
    return out;
}

bool RichDictionary::Load(const wchar_t *utf16Path) {
    _Close();

    _file = CreateFileW(utf16Path, GENERIC_READ, FILE_SHARE_READ, nullptr,
                        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (_file == INVALID_HANDLE_VALUE) {
        DBGF("RichDictionary::Load CreateFileW failed err=%lu (%ls)",
             (unsigned long)GetLastError(), utf16Path);
        return false;
    }

    LARGE_INTEGER li;
    if (!GetFileSizeEx(_file, &li) ||
        li.QuadPart < (LONGLONG)sizeof(OnDiskHeader)) {
        DBG("RichDictionary::Load file too small");
        _Close();
        return false;
    }
    _size = (size_t)li.QuadPart;

    _map = CreateFileMappingW(_file, nullptr, PAGE_READONLY, 0, 0, nullptr);
    if (!_map) { _Close(); return false; }

    _view = MapViewOfFile(_map, FILE_MAP_READ, 0, 0, 0);
    if (!_view) { _Close(); return false; }
    _data = static_cast<const char *>(_view);

    const auto *hdr = reinterpret_cast<const OnDiskHeader *>(_data);
    if (std::memcmp(hdr->magic, kMagic, 4) != 0) {
        DBG("RichDictionary::Load magic mismatch -- not a kj_dict.bin file");
        _Close();
        return false;
    }
    if (hdr->version != kVersion) {
        DBGF("RichDictionary::Load version=%u (expected %u)",
             (unsigned)hdr->version, (unsigned)kVersion);
        _Close();
        return false;
    }

    _numKana    = hdr->num_kana;
    _numEntries = hdr->num_entries;

    // Compute table offsets.  Layout from build_viterbi_data.py:
    //   header | KanaEntry[num_kana] | Entry[num_entries] | StringPool
    const size_t kanaTableOff   = sizeof(OnDiskHeader);
    const size_t kanaTableSize  = (size_t)_numKana * sizeof(OnDiskKanaEntry);
    const size_t entryTableOff  = kanaTableOff + kanaTableSize;
    const size_t entryTableSize = (size_t)_numEntries * sizeof(OnDiskEntry);
    const size_t poolOff        = entryTableOff + entryTableSize;

    if (poolOff > _size) {
        DBGF("RichDictionary::Load truncated: poolOff=%zu size=%zu",
             poolOff, _size);
        _Close();
        return false;
    }

    _kanas   = reinterpret_cast<const OnDiskKanaEntry *>(_data + kanaTableOff);
    _entries = reinterpret_cast<const OnDiskEntry     *>(_data + entryTableOff);
    _pool    = _data + poolOff;
    _poolLen = _size - poolOff;

    DBGF("RichDictionary::Load OK kana=%u entries=%u pool=%zu",
         (unsigned)_numKana, (unsigned)_numEntries, _poolLen);
    return true;
}

std::vector<RichDictionary::Entry> RichDictionary::Lookup(
    std::wstring_view kanaKey) const {
    std::vector<Entry> out;
    if (!_kanas || _numKana == 0 || kanaKey.empty()) return out;

    // The kana table is sorted by UTF-8 bytes of the kana string, so we
    // first encode the query to UTF-8 once and then memcmp inside the
    // binary search.
    const std::string keyUtf8 = Utf16ToUtf8(kanaKey);
    if (keyUtf8.empty()) return out;
    const char    *kp = keyUtf8.data();
    const uint16_t kn = static_cast<uint16_t>(keyUtf8.size());

    // Bounds-checked range so a corrupt kana_off cannot send memcmp into
    // arbitrary memory; we clamp to the mmap'd pool slice.
    auto cmpKey = [&](const OnDiskKanaEntry &e) -> int {
        if ((size_t)e.kana_off + e.kana_len > _poolLen) return -1;
        const size_t cmpLen = std::min<size_t>(e.kana_len, kn);
        int c = std::memcmp(_pool + e.kana_off, kp, cmpLen);
        if (c != 0) return c;
        if (e.kana_len < kn) return -1;
        if (e.kana_len > kn) return  1;
        return 0;
    };

    // Standard lower_bound by hand (we cannot use std::lower_bound easily
    // because the comparator wants 3-way semantics for the early-exit
    // equality check below).
    uint32_t lo = 0, hi = _numKana;
    while (lo < hi) {
        uint32_t mid = lo + (hi - lo) / 2;
        int c = cmpKey(_kanas[mid]);
        if (c < 0)       lo = mid + 1;
        else if (c > 0)  hi = mid;
        else {
            // Found.  Decode each entry surface and return.
            const OnDiskKanaEntry &k = _kanas[mid];
            const uint32_t start = k.entry_start;
            const uint32_t count = k.entry_count;
            if ((size_t)start + count > _numEntries) return out;
            out.reserve(count);
            for (uint32_t i = 0; i < count; ++i) {
                const OnDiskEntry &e = _entries[start + i];
                if ((size_t)e.surface_off + e.surface_len > _poolLen) continue;
                Entry ent;
                ent.surface = Utf8ToUtf16(_pool + e.surface_off, e.surface_len);
                ent.cost    = e.cost;
                ent.lid     = e.lid;
                ent.rid     = e.rid;
                out.push_back(std::move(ent));
            }
            return out;
        }
    }
    return out;
}
