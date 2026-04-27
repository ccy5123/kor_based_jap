package io.github.ccy5123.korjpnime.engine

/**
 * Minimum-cost segmentation of a hiragana string into Mozc-style dictionary
 * entries.  Direct port of `tsf/src/Viterbi.cpp`.
 *
 * Approach: classic Viterbi over a lattice.
 *
 *   Nodes      positions 0..N (one per character boundary in `kana`)
 *   Edges      (start, end, surface, cost, lid, rid) for every dict
 *              entry that matches the substring kana[start..end), plus
 *              a length-1 "unknown" fallback edge at every start
 *              position so the search never gets stuck on OOV chars.
 *   Edge cost  unigram cost from [RichDictionary].
 *   Bigram     [Connector.cost(prev_edge.rid, this_edge.lid)] added when
 *              an edge is joined onto a prefix path.
 *   BOS / EOS  virtual edges with lid = rid = 0.
 *
 * Top-K forward DP keeps `K` (cost, predecessor) tuples per position so
 * we can backtrack alternative paths for the candidate strip.
 *
 * Scale: N is 1..30 kana chars in practice, ~5 dict matches per
 * (start, length), so a few thousand edges and ~ms compute per call.
 */
class Viterbi(
    private val dict: RichDictionary,
    private val conn: Connector,
) {

    val isReady: Boolean get() = dict.isLoaded && conn.isLoaded

    /** One segment of the best path, in left-to-right order. */
    data class Segment(
        val kanaStart: Int,
        val kanaLen: Int,
        val surface: String,
        val cost: Int,
        val lid: Int,
        val rid: Int,
        val isUnknown: Boolean,
    )

    data class Result(
        val segments: List<Segment>,
        val totalCost: Int,
    ) {
        fun joinedSurface(): String = buildString { segments.forEach { append(it.surface) } }
    }

    fun best(kana: String): Result? = searchTopK(kana, 1).firstOrNull()

    /**
     * Up to [K] best-cost segmentations of [kana], sorted ascending by
     * total cost (best first).  Empty when the engine isn't ready or
     * input is empty.  Internally clamped to [MAX_TOP_K].
     */
    fun searchTopK(kana: String, K: Int): List<Result> {
        if (!isReady || kana.isEmpty() || K <= 0) return emptyList()
        val k = K.coerceAtMost(MAX_TOP_K)
        val n = kana.length
        val edgesEndingAt = buildLattice(kana)

        // topK[pos] is sorted ascending by cost; topK[pos][r] is the r-th
        // best path from BOS to pos.  Position 0 has a single sentinel.
        val topK = Array(n + 1) { mutableListOf<PathSlot>() }
        topK[0].add(PathSlot(cost = 0, edgeEnd = -1, edgeIdx = -1, prevRank = -1, pathRid = BOS_EOS_POS))

        for (j in 1..n) {
            val edges = edgesEndingAt[j]
            for (kEdge in edges.indices) {
                val e = edges[kEdge]
                val prevSlots = topK[e.start]
                if (prevSlots.isEmpty()) continue
                for (r in prevSlots.indices) {
                    val prev = prevSlots[r]
                    if (prev.cost >= INF) continue
                    var bigram = conn.cost(prev.pathRid, e.lid).toInt()
                    if (bigram == Short.MAX_VALUE.toInt()) bigram = OOV_BIGRAM
                    val cost = prev.cost + bigram + e.cost
                    if (cost >= INF) continue
                    insertTopK(topK[j], PathSlot(cost, j, kEdge, r, e.rid), k)
                }
            }
        }

        // EOS bigram on each top-K final slot, then resort.  prevRank on
        // these slots still indexes into the (unchanged) topK[edge.start];
        // only the order at position N changes.
        for (slot in topK[n]) {
            if (slot.cost >= INF) continue
            var eos = conn.cost(slot.pathRid, BOS_EOS_POS).toInt()
            if (eos == Short.MAX_VALUE.toInt()) eos = OOV_BIGRAM
            slot.cost += eos
        }
        topK[n].sortBy { it.cost }

        // Backtrack each rank to recover its segment list.
        val out = ArrayList<Result>(topK[n].size)
        for (r in topK[n].indices) {
            val finalSlot = topK[n][r]
            if (finalSlot.cost >= INF) break
            val segments = ArrayList<Segment>()
            var curPos = n
            var curRank = r
            var ok = true
            while (curPos > 0) {
                if (curRank < 0 || curRank >= topK[curPos].size) {
                    ok = false; break
                }
                val slot = topK[curPos][curRank]
                if (slot.edgeIdx < 0) break
                val e = edgesEndingAt[slot.edgeEnd][slot.edgeIdx]
                segments.add(
                    Segment(
                        kanaStart = e.start,
                        kanaLen = e.end - e.start,
                        surface = e.surface,
                        cost = e.cost,
                        lid = e.lid,
                        rid = e.rid,
                        isUnknown = e.isUnknown,
                    ),
                )
                curRank = slot.prevRank
                curPos = e.start
            }
            if (!ok || segments.isEmpty()) continue
            segments.reverse()
            out.add(Result(segments, finalSlot.cost))
        }

        // Dedup by joined surface — different segment boundaries can yield
        // identical visible strings; keep the cheapest.
        return out.distinctBy { it.joinedSurface() }
    }

    /**
     * Build edge lists keyed by end position.  Always includes a length-1
     * unknown-fallback edge at every start so the search is well-defined
     * for OOV input.
     */
    private fun buildLattice(kana: String): Array<MutableList<Edge>> {
        val n = kana.length
        val edgesEndingAt = Array(n + 1) { mutableListOf<Edge>() }
        for (i in 0 until n) {
            for (l in 1..(n - i)) {
                val sub = kana.substring(i, i + l)
                val entries = dict.lookup(sub)
                if (entries.isEmpty()) continue
                val list = edgesEndingAt[i + l]
                for (e in entries) {
                    list.add(
                        Edge(
                            start = i,
                            end = i + l,
                            surface = e.surface,
                            cost = e.cost.toInt(),
                            lid = e.lid,
                            rid = e.rid,
                            isUnknown = false,
                        ),
                    )
                }
            }
            edgesEndingAt[i + 1].add(
                Edge(
                    start = i,
                    end = i + 1,
                    surface = kana.substring(i, i + 1),
                    cost = UNKNOWN_COST_PER_CHAR,
                    lid = UNKNOWN_POS,
                    rid = UNKNOWN_POS,
                    isUnknown = true,
                ),
            )
        }
        return edgesEndingAt
    }

    /**
     * Sorted insert into an at-most-[k]-element list, keeping ascending
     * cost order.  Drops the worst entry when full and the new candidate
     * beats it.
     */
    private fun insertTopK(slots: MutableList<PathSlot>, cand: PathSlot, k: Int) {
        if (slots.size < k) {
            val pos = sortedInsertIndex(slots, cand)
            slots.add(pos, cand)
            return
        }
        if (cand.cost >= slots.last().cost) return
        slots.removeAt(slots.size - 1)
        val pos = sortedInsertIndex(slots, cand)
        slots.add(pos, cand)
    }

    private fun sortedInsertIndex(slots: List<PathSlot>, cand: PathSlot): Int {
        // First index `i` where slots[i].cost > cand.cost (upper_bound).
        var lo = 0
        var hi = slots.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (slots[mid].cost <= cand.cost) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private data class Edge(
        val start: Int,
        val end: Int,
        val surface: String,
        val cost: Int,
        val lid: Int,
        val rid: Int,
        val isUnknown: Boolean,
    )

    private data class PathSlot(
        var cost: Int,
        val edgeEnd: Int,
        val edgeIdx: Int,
        val prevRank: Int,
        val pathRid: Int,
    )

    companion object {
        /**
         * "Unknown word" cost for the raw-kana fallback edge inserted
         * whenever no dictionary entry covers a position.  Picked above
         * the highest real Mozc cost (~18,318) so any actual dict match
         * wins on price — but not so absurd that a sentence full of
         * unknown chars overflows the int running total.
         */
        private const val UNKNOWN_COST_PER_CHAR = 25_000

        /** POS id 0 in id.def is "BOS/EOS" — also reused as unknown. */
        private const val UNKNOWN_POS = 0
        private const val BOS_EOS_POS = 0

        /** Hard cap on K to keep per-position memory linear. */
        const val MAX_TOP_K = 10

        /** Substitute when a bigram lookup returns sentinel max-int. */
        private const val OOV_BIGRAM = 30_000

        /** Sentinel for untouched DP cells. */
        private const val INF = Int.MAX_VALUE / 4
    }
}
