package io.github.ccy5123.korjpnime.engine

import android.content.Context
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * mmap'd kana → list-of-(surface, cost, lid, rid) lookup, reading
 * `assets/kj_dict.bin` (built by `dict/build_viterbi_data.py`).  Direct
 * port of `tsf/src/RichDictionary.cpp`.
 *
 * Distinct from [Dictionary] (text `jpn_dict.txt`) because the Viterbi
 * engine needs lid/rid + per-surface cost, which the legacy text format
 * intentionally omits to stay small.  Both coexist while M3 settles —
 * Viterbi engages when ready, simple lookup remains as a fallback.
 *
 * File layout (little-endian, see `dict/build_viterbi_data.py` for spec):
 *
 *     Header (16 B):
 *       char[4]  magic    = "KJDV"
 *       uint32   version  = 1
 *       uint32   num_kana
 *       uint32   num_entries
 *
 *     KanaEntry table  (num_kana * 16 B, sorted by kana UTF-8 bytes):
 *       uint32  kana_off
 *       uint16  kana_len
 *       uint16  pad
 *       uint32  entry_start
 *       uint32  entry_count
 *
 *     Entry table  (num_entries * 16 B):
 *       uint32  surface_off
 *       uint16  surface_len
 *       int16   cost
 *       uint16  lid
 *       uint16  rid
 *       uint32  pad
 *
 *     String pool (UTF-8, contiguous bytes)
 *
 * All ByteBuffer reads use absolute-index methods so the buffer's
 * internal position stays at 0 — safe for repeated lookups from the
 * IME UI thread.  Surface decoding allocates a fresh ByteArray per
 * entry; that's the bottleneck if a kana key has many candidates,
 * but typical readings have <30 surfaces and total <1 KB decoded.
 */
class RichDictionary {

    @Volatile private var buffer: ByteBuffer? = null
    private var numKana: Int = 0
    private var numEntries: Int = 0
    private var kanaTableOff: Int = 0
    private var entryTableOff: Int = 0
    private var poolOff: Int = 0

    val isLoaded: Boolean get() = buffer != null
    val keyCount: Int get() = numKana
    val entryCount: Int get() = numEntries

    data class Entry(
        val surface: String,
        val cost: Short,
        val lid: Int,
        val rid: Int,
    )

    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        val local = ensureExtracted(context, ASSET_NAME) ?: return@withContext false
        val raf = RandomAccessFile(local, "r")
        val channel = raf.channel
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        buf.order(ByteOrder.LITTLE_ENDIAN)
        if (!validateHeader(buf)) {
            raf.close()
            return@withContext false
        }
        numKana = buf.getInt(8)
        numEntries = buf.getInt(12)
        kanaTableOff = HEADER_SIZE
        entryTableOff = kanaTableOff + numKana * KANA_ENTRY_SIZE
        poolOff = entryTableOff + numEntries * ENTRY_SIZE
        if (poolOff > channel.size()) {
            raf.close()
            return@withContext false
        }
        buffer = buf
        true
    }

    /**
     * Returns all entries matching [kanaKey] exactly, in ascending-cost
     * order (already sorted at build time).  Empty list if not found or
     * the dictionary failed to load.
     */
    fun lookup(kanaKey: String): List<Entry> {
        val buf = buffer ?: return emptyList()
        if (kanaKey.isEmpty()) return emptyList()
        val key = kanaKey.toByteArray(Charsets.UTF_8)

        val idx = lowerBound(buf, key)
        if (idx >= numKana) return emptyList()

        val kanaEntryStart = kanaTableOff + idx * KANA_ENTRY_SIZE
        val kanaOff = buf.getInt(kanaEntryStart)
        val kanaLen = buf.getShort(kanaEntryStart + 4).toInt() and 0xFFFF
        if (kanaLen != key.size) return emptyList()
        if (compareKey(buf, poolOff + kanaOff, kanaLen, key) != 0) return emptyList()

        val entryStartIdx = buf.getInt(kanaEntryStart + 8)
        val entryCnt = buf.getInt(kanaEntryStart + 12)
        if (entryStartIdx.toLong() + entryCnt > numEntries) return emptyList()

        val out = ArrayList<Entry>(entryCnt)
        val readBuf = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until entryCnt) {
            val eOff = entryTableOff + (entryStartIdx + i) * ENTRY_SIZE
            val surfaceOff = buf.getInt(eOff)
            val surfaceLen = buf.getShort(eOff + 4).toInt() and 0xFFFF
            val cost = buf.getShort(eOff + 6)
            val lid = buf.getShort(eOff + 8).toInt() and 0xFFFF
            val rid = buf.getShort(eOff + 10).toInt() and 0xFFFF
            // surface_off + surface_len bounds check — defensive against
            // file truncation.
            val poolEnd = buf.capacity()
            val surfaceStart = poolOff + surfaceOff
            if (surfaceStart < 0 || surfaceStart + surfaceLen > poolEnd) continue
            val surfaceBytes = ByteArray(surfaceLen)
            readBuf.position(surfaceStart)
            readBuf.get(surfaceBytes)
            val surface = String(surfaceBytes, Charsets.UTF_8)
            out.add(Entry(surface, cost, lid, rid))
        }
        return out
    }

    private fun lowerBound(buf: ByteBuffer, key: ByteArray): Int {
        var lo = 0
        var hi = numKana
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val entryStart = kanaTableOff + mid * KANA_ENTRY_SIZE
            val kanaOff = buf.getInt(entryStart)
            val kanaLen = buf.getShort(entryStart + 4).toInt() and 0xFFFF
            val cmp = compareKey(buf, poolOff + kanaOff, kanaLen, key)
            if (cmp < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareKey(buf: ByteBuffer, offset: Int, len: Int, key: ByteArray): Int {
        val n = minOf(len, key.size)
        for (i in 0 until n) {
            val a = buf.get(offset + i).toInt() and 0xFF
            val b = key[i].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - key.size
    }

    private fun validateHeader(buf: ByteBuffer): Boolean {
        if (buf.capacity() < HEADER_SIZE) return false
        return buf.get(0) == 'K'.code.toByte() &&
            buf.get(1) == 'J'.code.toByte() &&
            buf.get(2) == 'D'.code.toByte() &&
            buf.get(3) == 'V'.code.toByte() &&
            buf.getInt(4) == VERSION
    }

    companion object {
        private const val ASSET_NAME = "kj_dict.bin"
        private const val HEADER_SIZE = 16
        private const val KANA_ENTRY_SIZE = 16
        private const val ENTRY_SIZE = 16
        private const val VERSION = 1
    }
}
