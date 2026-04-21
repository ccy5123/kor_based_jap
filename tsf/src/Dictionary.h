#pragma once
#include <windows.h>
#include <string>
#include <string_view>
#include <vector>

// ----------------------------------------------------------------------------
// Dictionary — kana→kanji lookup loaded from dict/jpn_dict.txt.
//
// The text file is sorted by kana (UTF-8) and each line is:
//     <kana><TAB><kanji_1><TAB><kanji_2><TAB>...<LF>
// (lines starting with '#' are header / comment.)
//
// Load() memory-maps the file, scans line offsets once, and stores them as
// (line_start, kana_end_on_line) pairs sorted by kana.  Lookup() does binary
// search on those offsets and returns a list of UTF-16 kanji candidates for
// the given hiragana key.
//
// All file data is held by the OS page cache, so the only RAM we own is the
// offset index (~6 MB / 32 bytes ≈ 1.5 MB for a 180K-key dictionary).
// ----------------------------------------------------------------------------
class Dictionary {
public:
    Dictionary() = default;
    ~Dictionary();

    Dictionary(const Dictionary&) = delete;
    Dictionary& operator=(const Dictionary&) = delete;

    // Returns true on success.  Path is the absolute UTF-8 path to jpn_dict.txt.
    bool Load(const wchar_t *utf16Path);

    bool IsLoaded() const { return _data != nullptr; }
    size_t KeyCount() const { return _index.size(); }

    // Look up `kanaKey` (UTF-16 hiragana).  Returns kanji candidates as
    // UTF-16 strings, in dictionary order (most frequent first).  Empty
    // vector if not found.
    std::vector<std::wstring> Lookup(std::wstring_view kanaKey) const;

private:
    struct Row {
        const char *kanaStart;   // pointer into _data
        size_t      kanaLen;     // bytes in UTF-8
        const char *line;        // start of full line in _data
        size_t      lineLen;     // bytes including trailing LF (excluded)
    };

    // Build the sorted index of rows from the mapped file.
    bool BuildIndex();
    // Convert a UTF-16 wstring to UTF-8 std::string for comparison.
    static std::string Utf16ToUtf8(std::wstring_view ws);
    // Convert a UTF-8 byte range to UTF-16 std::wstring for output.
    static std::wstring Utf8ToUtf16(const char *p, size_t len);

    HANDLE      _hFile    = INVALID_HANDLE_VALUE;
    HANDLE      _hMapping = nullptr;
    const char *_data     = nullptr;
    size_t      _size     = 0;
    std::vector<Row> _index;
};
