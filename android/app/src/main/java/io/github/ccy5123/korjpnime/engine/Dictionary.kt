package io.github.ccy5123.korjpnime.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kana → kanji lookup loaded from `assets/jpn_dict.txt`.  Direct port of
 * `tsf/src/Dictionary.cpp` adapted for Android assets:
 *
 *   - The text file is sorted by kana (UTF-8) and each non-comment line is
 *     `<kana>\t<kanji_1>\t<kanji_2>\t...\n`.
 *   - Load reads the asset bytes once into a [ByteArray] (no mmap — Android
 *     APK assets aren't directly mmap-able without aligned offsets, and 19 MB
 *     is fine to hold in process memory).
 *   - An index of `(lineStart, kanaLen, lineLen)` triples is built once;
 *     [lookup] does a byte-level binary search on it.
 *
 * Index size for ~505K keys: 3 × 4 bytes/entry ≈ 6 MB.  Plus the ~19 MB
 * data array.  Total ~25 MB resident — acceptable for the IME service on
 * any device that runs a Compose keyboard.
 */
class Dictionary {

    @Volatile private var data: ByteArray? = null
    private var lineStarts: IntArray = IntArray(0)
    private var kanaLens: IntArray = IntArray(0)
    private var lineLens: IntArray = IntArray(0)

    val isLoaded: Boolean get() = data != null
    val keyCount: Int get() = lineStarts.size

    /**
     * Load and index `assets/jpn_dict.txt`.  Runs on [Dispatchers.IO] — the
     * caller should `launch` from a coroutine scope.  Idempotent: the second
     * call is a no-op once [isLoaded] is true.
     */
    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        val bytes = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (e: Exception) {
            return@withContext false
        }
        buildIndex(bytes)
        data = bytes
        true
    }

    private fun buildIndex(bytes: ByteArray) {
        // Pre-size for ~550K rows; the v1 dict has 505K.
        val starts = IntArray(550_000)
        val klens = IntArray(550_000)
        val llens = IntArray(550_000)
        var count = 0

        var p = 0
        val n = bytes.size
        while (p < n) {
            val lineStart = p
            // Find end of line (LF).
            while (p < n && bytes[p] != BYTE_LF) p++
            val lineEnd = p
            if (p < n) p++  // skip LF

            if (lineStart == lineEnd) continue          // blank
            if (bytes[lineStart] == BYTE_HASH) continue // header / comment

            // First TAB separates kana from candidates.
            var t = lineStart
            while (t < lineEnd && bytes[t] != BYTE_TAB) t++
            if (t >= lineEnd) continue  // malformed — no TAB

            if (count >= starts.size) {
                // Defensive: expand if dict ever grows past the pre-size.
                // (Lazy: just reallocate larger arrays.)
                return  // bail rather than crash; caller sees partial index
            }
            starts[count] = lineStart
            klens[count] = t - lineStart
            llens[count] = lineEnd - lineStart
            count++
        }
        lineStarts = starts.copyOf(count)
        kanaLens = klens.copyOf(count)
        lineLens = llens.copyOf(count)
    }

    /**
     * Returns kanji candidates for [kana] (UTF-16 hiragana).  Empty list if
     * dict isn't loaded yet, key is empty, or no match.  Order matches the
     * dictionary file (most-frequent-first, by build_dict_*.py convention).
     */
    fun lookup(kana: String): List<String> {
        val data = data ?: return emptyList()
        if (kana.isEmpty()) return emptyList()
        val key = kana.toByteArray(Charsets.UTF_8)

        val idx = lowerBound(data, key)
        if (idx >= lineStarts.size) return emptyList()
        if (kanaLens[idx] != key.size) return emptyList()
        if (compareBytes(
                data, lineStarts[idx], kanaLens[idx],
                key, 0, key.size,
            ) != 0
        ) return emptyList()

        val out = ArrayList<String>(8)
        val lineStart = lineStarts[idx]
        val lineEnd = lineStart + lineLens[idx]
        var p = lineStart + kanaLens[idx] + 1  // skip kana + first TAB
        while (p < lineEnd) {
            var next = p
            while (next < lineEnd && data[next] != BYTE_TAB) next++
            out.add(String(data, p, next - p, Charsets.UTF_8))
            if (next >= lineEnd) break
            p = next + 1
        }
        return out
    }

    /** Standard lower-bound: first index whose key is ≥ [key]. */
    private fun lowerBound(data: ByteArray, key: ByteArray): Int {
        var lo = 0
        var hi = lineStarts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareBytes(
                data, lineStarts[mid], kanaLens[mid],
                key, 0, key.size,
            )
            if (cmp < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Unsigned-byte memcmp + length tiebreak. */
    private fun compareBytes(
        a: ByteArray, aOff: Int, aLen: Int,
        b: ByteArray, bOff: Int, bLen: Int,
    ): Int {
        val n = minOf(aLen, bLen)
        for (i in 0 until n) {
            val ai = a[aOff + i].toInt() and 0xFF
            val bi = b[bOff + i].toInt() and 0xFF
            if (ai != bi) return ai - bi
        }
        return aLen - bLen
    }

    companion object {
        private const val ASSET_NAME = "jpn_dict.txt"
        private const val BYTE_LF: Byte = 0x0A
        private const val BYTE_TAB: Byte = 0x09
        private const val BYTE_HASH: Byte = 0x23
    }
}
