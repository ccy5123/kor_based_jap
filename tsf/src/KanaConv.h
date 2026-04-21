#pragma once
#include <string>

// ----------------------------------------------------------------------------
// Hiragana <-> Katakana conversion helpers shared between KeyHandler (which
// generates kana from Korean syllables) and KorJpnIme (which optionally
// upper-cases the pending buffer when the user is in Katakana mode).
// ----------------------------------------------------------------------------
namespace kana {

// U+3041..U+3096 (hiragana letters) -> U+30A1..U+30F6 (katakana letters)
// plus the few specials we use:  ん -> ン,  っ -> ッ.
inline wchar_t toKatakana(wchar_t h) noexcept {
    if (h >= 0x3041 && h <= 0x3096) return static_cast<wchar_t>(h + 0x60);
    if (h == 0x3093) return 0x30F3;     // ん -> ン
    if (h == 0x3063) return 0x30C3;     // っ -> ッ
    return h;                            // already katakana, ー, or punctuation
}

inline std::wstring toKatakanaStr(const std::wstring& s) {
    std::wstring out;
    out.reserve(s.size());
    for (wchar_t c : s) out += toKatakana(c);
    return out;
}

// Reverse direction: katakana -> hiragana.
inline wchar_t toHiragana(wchar_t k) noexcept {
    if (k >= 0x30A1 && k <= 0x30F6) return static_cast<wchar_t>(k - 0x60);
    if (k == 0x30F3) return 0x3093;     // ン -> ん
    if (k == 0x30C3) return 0x3063;     // ッ -> っ
    return k;
}

inline std::wstring toHiraganaStr(const std::wstring& s) {
    std::wstring out;
    out.reserve(s.size());
    for (wchar_t c : s) out += toHiragana(c);
    return out;
}

} // namespace kana
