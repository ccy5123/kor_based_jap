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

// Hard cap on K to keep per-position memory linear.  10 is plenty for an
// IME candidate window (we already cap visible candidates at ~30 across
// all sources).  Larger K mostly returns near-duplicates of the top picks.
constexpr int kMaxTopK = 10;

// Sentinel to fill INT cells that haven't been touched.  INT_MAX/4 leaves
// room for additive cost accumulation without overflow.
constexpr int INF = INT_MAX / 4;

struct Edge {
    size_t       start;       // kana offset (chars)
    size_t       end;         // kana offset (chars), exclusive
    std::wstring surface;
    int          cost;
    uint16_t     lid;
    uint16_t     rid;
    bool         isUnknown;
};

// One slot in topK[pos].  Identifies a specific path from BOS to pos by
// pointing to the LAST edge that reached pos and the rank of the
// predecessor path within topK[edge.start].
//
// pathRid is cached so the forward DP doesn't have to walk back to recover
// it on every bigram lookup.
struct PathSlot {
    int      cost;
    int      edgeEnd;     // == pos for this slot (the edge ends here)
    int      edgeIdx;     // index into edgesEndingAt[edgeEnd]; -1 = BOS sentinel
    int      prevRank;    // rank within topK[edge.start] this path used; -1 = BOS
    uint16_t pathRid;     // rid of the LAST edge on this path (for next-bigram lookup)
};

// Insert `cand` into a top-K min-heap-by-cost vector.  Keeps `slots`
// bounded in size; drops the worst entry when full and the new candidate
// beats it.  `slots` stays sorted by ascending cost so backtracking can
// index by rank without a final sort step.
void InsertTopK(std::vector<PathSlot>& slots, const PathSlot& cand, int K) {
    if ((int)slots.size() < K) {
        // Find insertion point (sorted ascending by cost).
        auto it = std::upper_bound(
            slots.begin(), slots.end(), cand,
            [](const PathSlot& a, const PathSlot& b) { return a.cost < b.cost; });
        slots.insert(it, cand);
        return;
    }
    // Full: replace worst entry only if cand is strictly better.
    if (cand.cost >= slots.back().cost) return;
    slots.pop_back();
    auto it = std::upper_bound(
        slots.begin(), slots.end(), cand,
        [](const PathSlot& a, const PathSlot& b) { return a.cost < b.cost; });
    slots.insert(it, cand);
}

// Build the lattice (edges grouped by end position) for `kana`.  Always
// includes a length-1 unknown-fallback edge at every start position so
// the search is well-defined even for OOV input.
std::vector<std::vector<Edge>> BuildLattice(std::wstring_view kana,
                                             const RichDictionary& dict) {
    const size_t N = kana.size();
    std::vector<std::vector<Edge>> edgesEndingAt(N + 1);

    for (size_t i = 0; i < N; ++i) {
        for (size_t L = 1; i + L <= N; ++L) {
            std::wstring_view sub = kana.substr(i, L);
            auto entries = dict.Lookup(sub);
            if (entries.empty()) continue;
            edgesEndingAt[i + L].reserve(edgesEndingAt[i + L].size() + entries.size());
            for (const auto& e : entries) {
                edgesEndingAt[i + L].push_back(Edge{
                    i, i + L, e.surface, (int)e.cost, e.lid, e.rid, false
                });
            }
        }
        // Unknown fallback (single hiragana char), always available.
        edgesEndingAt[i + 1].push_back(Edge{
            i, i + 1, std::wstring(1, kana[i]),
            kUnknownCostPerChar, kUnknownPos, kUnknownPos, true
        });
    }
    return edgesEndingAt;
}

} // namespace

Viterbi::Result Viterbi::Best(std::wstring_view kana) const {
    auto results = SearchTopK(kana, 1);
    return results.empty() ? Result{} : std::move(results.front());
}

std::vector<Viterbi::Result> Viterbi::SearchTopK(std::wstring_view kana, int K) const {
    std::vector<Result> out;
    if (kana.empty() || !IsReady() || K <= 0) return out;
    if (K > kMaxTopK) K = kMaxTopK;

    const size_t N = kana.size();
    auto edgesEndingAt = BuildLattice(kana, _dict);

    // ---- Forward DP with top-K --------------------------------------
    // topK[pos] is sorted ascending by cost; topK[pos][r] is the r-th
    // best (zero-indexed) path from BOS to pos.  Position 0 has a single
    // sentinel slot representing the empty path.
    std::vector<std::vector<PathSlot>> topK(N + 1);
    topK[0].push_back(PathSlot{ 0, -1, -1, -1, kBosEosPos });

    for (size_t j = 1; j <= N; ++j) {
        const auto& edges = edgesEndingAt[j];
        for (size_t k = 0; k < edges.size(); ++k) {
            const Edge& e = edges[k];
            const auto& prevSlots = topK[e.start];
            if (prevSlots.empty()) continue;
            for (size_t r = 0; r < prevSlots.size(); ++r) {
                const PathSlot& prev = prevSlots[r];
                if (prev.cost >= INF) continue;
                int bigram = _conn.Cost(prev.pathRid, e.lid);
                if (bigram == INT16_MAX) bigram = 30000;
                int cost = prev.cost + bigram + e.cost;
                if (cost >= INF) continue;
                InsertTopK(topK[j], PathSlot{
                    cost, (int)j, (int)k, (int)r, e.rid
                }, K);
            }
        }
    }

    // ---- EOS bigram + backtrack -------------------------------------
    // Each rank at position N gets its EOS bigram added so the totalCost
    // is comparable across runs.  We then walk back via the stored
    // (edgeIdx, prevRank) chain to recover the segment list.
    for (size_t r = 0; r < topK[N].size(); ++r) {
        PathSlot& slot = topK[N][r];
        if (slot.cost >= INF) continue;
        int eosBigram = _conn.Cost(slot.pathRid, kBosEosPos);
        if (eosBigram == INT16_MAX) eosBigram = 30000;
        slot.cost += eosBigram;
    }
    // EOS bigram may have changed the order; resort.
    std::sort(topK[N].begin(), topK[N].end(),
              [](const PathSlot& a, const PathSlot& b) { return a.cost < b.cost; });

    out.reserve(topK[N].size());
    for (size_t r = 0; r < topK[N].size(); ++r) {
        if (topK[N][r].cost >= INF) break;
        Result result;
        int curPos  = (int)N;
        int curRank = (int)r;
        bool ok = true;
        while (curPos > 0) {
            if (curRank < 0 || curRank >= (int)topK[curPos].size()) {
                DBGF("Viterbi top-K backtrack broken pos=%d rank=%d", curPos, curRank);
                ok = false;
                break;
            }
            const PathSlot& slot = topK[curPos][curRank];
            if (slot.edgeIdx < 0) break;
            const Edge& e = edgesEndingAt[slot.edgeEnd][slot.edgeIdx];
            result.segments.push_back(Segment{
                e.start, e.end - e.start, e.surface, e.cost,
                e.lid, e.rid, e.isUnknown
            });
            curRank = slot.prevRank;
            curPos  = (int)e.start;
        }
        if (!ok || result.segments.empty()) continue;
        std::reverse(result.segments.begin(), result.segments.end());
        result.totalCost = topK[N][r].cost;
        out.push_back(std::move(result));
    }

    // De-duplicate by joined surface so callers don't get multiple paths
    // that produce the same visible string (different segment boundaries
    // but identical concatenation).  Keep the cheapest of each group.
    if (out.size() >= 2) {
        std::vector<Result> dedup;
        dedup.reserve(out.size());
        for (auto& r : out) {
            std::wstring joined = r.joinedSurface();
            bool seen = false;
            for (auto& d : dedup) {
                if (d.joinedSurface() == joined) { seen = true; break; }
            }
            if (!seen) dedup.push_back(std::move(r));
        }
        out = std::move(dedup);
    }

    return out;
}
