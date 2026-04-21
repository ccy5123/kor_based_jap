#pragma once
#include <windows.h>
#include <string>
#include <vector>
#include <map>

// ----------------------------------------------------------------------------
// UserDict — per-user learning dictionary that remembers which kanji the user
// selected for each kana reading.  Loaded from / saved to a small UTF-8 text
// file next to KorJpnIme.dll.
//
// File format (sorted by kana, then kanji):
//     kana<TAB>kanji<TAB>count<LF>
//
// Lookups append the user's preferred candidates (highest count first) to the
// front of the system dictionary results, so frequent words bubble up.
// ----------------------------------------------------------------------------
class UserDict {
public:
    UserDict() = default;

    // Path is the absolute UTF-16 path to user_dict.txt.  Loads existing
    // entries if the file is present; missing file is treated as empty.
    bool Load(const wchar_t *utf16Path);

    // Persist current state back to the file path remembered by Load().
    // Returns true on success.  Safe to call when nothing changed (no-op).
    bool Save() const;

    // Record that the user picked `kanji` for `kana`.  Increments the count
    // and marks dirty for next Save().
    void Record(const std::wstring& kana, const std::wstring& kanji);

    // Returns kanji candidates the user has previously chosen for this kana,
    // sorted by descending count (most frequent first).  Empty vector if none.
    std::vector<std::wstring> GetPreferred(const std::wstring& kana) const;

    bool IsLoaded() const { return _loaded; }
    bool IsDirty()  const { return _dirty; }
    size_t KeyCount() const { return _data.size(); }

private:
    // kana -> { kanji -> count }
    std::map<std::wstring, std::map<std::wstring, int>> _data;
    std::wstring _path;
    bool         _loaded = false;
    mutable bool _dirty  = false;
};
