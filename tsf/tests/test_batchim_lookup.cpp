// Regression tests for the batchim:: helpers.  These cover structural
// properties (compound-jong split, sokuon/hatsuon dispatch) that don't
// depend on the exact kana table in syllables.yaml, so they stay robust
// across YAML edits.

#include "../src/BatchimLookup.h"
#include "minitest.h"

// ---- isHangul ---------------------------------------------------------------

MT_TEST(isHangul_recognises_syllable_block) {
    MT_EXPECT(batchim::isHangul(L'가'));      // U+AC00
    MT_EXPECT(batchim::isHangul(L'힣'));      // U+D7A3 (top of block)
    MT_EXPECT(batchim::isHangul(L'한'));
}

MT_TEST(isHangul_rejects_compat_jamo) {
    MT_EXPECT(!batchim::isHangul(L'ㄱ'));     // compat jamo, not syllable
    MT_EXPECT(!batchim::isHangul(L'ㅏ'));
    MT_EXPECT(!batchim::isHangul(L'A'));
    MT_EXPECT(!batchim::isHangul(L'あ'));
}

// ---- decompose --------------------------------------------------------------

MT_TEST(decompose_plain_syllable) {
    // 가 = (cho=0 ㄱ, jung=0 ㅏ, jong=0 none)
    auto [cho, jung, jong] = batchim::decompose(L'가');
    MT_EQ(cho, 0);
    MT_EQ(jung, 0);
    MT_EQ(jong, 0);
}

MT_TEST(decompose_with_jong) {
    // 각 = (cho=0 ㄱ, jung=0 ㅏ, jong=1 ㄱ)
    auto [cho, jung, jong] = batchim::decompose(L'각');
    MT_EQ(cho, 0);
    MT_EQ(jung, 0);
    MT_EQ(jong, 1);
}

MT_TEST(decompose_compound_jong) {
    // 닭 = (cho=3 ㄷ, jung=0 ㅏ, jong=9 ㄺ)
    auto [cho, jung, jong] = batchim::decompose(L'닭');
    MT_EQ(cho, 3);
    MT_EQ(jung, 0);
    MT_EQ(jong, 9);   // ㄺ compound
}

// ---- splitCompoundJong -----------------------------------------------------

MT_TEST(split_compound_jong_rak) {
    // ㄺ index 9 -> (ㄹ firstJong=8, ㄱ secondCho=0)
    auto s = batchim::splitCompoundJong(9);
    MT_EQ(s.firstJong, 8);
    MT_EQ(s.secondCho, 0);
}

MT_TEST(split_compound_jong_nj) {
    // ㄵ index 5 -> (ㄴ firstJong=4, ㅈ secondCho=12)
    auto s = batchim::splitCompoundJong(5);
    MT_EQ(s.firstJong, 4);
    MT_EQ(s.secondCho, 12);
}

MT_TEST(split_compound_jong_simple_returns_negative) {
    // Non-compound ㄴ (index 4) has no split.
    auto s = batchim::splitCompoundJong(4);
    MT_EXPECT(s.firstJong < 0);
    MT_EXPECT(s.secondCho < 0);
}

MT_TEST(simplify_compound_jong_preserves_single) {
    MT_EQ(batchim::simplifyCompoundJong(4), 4);   // ㄴ stays ㄴ
}

MT_TEST(simplify_compound_jong_folds_compound) {
    MT_EQ(batchim::simplifyCompoundJong(9), 8);   // ㄺ -> ㄹ (first part)
    MT_EQ(batchim::simplifyCompoundJong(5), 4);   // ㄵ -> ㄴ
}

// ---- suffix dispatch -------------------------------------------------------

MT_TEST(suffix_empty_for_no_jong) {
    MT_EQ_W(batchim::suffix(0, 0), std::wstring{});
}

// Hatsuon: ㄴ/ㅇ/ㅁ always collapse to ん regardless of context.
MT_TEST(suffix_hatsuon_nieun) {
    // jongIndex ㄴ = 4
    MT_EQ_W(batchim::suffix(4, 0),       std::wstring{L'\u3093'});  // ん
    MT_EQ_W(batchim::suffix(4, L'ㄱ'),   std::wstring{L'\u3093'});
}

MT_TEST(suffix_hatsuon_ieung) {
    // jongIndex ㅇ = 21
    MT_EQ_W(batchim::suffix(21, 0), std::wstring{L'\u3093'});       // ん
}

MT_TEST(suffix_hatsuon_mieum) {
    // jongIndex ㅁ = 16
    MT_EQ_W(batchim::suffix(16, 0), std::wstring{L'\u3093'});       // ん
}

// Sokuon: ㅎ always, ㅅ/ㅆ at terminal or before any non-ㅇ consonant.
MT_TEST(suffix_sokuon_always_hieut) {
    // jongIndex ㅎ = 27
    MT_EQ_W(batchim::suffix(27, 0),     std::wstring{L'\u3063'});   // っ
    MT_EQ_W(batchim::suffix(27, L'ㄱ'), std::wstring{L'\u3063'});
}

MT_TEST(suffix_sokuon_terminal_siot) {
    // jongIndex ㅅ = 19; terminal -> っ
    MT_EQ_W(batchim::suffix(19, 0), std::wstring{L'\u3063'});
}

MT_TEST(suffix_sokuon_universal_siot_before_consonant) {
    // ㅅ jong before any non-silent cho fires sokuon.
    // ㅋ is cho, NOT ㅇ -> っ.
    MT_EQ_W(batchim::suffix(19, L'ㅋ'), std::wstring{L'\u3063'});
}

MT_TEST(suffix_sokuon_siot_skips_silent_ieung) {
    // ㅅ jong followed by silent ㅇ should NOT fire sokuon (the ㅇ is a
    // vowel-onset placeholder, not a real consonant).  Depending on
    // foreign_kana(ㅅ) this returns either the ㅅ foreign kana or "" --
    // the key invariant is that it is NOT っ.
    std::wstring s = batchim::suffix(19, L'ㅇ');
    MT_EXPECT(s != std::wstring{L'\u3063'});
}

// Sokuon-strict: ㄱ jong only fires っ before ㄱ/ㅋ (k-family).
MT_TEST(suffix_sokuon_strict_kiyeok_fires_before_kk) {
    // jongIndex ㄱ = 1
    MT_EQ_W(batchim::suffix(1, L'ㄱ'), std::wstring{L'\u3063'});
    MT_EQ_W(batchim::suffix(1, L'ㅋ'), std::wstring{L'\u3063'});
}

MT_TEST(suffix_sokuon_strict_kiyeok_no_fire_before_other) {
    // ㄱ before ㅁ should not fire sokuon -- foreign_kana fallback applies.
    std::wstring s = batchim::suffix(1, L'ㅁ');
    MT_EXPECT(s != std::wstring{L'\u3063'});
}

MT_TEST(suffix_compound_jong_simplifies_to_first_part) {
    // ㄵ index 5 -> first part ㄴ (hatsuon) -> ん.
    // Tests that suffix() folds the compound before dispatching.
    MT_EQ_W(batchim::suffix(5, 0), std::wstring{L'\u3093'});        // ん
}

// ---- lookup end-to-end (non-Hangul passthrough) ----------------------------

MT_TEST(lookup_passes_through_compat_jamo) {
    // Compat-jamo path goes through mapping::lookup, which returns "" for
    // a lone jamo we never mapped explicitly.  Whatever it returns, the
    // function must not crash and must not touch the Hangul syllable path.
    (void)batchim::lookup(L'ㄱ', 0);
}

MT_TEST(lookup_plain_hangul_produces_nonempty) {
    // 카 is in syllables.yaml; the exact kana may evolve but should never
    // collapse to the empty string (would mean a silent IME for a common
    // syllable).
    std::wstring kana = batchim::lookup(L'카', 0);
    MT_EXPECT(!kana.empty());
}

MT_TEST(lookup_with_hatsuon_jong_appends_n) {
    // 칸 = 카 + ん.  The hatsuon suffix is appended to the base kana.
    std::wstring kana = batchim::lookup(L'칸', 0);
    MT_EXPECT(!kana.empty());
    MT_EQ(kana.back(), L'\u3093');   // trailing ん
}

MT_TEST(lookup_with_sokuon_jong_appends_tsu_terminal) {
    // 갓 at terminal -> ... + っ (sokuon_terminal rule for ㅅ).
    std::wstring kana = batchim::lookup(L'갓', 0);
    MT_EXPECT(!kana.empty());
    MT_EQ(kana.back(), L'\u3063');   // trailing っ
}

// main() lives in test_main.cpp so all test TUs share one runner.
