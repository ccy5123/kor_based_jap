// HangulComposer + VkToJamo -- pure Korean-jamo state machine, no TSF or COM
// dependencies.  Extracted out of KeyHandler.cpp so the unit test target can
// link this translation unit in isolation (KeyHandler itself pulls in
// KorJpnIme, Composition, dlls, etc. -- way too heavy for a test runner).
//
// All behaviour matches the previous in-line implementation byte-for-byte;
// see git history for the original log of design decisions, bug fixes
// (따따 / 칸지 / 모찌), and the wo / wa / e particle marker patterns.

#include "KeyHandler.h"          // HangulComposer class + VkToJamo decl
#include "BatchimLookup.h"       // splitCompoundJong for compound-coda handling
#include <string>

// ============================================================================
// VkToJamo — Korean 2-beolsik layout
// Standard layout: unshifted + shifted jamo
// ============================================================================
wchar_t VkToJamo(UINT vk, bool shifted) {
    // @MX:ANCHOR: VK→jamo mapping is the single authoritative 2-beolsik table
    // @MX:REASON: All key handling passes through here; any edit affects every keystroke
    struct Entry { UINT vk; wchar_t normal; wchar_t shifted; };
    static constexpr Entry kTable[] = {
        // Row 1 (number row) — no Korean on unshifted; shifted give special jamo
        { 'Q', L'ㅂ', L'ㅃ' },
        { 'W', L'ㅈ', L'ㅉ' },
        { 'E', L'ㄷ', L'ㄸ' },
        { 'R', L'ㄱ', L'ㄲ' },
        { 'T', L'ㅅ', L'ㅆ' },
        { 'Y', L'ㅛ', L'ㅛ' },
        { 'U', L'ㅕ', L'ㅕ' },
        { 'I', L'ㅑ', L'ㅑ' },
        { 'O', L'ㅐ', L'ㅒ' },
        { 'P', L'ㅔ', L'ㅖ' },
        { 'A', L'ㅁ', L'ㅁ' },
        { 'S', L'ㄴ', L'ㄴ' },
        { 'D', L'ㅇ', L'ㅇ' },
        { 'F', L'ㄹ', L'ㄹ' },
        { 'G', L'ㅎ', L'ㅎ' },
        { 'H', L'ㅗ', L'ㅗ' },
        { 'J', L'ㅓ', L'ㅓ' },
        { 'K', L'ㅏ', L'ㅏ' },
        { 'L', L'ㅣ', L'ㅣ' },
        { 'Z', L'ㅋ', L'ㅋ' },
        { 'X', L'ㅌ', L'ㅌ' },
        { 'C', L'ㅊ', L'ㅊ' },
        { 'V', L'ㅍ', L'ㅍ' },
        { 'B', L'ㅠ', L'ㅠ' },
        { 'N', L'ㅜ', L'ㅜ' },
        { 'M', L'ㅡ', L'ㅡ' },
    };
    for (const auto& e : kTable) {
        if (e.vk == vk)
            return shifted ? e.shifted : e.normal;
    }
    return 0; // not a Korean key
}

// ============================================================================
// HangulComposer — 3-state syllable state machine
// States: EMPTY → CHO_ONLY → CHO_JUNG → CHO_JUNG_JONG
// Unicode formula: (cho*21 + jung)*28 + jong + 0xAC00
// ============================================================================

// @MX:ANCHOR: These index tables are used by both input() and compose()
// @MX:REASON: Indices must stay in sync with Unicode Hangul syllable block offsets

// 초성 (onset) — 19 entries, offsets 0-18
static constexpr wchar_t kCho[] = {
    L'ㄱ',L'ㄲ',L'ㄴ',L'ㄷ',L'ㄸ',L'ㄹ',L'ㅁ',L'ㅂ',L'ㅃ',
    L'ㅅ',L'ㅆ',L'ㅇ',L'ㅈ',L'ㅉ',L'ㅊ',L'ㅋ',L'ㅌ',L'ㅍ',L'ㅎ'
};

// 중성 (nucleus vowel) — 21 entries
static constexpr wchar_t kJung[] = {
    L'ㅏ',L'ㅐ',L'ㅑ',L'ㅒ',L'ㅓ',L'ㅔ',L'ㅕ',L'ㅖ',L'ㅗ',
    L'ㅘ',L'ㅙ',L'ㅚ',L'ㅛ',L'ㅜ',L'ㅝ',L'ㅞ',L'ㅟ',L'ㅠ',
    L'ㅡ',L'ㅢ',L'ㅣ'
};

// 종성 (coda) — 28 entries (index 0 = no coda)
static constexpr wchar_t kJong[] = {
    0,    // 0 = no coda
    L'ㄱ',L'ㄲ',L'ㄳ',L'ㄴ',L'ㄵ',L'ㄶ',L'ㄷ',L'ㄹ',L'ㄺ',L'ㄻ',
    L'ㄼ',L'ㄽ',L'ㄾ',L'ㄿ',L'ㅀ',L'ㅁ',L'ㅂ',L'ㅄ',L'ㅅ',L'ㅆ',
    L'ㅇ',L'ㅈ',L'ㅊ',L'ㅋ',L'ㅌ',L'ㅍ',L'ㅎ'
};

// Compound jong pairs → jong index (ㄱ+ㅅ=ㄳ, ㄴ+ㅈ=ㄵ, etc.)
namespace {
struct JongPair { wchar_t j1, j2; int result; };
constexpr JongPair kJongCompound[] = {
    { L'ㄱ', L'ㅅ',  3 }, // ㄳ
    { L'ㄴ', L'ㅈ',  5 }, // ㄵ
    { L'ㄴ', L'ㅎ',  6 }, // ㄶ
    { L'ㄹ', L'ㄱ',  9 }, // ㄺ
    { L'ㄹ', L'ㅁ', 10 }, // ㄻ
    { L'ㄹ', L'ㅂ', 11 }, // ㄼ
    { L'ㄹ', L'ㅅ', 12 }, // ㄽ
    { L'ㄹ', L'ㅌ', 13 }, // ㄾ
    { L'ㄹ', L'ㅍ', 14 }, // ㄿ
    { L'ㄹ', L'ㅎ', 15 }, // ㅀ
    { L'ㅂ', L'ㅅ', 18 }, // ㅄ
};

// Compound vowel pairs → vowel (ㅗ+ㅏ=ㅘ, ㅗ+ㅐ=ㅙ, etc.)
struct VowelPair { wchar_t v1, v2; wchar_t result; };
constexpr VowelPair kVowelCompound[] = {
    { L'ㅗ', L'ㅏ', L'ㅘ' },
    { L'ㅗ', L'ㅐ', L'ㅙ' },
    { L'ㅗ', L'ㅣ', L'ㅚ' },
    { L'ㅜ', L'ㅓ', L'ㅝ' },
    { L'ㅜ', L'ㅔ', L'ㅞ' },
    { L'ㅜ', L'ㅣ', L'ㅟ' },
    { L'ㅡ', L'ㅣ', L'ㅢ' },
};
} // anonymous namespace

HangulComposer::HangulComposer() = default;

int HangulComposer::choIndex(wchar_t jamo) {
    for (int i = 0; i < CHO_COUNT; ++i)
        if (kCho[i] == jamo) return i;
    return -1;
}

int HangulComposer::jungIndex(wchar_t jamo) {
    for (int i = 0; i < JUNG_COUNT; ++i)
        if (kJung[i] == jamo) return i;
    return -1;
}

int HangulComposer::jongIndex(wchar_t jamo) {
    for (int i = 1; i < JONG_COUNT; ++i) // skip 0 (no coda)
        if (kJong[i] == jamo) return i;
    return -1;
}

// Remap jong→cho when the coda splits off to start a new syllable
// E.g., 닭+아 → 달+가, so jong ㄱ(index 1) → cho ㄱ(index 0)
int HangulComposer::choFromJong(int jongIdx) {
    wchar_t jamo = kJong[jongIdx];
    return choIndex(jamo);
}

wchar_t HangulComposer::compoundVowel(wchar_t v1, wchar_t v2) {
    for (const auto& p : kVowelCompound)
        if (p.v1 == v1 && p.v2 == v2) return p.result;
    return 0;
}

int HangulComposer::compoundJong(wchar_t j1, wchar_t j2) {
    for (const auto& p : kJongCompound)
        if (p.j1 == j1 && p.j2 == j2) return p.result;
    return 0;
}

wchar_t HangulComposer::compose() const {
    if (_cho < 0 || _jung < 0) return 0;
    int jong = (_jong < 0) ? 0 : _jong;
    return static_cast<wchar_t>((_cho * 21 + _jung) * 28 + jong + 0xAC00);
}

// @MX:NOTE: [AUTO] Main state machine — processes one jamo at a time
// Returns a completed syllable wstring when one is finalized; empty if still composing
std::wstring HangulComposer::input(wchar_t jamo) {
    // ----- State: EMPTY --------------------------------------------------
    if (_cho < 0 && _jung < 0) {
        int ci = choIndex(jamo);
        if (ci >= 0) {
            _cho = ci;
            _rawJong = jamo;
            return {};  // CHO_ONLY
        }
        // Lone vowel (no consonant onset — use ㅇ as placeholder cho index 11)
        int vi = jungIndex(jamo);
        if (vi >= 0) {
            _cho  = 11; // ㅇ silent onset
            _jung = vi;
            _rawJung = jamo;
            return {};  // CHO_JUNG (silent onset)
        }
        // Not Korean — pass through as-is
        return std::wstring(1, jamo);
    }

    // ----- State: CHO_ONLY -----------------------------------------------
    if (_cho >= 0 && _jung < 0) {
        int vi = jungIndex(jamo);
        if (vi >= 0) {
            _jung    = vi;
            _rawJung = jamo;
            return {};  // → CHO_JUNG
        }
        // Another consonant → emit current (standalone jamo letter) and restart
        std::wstring out(1, kCho[_cho]);
        reset();
        return out + input(jamo);
    }

    // ----- State: CHO_JUNG -----------------------------------------------
    if (_cho >= 0 && _jung >= 0 && _jong < 0) {
        // Try compound vowel first (ㅗ+ㅏ=ㅘ, etc.)
        wchar_t cv = compoundVowel(_rawJung, jamo);
        if (cv) {
            int vi = jungIndex(cv);
            if (vi >= 0) {
                _jung    = vi;
                _rawJung = cv;
                return {};  // still CHO_JUNG, vowel upgraded
            }
        }

        // Special patterns: doubled final-vowel jamo after a silent-ㅇ
        // onset have no native Korean meaning (the second vowel would
        // normally split into a new syllable), so the keystroke pattern
        // is repurposed for Japanese particles whose written form differs
        // from pronunciation.
        //
        //   ㅇ-ㅗ-ㅗ -> を   (object particle, normally read as "o")
        //   ㅇ-ㅘ-ㅏ -> は   (topic particle,  normally read as "wa";
        //                     ㅘ comes from the ㅗ+ㅏ compound vowel)
        //   ㅇ-ㅔ-ㅔ -> へ   (direction particle, normally read as "e")
        //
        // Real long-vowel forms (おお, ええ, etc.) require the explicit
        // ㅇ between vowels (ㅇ-ㅗ-ㅇ-ㅗ, ㅇ-ㅔ-ㅇ-ㅔ) and are unaffected.
        if (_cho == 11) {
            if (_rawJung == L'ㅗ' && jamo == L'ㅗ') {
                reset(); return std::wstring(1, kWoMarker);
            }
            if (_rawJung == L'ㅘ' && jamo == L'ㅏ') {
                reset(); return std::wstring(1, kWaMarker);
            }
            if (_rawJung == L'ㅔ' && jamo == L'ㅔ') {
                reset(); return std::wstring(1, kEMarker);
            }
        }

        // Consonant that can be jongseong → make it the jong of current syllable
        int ji = jongIndex(jamo);
        if (ji >= 0) {
            _jong    = ji;
            _rawJong = jamo;
            return {};  // → CHO_JUNG_JONG
        }

        // Anything else (vowel-not-compound, or fortis consonant ㄸ/ㅃ/ㅉ that
        // cannot be a jongseong) → emit current syllable and recurse so the new
        // input is processed against an EMPTY state (will become a new cho or
        // a stand-alone vowel correctly).
        // Bug fixed: previously this branch hard-coded `_cho = 11` (ㅇ) and then
        // tried to use `jamo` as a vowel, which produced "たㅇ" when the second
        // ㄸ in 따따 arrived.
        wchar_t syllable = compose();
        std::wstring out(1, syllable);
        reset();
        return out + input(jamo);
    }

    // ----- State: CHO_JUNG_JONG ------------------------------------------
    if (_cho >= 0 && _jung >= 0 && _jong >= 0) {
        int vi = jungIndex(jamo);

        if (vi >= 0) {
            // Vowel arriving with a jong present — the jong "migrates" to be
            // the cho of the next syllable.

            // Compound jong (ㄳ ㄵ ㄶ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅄ): keep the FIRST
            // part as the jong of the current syllable, and use the SECOND
            // part as the cho of the new syllable.  Bug fixed: previously this
            // case fell through to silent ㅇ, turning 칹+ㅣ into かい instead
            // of かんじ.
            batchim::SplitJong sj = batchim::splitCompoundJong(_jong);
            if (sj.firstJong >= 0 && sj.secondCho >= 0) {
                _jong    = sj.firstJong;
                _rawJong = kJong[sj.firstJong];
                wchar_t syllable = compose();    // 칸 (with reduced jong)
                std::wstring out(1, syllable);
                reset();
                _cho     = sj.secondCho;
                _jung    = vi;
                _rawJung = jamo;
                return out;
            }

            // Single jong: choFromJong gives the migrating cho, current
            // syllable emits without jong (e.g. 칸 → 카 + 나).
            int newCho = choFromJong(_jong);
            if (newCho < 0) {
                // Truly unknown jong (shouldn't happen for valid input) —
                // fall back to silent ㅇ as last resort.
                wchar_t syllable = compose();
                std::wstring out(1, syllable);
                reset();
                _cho     = 11;
                _jung    = vi;
                _rawJung = jamo;
                return out;
            }

            int savedJong = _jong;
            wchar_t savedRawJong = _rawJong;
            _jong    = -1;
            _rawJong = 0;
            wchar_t syllable = compose();
            std::wstring out(1, syllable);

            reset();
            _cho     = newCho;
            _jung    = vi;
            _rawJung = jamo;
            (void)savedJong; (void)savedRawJong;
            return out;
        }

        // Consonant: try to form compound jong (ㄱ+ㅅ=ㄳ)
        wchar_t cj = compoundJong(_rawJong, jamo);
        if (cj) {
            // compoundJong returns the jong INDEX encoded as wchar_t
            _jong    = static_cast<int>(cj);
            _rawJong = kJong[static_cast<int>(cj)];
            return {};  // compound jong formed
        }

        // Another consonant → emit current syllable, restart with new consonant
        wchar_t syllable = compose();
        std::wstring out(1, syllable);
        reset();
        return out + input(jamo);
    }

    return {};
}

std::wstring HangulComposer::flush() {
    if (_cho < 0) return {};
    std::wstring out;
    if (_jung >= 0) {
        out = std::wstring(1, compose());
    } else {
        // CHO_ONLY — emit the consonant as standalone jamo letter
        out = std::wstring(1, kCho[_cho]);
    }
    reset();
    return out;
}

std::wstring HangulComposer::preedit() {
    if (_cho < 0) return {};
    if (_jung < 0) return std::wstring(1, kCho[_cho]);
    return std::wstring(1, compose());
}

bool HangulComposer::empty() const {
    return _cho < 0;
}

void HangulComposer::reset() {
    _cho     = -1;
    _jung    = -1;
    _jong    = -1;
    _rawJung = 0;
    _rawJong = 0;
}

// Drop the most recently added piece of the in-progress syllable.  Order of
// undo (the reverse of typing order):
//   1. Compound jong → simplify to its first part (ㄳ→ㄱ, ㄵ→ㄴ, …)
//   2. Single jong   → remove
//   3. Compound vowel → simplify to first part (ㅘ→ㅗ, ㅝ→ㅜ, …)
//   4. Jung (vowel)  → remove
//   5. Cho (consonant) → remove (composer becomes empty)
// Returns true if anything was undone.
bool HangulComposer::undoLastJamo() {
    if (_jong >= 0) {
        // Try simplify compound jong first
        batchim::SplitJong sj = batchim::splitCompoundJong(_jong);
        if (sj.firstJong >= 0) {
            _jong    = sj.firstJong;
            _rawJong = kJong[_jong];
        } else {
            _jong    = -1;
            _rawJong = 0;
        }
        return true;
    }
    if (_jung >= 0) {
        // Try simplify compound vowel (ㅘ → ㅗ etc.) by reverse-lookup of kVowelCompound
        if (_rawJung != 0) {
            for (const auto& vp : kVowelCompound) {
                if (vp.result == _rawJung) {
                    _jung    = jungIndex(vp.v1);
                    _rawJung = vp.v1;
                    return _jung >= 0;
                }
            }
        }
        _jung    = -1;
        _rawJung = 0;
        return true;
    }
    if (_cho >= 0) {
        _cho = -1;
        return true;
    }
    return false;
}
