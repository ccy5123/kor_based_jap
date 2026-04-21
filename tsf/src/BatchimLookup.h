#pragma once
#include "mapping_table.h"
#include "batchim_rules.h"

// @MX:ANCHOR: batchim::lookup is the single point of Korean-syllable → kana conversion
// @MX:REASON: All paths that commit text (OnKeyDown, flush, hyphen) must go through here;
//             without batchim processing any syllable with a final consonant fails to convert.

namespace batchim {

// ---- Hangul syllable decomposition ----------------------------------------
// Hangul syllable block: U+AC00..U+D7A3
// Formula: codepoint = (cho*21 + jung)*28 + jong + 0xAC00
//   cho:  0-18  (초성 index)
//   jung: 0-20  (중성 index)
//   jong: 0-27  (종성 index; 0 = no coda)

struct Syllable { int cho, jung, jong; };

inline bool isHangul(wchar_t ch) noexcept {
    return ch >= 0xAC00 && ch <= 0xD7A3;
}

inline Syllable decompose(wchar_t ch) noexcept {
    int offset = static_cast<int>(ch) - 0xAC00;
    return { offset / 28 / 21, (offset / 28) % 21, offset % 28 };
}

// Jong index → compatibility jamo (U+3131..U+314E).
// Index 0 means "no coda"; returns 0 in that case.
inline wchar_t jongToJamo(int jongIdx) noexcept {
    // Unicode Hangul jongseong table (28 entries, index 0 = no coda)
    static constexpr wchar_t kJong[28] = {
        0,
        L'\u3131', L'\u3132', L'\u3133', L'\u3134', L'\u3135', L'\u3136',
        L'\u3137', L'\u3139', L'\u313A', L'\u313B', L'\u313C', L'\u313D',
        L'\u313E', L'\u313F', L'\u3140', L'\u3141', L'\u3142', L'\u3144',
        L'\u3145', L'\u3146', L'\u3147', L'\u3148', L'\u314A', L'\u314B',
        L'\u314C', L'\u314D', L'\u314E',
    };
    return (jongIdx >= 0 && jongIdx < 28) ? kJong[jongIdx] : 0;
}

// ---- Batchim suffix --------------------------------------------------------
// Returns the kana suffix for a final consonant (jong) given what follows.
//
// nextJamo: the compatibility jamo of the NEXT syllable's initial consonant,
//           or 0 when this syllable is at a word boundary (terminal / flush).
//
// Priority (highest first):
//   1. hatsuon   (ㄴ/ㅇ/ㅁ)         → ん  (always, context-independent)
//   2. sokuon_always (ㅎ)           → っ  (always)
//   3. sokuon_terminal (ㅅ/ㅆ)      → っ  (at terminal)
//   4. sokuon_universal (ㅅ/ㅆ)     → っ  (before any consonant)
//   5. sokuon_strict (ㄱ/ㅂ/ㄷ/ㅅ)  → っ  (before matching consonant family)
//   6. foreign_batchim              → specific kana (く, ぷ, る, と …)
//   7. (else)                       → ""  (silent / drop)

inline std::wstring suffix(int jongIdx, wchar_t nextJamo) {
    if (jongIdx == 0) return L"";

    wchar_t j = jongToJamo(jongIdx);
    if (j == 0) return L"";  // compound jong not yet fully handled → silent

    // 1. hatsuon → ん
    if (batchim_rules::is_hatsuon(j))
        return L"\u3093";  // ん

    // 2. sokuon_always → っ
    if (batchim_rules::is_sokuon_always(j))
        return L"\u3063";  // っ

    // 3 & 4. sokuon_terminal / sokuon_universal (ㅅ/ㅆ)
    if (batchim_rules::is_sokuon_terminal(j)) {
        // terminal: no next syllable
        if (nextJamo == 0) return L"\u3063";
        // universal: っ before any consonant (ㅇ = silent vowel onset, skip)
        if (batchim_rules::is_sokuon_universal(j) && nextJamo != L'\u3147')
            return L"\u3063";
    }

    // 5. sokuon_strict: jong + next initial matches a trigger family
    if (nextJamo != 0 && batchim_rules::is_sokuon_strict(j, nextJamo))
        return L"\u3063";

    // 6. foreign batchim
    return batchim_rules::foreign_kana(j);
    // (returns "" if not in foreign table — silent drop)
}

// ---- CHO_ALIAS -------------------------------------------------------------
// Fortis (된소리) consonants have no distinct Japanese phoneme; map them to
// the nearest row so that syllables like 짜/까/따/빠/씨 resolve correctly.
//
// Cho index table (mirrors HangulComposer::kCho):
//   0=ㄱ 1=ㄲ 2=ㄴ 3=ㄷ 4=ㄸ 5=ㄹ 6=ㅁ 7=ㅂ 8=ㅃ
//   9=ㅅ 10=ㅆ 11=ㅇ 12=ㅈ 13=ㅉ 14=ㅊ 15=ㅋ 16=ㅌ 17=ㅍ 18=ㅎ
//
// Applied only as a FALLBACK when the direct syllable lookup misses.
// Explicit entries in syllables.yaml (e.g., 쓰→つ, 삐→ぴ) always win.

inline int choAlias(int cho) noexcept {
    switch (cho) {
        case  1: return  0;  // ㄲ → ㄱ  (が row)
        case  4: return  3;  // ㄸ → ㄷ  (だ row)
        case  8: return 17;  // ㅃ → ㅍ  (ぱ row; consistent with 삐→ぴ)
        case 10: return  9;  // ㅆ → ㅅ  (さ row; 쓰→つ stays via direct hit)
        case 13: return 14;  // ㅉ → ㅊ  (ちゃ row; Python CHO_ALIAS)
        default: return cho;
    }
}

// ---- Main lookup -----------------------------------------------------------
// Converts one Hangul syllable (with or without final consonant) to kana.
//
// Lookup order:
//   1. Direct syllable_table hit
//   2. CHO_ALIAS fallback (fortis → nearest row) then retry table
//   3. Batchim suffix appended after whichever base kana is resolved
//
// nextJamo: see suffix() above.  Pass 0 at word boundaries.

inline std::wstring lookup(wchar_t ch, wchar_t nextJamo = 0) {
    if (!isHangul(ch)) {
        // Compatibility jamo letter or other non-Hangul → base mapping table
        return mapping::lookup(std::wstring(1, ch));
    }

    auto [cho, jung, jong] = decompose(ch);

    // Helper: lookup a syllable wchar_t in the table, returns "" if not found
    auto tableGet = [](wchar_t s) -> std::wstring {
        const wchar_t key[2] = {s, L'\0'};
        const wchar_t* v = mapping::lookup_raw(key);
        return v ? std::wstring(v) : std::wstring{};
    };

    // Reconstruct base syllable (jong stripped)
    wchar_t base = static_cast<wchar_t>(0xAC00 + (cho * 21 + jung) * 28);

    // 1. Direct lookup of the base syllable
    std::wstring kana = tableGet(jong == 0 ? ch : base);

    // 2. CHO_ALIAS fallback when direct lookup missed
    if (kana.empty()) {
        int aliased = choAlias(cho);
        if (aliased != cho) {
            wchar_t aliasBase = static_cast<wchar_t>(0xAC00 + (aliased * 21 + jung) * 28);
            kana = tableGet(jong == 0 ? aliasBase : aliasBase);
        }
        // Still empty → keep empty (batchim-only output is possible, e.g., lone ん)
        if (kana.empty()) kana = mapping::lookup(std::wstring(1, base)); // passthrough
    }

    // 3. Batchim suffix
    if (jong != 0) kana += suffix(jong, nextJamo);
    return kana;
}

} // namespace batchim
