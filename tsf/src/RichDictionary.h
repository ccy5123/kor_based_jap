#pragma once
#include <windows.h>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

// ----------------------------------------------------------------------------
// RichDictionary -- mmap'd kana -> [(surface, cost, lid, rid)] lookup,
// reading the binary kj_dict.bin produced by dict/build_viterbi_data.py.
//
// Distinct from the existing Dictionary (text jpn_dict.txt) because the
// viterbi engine needs lid/rid + per-surface cost, which the legacy text
// format intentionally omits to stay small.  The two coexist during the
// transition: TryStartConversion prefers RichDictionary when loaded and
// silently falls back to Dictionary otherwise.
//
// File layout (must match build_viterbi_data.py):
//
//   Header (16 B):
//     char[4] magic    = b"KJDV"
//     uint32  version  = 1
//     uint32  num_kana
//     uint32  num_entries
//
//   KanaEntry table  (num_kana * 16 B, sorted by kana UTF-8 bytes):
//     uint32  kana_off       -- bytes into String pool
//     uint16  kana_len       -- bytes
//     uint16  pad
//     uint32  entry_start    -- index into Entry table
//     uint32  entry_count
//
//   Entry table  (num_entries * 16 B):
//     uint32  surface_off
//     uint16  surface_len
//     int16   cost
//     uint16  lid
//     uint16  rid
//     uint32  pad
//
//   String pool (UTF-8, contiguous bytes)
// ----------------------------------------------------------------------------

class RichDictionary {
public:
    // One candidate surface for a given kana key.  All members are valid for
    // the lifetime of the RichDictionary (strings point into the mmap).
    struct Entry {
        std::wstring surface;   // UTF-16 -- decoded eagerly so callers don't
                                // have to repeat the UTF-8 -> UTF-16 dance
        int16_t      cost;      // Mozc unigram cost (lower = more frequent)
        uint16_t     lid;       // POS ID for left-context bigram lookup
        uint16_t     rid;       // POS ID for right-context bigram lookup
    };

    RichDictionary() = default;
    ~RichDictionary();

    RichDictionary(const RichDictionary&)            = delete;
    RichDictionary& operator=(const RichDictionary&) = delete;

    bool Load(const wchar_t *utf16Path);
    bool IsLoaded() const { return _data != nullptr; }
    size_t KeyCount()    const { return _numKana; }
    size_t EntryCount()  const { return _numEntries; }

    // All entries that match `kanaKey` exactly.  Entries are returned in
    // ascending-cost order (already sorted at build time).  Empty when
    // not found OR the dictionary failed to load.
    std::vector<Entry> Lookup(std::wstring_view kanaKey) const;

private:
    void _Close();

#pragma pack(push, 1)
    struct OnDiskHeader {
        char     magic[4];
        uint32_t version;
        uint32_t num_kana;
        uint32_t num_entries;
    };
    struct OnDiskKanaEntry {
        uint32_t kana_off;
        uint16_t kana_len;
        uint16_t pad;
        uint32_t entry_start;
        uint32_t entry_count;
    };
    struct OnDiskEntry {
        uint32_t surface_off;
        uint16_t surface_len;
        int16_t  cost;
        uint16_t lid;
        uint16_t rid;
        uint32_t pad;
    };
#pragma pack(pop)
    static_assert(sizeof(OnDiskHeader)    == 16, "RichDict header drift");
    static_assert(sizeof(OnDiskKanaEntry) == 16, "RichDict kana entry drift");
    static_assert(sizeof(OnDiskEntry)     == 16, "RichDict entry drift");

    static std::string  Utf16ToUtf8(std::wstring_view ws);
    static std::wstring Utf8ToUtf16(const char *p, size_t len);

    HANDLE  _file  = INVALID_HANDLE_VALUE;
    HANDLE  _map   = nullptr;
    void   *_view  = nullptr;
    size_t  _size  = 0;
    const char *_data = nullptr;     // == _view as char* for offset math

    uint32_t _numKana    = 0;
    uint32_t _numEntries = 0;
    const OnDiskKanaEntry *_kanas   = nullptr;   // sorted table
    const OnDiskEntry     *_entries = nullptr;
    const char            *_pool    = nullptr;   // start of string pool
    size_t                 _poolLen = 0;
};
