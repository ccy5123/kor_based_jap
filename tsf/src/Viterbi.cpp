#include "Viterbi.h"
#include "DebugLog.h"
#include <algorithm>
#include <climits>

namespace {

// "Unknown word" cost for the raw-kana fallback edge inserted whenever no
// dictionary entry covers a position.  Picked to be ABOVE the highest real
// Mozc cost (~18,318) so any actual dictionary match wins on price -- but
// not so absurd that a sentence full of unknown chars overflows the int
// running total.
constexpr int kUnknownCostPerChar = 25000;

// POS id used for both lid and rid of the unknown fallback edge.  0 is
// "BOS/EOS" in id.def, which yields the cheapest connector cost in many
// neighbour rows -- effectively letting the unknown segment slot in
// without too much grammatical penalty.
constexpr uint16_t kUnknownPos = 0;

// POS id used for the implicit BOS at position 0 and EOS at position N.
constexpr uint16_t kBosEosPos = 0;

struct Edge {
    size_t       start;       // kana offset (chars)
    size_t       end;         // kana offset (chars), exclusive
    std::wstring surface;
    int          cost;
    uint16_t     lid;
    uint16_t     rid;
    bool         isUnknown;
};

} // namespace

Viterbi::Result Viterbi::Best(std::wstring_view kana) const {
    Result result;
    if (kana.empty() || !IsReady()) return result;

    const size_t N = kana.size();

    // ---- Step 1: enumerate edges --------------------------------------
    // For each start position, look up every prefix length that produces
    // a dict match.  Plus a length-1 unknown-fallback at every position so
    // the lattice is always traversable.
    std::vector<std::vector<Edge>> edgesEndingAt(N + 1);
    for (size_t i = 0; i < N; ++i) {
        bool anyMatch = false;
        for (size_t L = 1; i + L <= N; ++L) {
            std::wstring_view sub = kana.substr(i, L);
            auto entries = _dict.Lookup(sub);
            if (entries.empty()) continue;
            anyMatch = true;
            edgesEndingAt[i + L].reserve(edgesEndingAt[i + L].size() + entries.size());
            for (const auto& e : entries) {
                edgesEndingAt[i + L].push_back(Edge{
                    i, i + L, e.surface, (int)e.cost, e.lid, e.rid, false
                });
            }
        }
        // Unknown fallback: always available, ensures the lattice is
        // connected even when the user types an OOV sequence.  Single
        // hiragana char as surface so picking this segment commits the
        // raw kana that the user typed.
        edgesEndingAt[i + 1].push_back(Edge{
            i, i + 1, std::wstring(1, kana[i]),
            kUnknownCostPerChar, kUnknownPos, kUnknownPos, true
        });
        (void)anyMatch;
    }

    // ---- Step 2: forward DP -------------------------------------------
    // 1-best per position.  bestCost[i] = min cost to reach position i
    // from BOS; bestPrev[i] points to the edge used to arrive there
    // (encoded as { end_pos, index_in_edgesEndingAt[end_pos] }).
    constexpr int INF = INT_MAX / 4;
    std::vector<int> bestCost(N + 1, INF);
    bestCost[0] = 0;
    std::vector<uint16_t> prevRid(N + 1, kBosEosPos);
    struct Backptr { int endPos = -1; int edgeIdx = -1; };
    std::vector<Backptr> back(N + 1);

    // Visit positions in order 1..N.  At each j, every edge ending at j
    // contributes a candidate cost = bestCost[edge.start] + bigram
    // (prev.rid -> edge.lid) + edge.cost.
    for (size_t j = 1; j <= N; ++j) {
        const auto& edges = edgesEndingAt[j];
        for (size_t k = 0; k < edges.size(); ++k) {
            const Edge& e = edges[k];
            if (bestCost[e.start] >= INF) continue;
            int bigram = _conn.Cost(prevRid[e.start], e.lid);
            // INT16_MAX from the connector means "no transition data" -- treat
            // as a stiff penalty rather than infinity so we still get *some*
            // answer for unusual lid/rid pairs.
            if (bigram == INT16_MAX) bigram = 30000;
            int candidate = bestCost[e.start] + bigram + e.cost;
            if (candidate < bestCost[j]) {
                bestCost[j]   = candidate;
                prevRid[j]    = e.rid;
                back[j].endPos  = (int)j;
                back[j].edgeIdx = (int)k;
            }
        }
    }

    if (bestCost[N] >= INF) {
        // Should be unreachable because the unknown fallback covers every
        // position, but bail safely just in case.
        DBG("Viterbi::Best no path reached EOS");
        return result;
    }

    // Final EOS bigram: connector.Cost(prevRid[N], BOS/EOS lid 0).  Add it to
    // the reported total cost so downstream callers can compare paths from
    // independent runs.
    int eosBigram = _conn.Cost(prevRid[N], kBosEosPos);
    if (eosBigram == INT16_MAX) eosBigram = 30000;
    result.totalCost = bestCost[N] + eosBigram;

    // ---- Step 3: backtrack --------------------------------------------
    // Walk from N back to 0, collecting the edge that produced each best
    // cost.  Reverse at the end so segments come out in left-to-right order.
    int cur = (int)N;
    while (cur > 0) {
        const Backptr bp = back[cur];
        if (bp.endPos < 0) {
            DBGF("Viterbi::Best broken backptr at pos=%d", cur);
            return Result{};   // signal failure
        }
        const Edge& e = edgesEndingAt[bp.endPos][bp.edgeIdx];
        result.segments.push_back(Segment{
            e.start, e.end - e.start, e.surface, e.cost, e.lid, e.rid, e.isUnknown
        });
        cur = (int)e.start;
    }
    std::reverse(result.segments.begin(), result.segments.end());
    return result;
}
