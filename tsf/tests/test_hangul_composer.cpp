// Regression tests for the HangulComposer state machine and the Korean
// 2-beolsik VK -> jamo mapping.  These exercise the historical bug cases
// (따따, 칸지, 모찌) and the Japanese-particle marker patterns added later.

#include "../src/KeyHandler.h"
#include "minitest.h"

namespace {

// Feed a sequence of jamo through the composer and return the concatenation
// of everything that fell out PLUS whatever is still in the preedit buffer
// (flushed at the end).  Markers stay as their PUA code points; tests
// comparing against expected markers do so explicitly.
std::wstring RunThenFlush(HangulComposer& c, std::wstring_view input) {
    std::wstring out;
    for (wchar_t j : input) out += c.input(j);
    out += c.flush();
    return out;
}

// Same, but does NOT flush -- useful when inspecting mid-composition state.
std::wstring RunNoFlush(HangulComposer& c, std::wstring_view input) {
    std::wstring out;
    for (wchar_t j : input) out += c.input(j);
    return out;
}

} // namespace

// ---- VkToJamo ---------------------------------------------------------------

MT_TEST(vk_to_jamo_unshifted_basics) {
    MT_EQ(VkToJamo('K', false), L'ㅏ');
    MT_EQ(VkToJamo('D', false), L'ㅇ');
    MT_EQ(VkToJamo('H', false), L'ㅗ');
    MT_EQ(VkToJamo('R', false), L'ㄱ');
    MT_EQ(VkToJamo('W', false), L'ㅈ');
}

MT_TEST(vk_to_jamo_shifted_fortis) {
    // Shifted row produces the tense (fortis) consonants
    MT_EQ(VkToJamo('Q', true), L'ㅃ');
    MT_EQ(VkToJamo('W', true), L'ㅉ');
    MT_EQ(VkToJamo('E', true), L'ㄸ');
    MT_EQ(VkToJamo('R', true), L'ㄲ');
    MT_EQ(VkToJamo('T', true), L'ㅆ');
}

MT_TEST(vk_to_jamo_vowel_shift_extras) {
    // Only two vowels have distinct shifted variants in 2-beolsik
    MT_EQ(VkToJamo('O', true), L'ㅒ');
    MT_EQ(VkToJamo('P', true), L'ㅖ');
}

MT_TEST(vk_to_jamo_out_of_range_returns_zero) {
    MT_EQ(VkToJamo(' ', false), (wchar_t)0);
    MT_EQ(VkToJamo('1', false), (wchar_t)0);
    MT_EQ(VkToJamo(0xFF,  true), (wchar_t)0);
}

// ---- Basic syllable composition --------------------------------------------

MT_TEST(compose_single_syllable) {
    // 가 = ㄱ + ㅏ
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㄱㅏ"), L"가");
}

MT_TEST(compose_cho_jung_jong) {
    // 강 = ㄱ + ㅏ + ㅇ
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㄱㅏㅇ"), L"강");
}

MT_TEST(compose_two_syllables_via_cho_migration) {
    // 강아 input: ㄱ ㅏ ㅇ ㅏ -> 강 (emit) + 아 (preedit) -> 강아
    // The ㅇ jong migrates to the next cho when a vowel arrives.
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㄱㅏㅇㅏ"), L"가아");   // ㅇ coda migrates to cho
}

MT_TEST(compose_compound_vowel) {
    // 와 = ㅇ + ㅘ (ㅘ = ㅗ + ㅏ compound)
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅇㅗㅏ"), L"와");
}

// ---- Historical bug regressions -------------------------------------------

// 따따 once produced "たㅇ" because a second fortis consonant in CHO_JUNG
// state was being fed to the silent ㅇ branch instead of restarting.
MT_TEST(double_fortis_regression_ddadda) {
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㄸㅏㄸㅏ"), L"따따");
}

// 칸지 once produced "かい" because the compound jong ㄵ+vowel path
// fell through to silent ㅇ, dropping the ㅈ before forming 지.
MT_TEST(compound_jong_split_regression_kanji) {
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅋㅏㄴㅈㅣ"), L"칸지");
}

// 모찌 input: ㅁ ㅗ ㅉ ㅣ.  The ㅉ in CHO_JUNG state should restart
// cleanly instead of chaining through the silent-ㅇ placeholder.
MT_TEST(fortis_after_cho_jung_regression_mojji) {
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅁㅗㅉㅣ"), L"모찌");
}

// ---- Japanese-particle marker patterns ------------------------------------
// These verify the PUA sentinel characters that KeyHandler later translates
// to を / は / へ.  The composer resets after emitting the marker, so any
// trailing input starts from an EMPTY state.

MT_TEST(marker_wo_from_two_oots) {
    // ㅇ-ㅗ-ㅗ -> kWoMarker
    HangulComposer c;
    std::wstring out = RunThenFlush(c, L"ㅇㅗㅗ");
    MT_EQ((int)out.size(), 1);
    MT_EQ(out[0], HangulComposer::kWoMarker);
}

MT_TEST(marker_wa_from_oo_a_a) {
    // ㅇ-ㅘ-ㅏ (which is ㅇ-ㅗ-ㅏ-ㅏ at the keystroke level, since ㅘ is
    // synthesised as the ㅗ+ㅏ compound) -> kWaMarker
    HangulComposer c;
    std::wstring out = RunThenFlush(c, L"ㅇㅗㅏㅏ");
    MT_EQ((int)out.size(), 1);
    MT_EQ(out[0], HangulComposer::kWaMarker);
}

MT_TEST(marker_e_from_two_eees) {
    // ㅇ-ㅔ-ㅔ -> kEMarker
    HangulComposer c;
    std::wstring out = RunThenFlush(c, L"ㅇㅔㅔ");
    MT_EQ((int)out.size(), 1);
    MT_EQ(out[0], HangulComposer::kEMarker);
}

// Long-vowel forms must NOT fire a marker because the explicit ㅇ between
// vowels forces a syllable boundary (오오 is 2 syllables, not a marker).
MT_TEST(long_vowel_oo_does_not_fire_wo_marker) {
    // ㅇㅗㅇㅗ -> 오오 (no marker)
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅇㅗㅇㅗ"), L"오오");
}

MT_TEST(long_vowel_ee_does_not_fire_e_marker) {
    // ㅇㅔㅇㅔ -> 에에 (no marker)
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅇㅔㅇㅔ"), L"에에");
}

// A compound-vowel ㅘ followed by a cho-starting syllable (not ㅏ) must
// remain plain 와 + next — the wa marker is specifically gated on `ㅘ + ㅏ`.
MT_TEST(wa_compound_plus_different_vowel_no_marker) {
    // ㅇㅗㅏㅣ -> 와이 (not wa marker)
    HangulComposer c;
    MT_EQ_W(RunThenFlush(c, L"ㅇㅗㅏㅣ"), L"와이");
}

// Marker fires regardless of what came before.  A full syllable then
// ㅇ-ㅗ-ㅗ should emit that syllable followed by the marker char.
MT_TEST(marker_wo_after_prior_syllable) {
    // 카 + ㅇㅗㅗ -> 카 + wo marker
    HangulComposer c;
    std::wstring out = RunThenFlush(c, L"ㅋㅏㅇㅗㅗ");
    MT_EQ((int)out.size(), 2);
    MT_EQ(out[0], L'카');
    MT_EQ(out[1], HangulComposer::kWoMarker);
}

// ---- flush / preedit / reset ----------------------------------------------

MT_TEST(flush_cho_only_returns_standalone_jamo) {
    HangulComposer c;
    c.input(L'ㄱ');                         // CHO_ONLY
    MT_EQ_W(c.flush(), std::wstring(1, L'ㄱ'));
    MT_EXPECT(c.empty());
}

MT_TEST(flush_cho_jung_returns_syllable) {
    HangulComposer c;
    c.input(L'ㅋ');
    c.input(L'ㅏ');
    MT_EQ_W(c.flush(), std::wstring(1, L'카'));
    MT_EXPECT(c.empty());
}

MT_TEST(preedit_reflects_in_progress_syllable) {
    HangulComposer c;
    c.input(L'ㅋ');
    MT_EQ_W(c.preedit(), std::wstring(1, L'ㅋ'));
    c.input(L'ㅏ');
    MT_EQ_W(c.preedit(), std::wstring(1, L'카'));
    c.input(L'ㄴ');
    MT_EQ_W(c.preedit(), std::wstring(1, L'칸'));
}

MT_TEST(empty_composer_flush_yields_empty) {
    HangulComposer c;
    MT_EQ_W(c.flush(), std::wstring{});
    MT_EXPECT(c.empty());
}

// ---- Per-jamo undo ---------------------------------------------------------

MT_TEST(undo_drops_single_jong) {
    // 칸 -> 카 after undoing the ㄴ coda
    HangulComposer c;
    RunNoFlush(c, L"ㅋㅏㄴ");
    MT_EQ_W(c.preedit(), std::wstring(1, L'칸'));
    MT_EXPECT(c.undoLastJamo());
    MT_EQ_W(c.preedit(), std::wstring(1, L'카'));
}

MT_TEST(undo_simplifies_compound_vowel) {
    // 와 -> 오 after undoing the ㅏ from the ㅘ compound vowel
    HangulComposer c;
    RunNoFlush(c, L"ㅇㅗㅏ");
    MT_EQ_W(c.preedit(), std::wstring(1, L'와'));
    MT_EXPECT(c.undoLastJamo());
    MT_EQ_W(c.preedit(), std::wstring(1, L'오'));
}

MT_TEST(undo_simplifies_compound_jong) {
    // 앉 has compound jong ㄵ -- undoing should leave ㄴ as the coda (안).
    HangulComposer c;
    RunNoFlush(c, L"ㅇㅏㄴㅈ");
    MT_EQ_W(c.preedit(), std::wstring(1, L'앉'));
    MT_EXPECT(c.undoLastJamo());
    MT_EQ_W(c.preedit(), std::wstring(1, L'안'));
}

MT_TEST(undo_drops_vowel) {
    // 카 -> ㅋ after undoing the ㅏ
    HangulComposer c;
    RunNoFlush(c, L"ㅋㅏ");
    MT_EXPECT(c.undoLastJamo());
    MT_EQ_W(c.preedit(), std::wstring(1, L'ㅋ'));
}

MT_TEST(undo_drops_cho_empties_composer) {
    HangulComposer c;
    RunNoFlush(c, L"ㅋ");
    MT_EXPECT(c.undoLastJamo());
    MT_EXPECT(c.empty());
}

MT_TEST(undo_on_empty_returns_false) {
    HangulComposer c;
    MT_EXPECT(!c.undoLastJamo());
}

// main() lives in test_main.cpp so all test TUs share one runner.
