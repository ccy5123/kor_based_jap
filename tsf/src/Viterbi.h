#pragma once
#include "RichDictionary.h"
#include "Connector.h"
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

// ----------------------------------------------------------------------------
// Viterbi -- minimum-cost segmentation of a hiragana string into Mozc-style
// dictionary entries.
//
// Approach: classic Viterbi over a lattice.
//
//   Nodes      positions 0 .. N (one per character boundary in `kana`)
//   Edges      (start, end, surface, cost, lid, rid) for every dict entry
//              that matches the substring kana[start..end), plus a length-1
//              "unknown" fallback edge at every start position so the search
//              never gets stuck on an out-of-vocabulary character.
//   Edge cost  unigram cost from RichDictionary
//   Bigram     connector.Cost(prev_edge.rid, this_edge.lid) added when an
//              edge is joined onto a prefix path
//   BOS / EOS  virtual edges with lid = rid = 0 (POS id "BOS/EOS")
//
// We do 1-best forward DP keeping a single (cost, predecessor) pair per
// position.  Multi-best ranking is left to the caller -- it can enumerate
// alternative first-segment edges and rank them by the cheap heuristic
// "BOS->this_edge bigram + this_edge unigram", which is sufficient for the
// candidate window without needing a full top-K lattice search.
//
// Scale:  N is typically 1..30 kana characters, ~5 dict matches per (start,
// length) pair, so we expect <~5000 edges per call and a few ms of compute.
// ----------------------------------------------------------------------------
class Viterbi {
public:
    // One segment of the best path, in left-to-right order.
    struct Segment {
        size_t       kanaStart;   // offset into the input wstring (chars)
        size_t       kanaLen;     // length in chars
        std::wstring surface;     // chosen surface (kanji / kana / mixed)
        int          cost;        // edge unigram cost (already applied)
        uint16_t     lid;
        uint16_t     rid;
        bool         isUnknown;   // true = synthesized raw-kana fallback,
                                  // false = real dictionary match
    };

    struct Result {
        std::vector<Segment> segments;  // best path; empty when no path found
        int totalCost = 0;              // sum of unigram + bigram costs

        bool empty() const { return segments.empty(); }
        // Concatenated surface across all segments -- the engine's single
        // best guess for the whole input ("私の" for わたしの, etc.).
        std::wstring joinedSurface() const {
            std::wstring out;
            for (const auto& s : segments) out += s.surface;
            return out;
        }
    };

    Viterbi(const RichDictionary& dict, const Connector& conn)
        : _dict(dict), _conn(conn) {}

    // True iff both data sources are loaded and Search() can produce
    // meaningful results.  TryStartConversion uses this to decide whether
    // to engage the viterbi path or fall back to the legacy lookup.
    bool IsReady() const { return _dict.IsLoaded() && _conn.IsLoaded(); }

    // Compute the minimum-cost segmentation of `kana`.  Returns an empty
    // Result when the engine is not ready or the input is empty.
    Result Best(std::wstring_view kana) const;

private:
    const RichDictionary& _dict;
    const Connector&      _conn;
};
