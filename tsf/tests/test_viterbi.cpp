// Smoke tests for Viterbi.  Full data-driven coverage needs a test fixture
// of kj_dict.bin + kj_conn.bin, which is out of scope for this initial
// test runner (would double the size of the tests directory and require a
// fixture-generation step in the build).  Once those exist, add:
//
//     - single-segment lookup: input matches exactly one dict entry
//     - two-segment best path: 私 + の for わたしの
//     - unknown fallback: input with no matches falls back to raw kana
//     - connector-cost tie-breaking: same unigram cost, different lid/rid
//                                     -> best bigram wins
//
// For now we only verify that the engine degrades cleanly when the data
// files are missing -- this matches the fallback path in TryStartConversion
// (IsReady() = false -> use legacy longest-prefix over Dictionary).

#include "../src/Viterbi.h"
#include "minitest.h"

MT_TEST(viterbi_not_ready_when_dict_empty) {
    RichDictionary dict;
    Connector      conn;
    // Neither Load()'d -- both must report IsLoaded() == false.
    MT_EXPECT(!dict.IsLoaded());
    MT_EXPECT(!conn.IsLoaded());

    Viterbi v(dict, conn);
    MT_EXPECT(!v.IsReady());
}

MT_TEST(viterbi_empty_input_returns_empty_result) {
    RichDictionary dict;
    Connector      conn;
    Viterbi v(dict, conn);

    auto r = v.Best(L"");
    MT_EXPECT(r.empty());
    MT_EQ(r.totalCost, 0);
}

MT_TEST(viterbi_not_ready_returns_empty_for_nonempty_input) {
    RichDictionary dict;
    Connector      conn;
    Viterbi v(dict, conn);

    auto r = v.Best(L"\u3042\u308a\u304c\u3068\u3046");   // ありがとう
    MT_EXPECT(r.empty());
}

// main() lives in test_main.cpp so all test TUs share one runner.
