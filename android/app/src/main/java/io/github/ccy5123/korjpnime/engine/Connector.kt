package io.github.ccy5123.korjpnime.engine

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only access to the bigram connection-cost matrix shipped in
 * `assets/kj_conn.bin` (built by `dict/build_viterbi_data.py` from Mozc OSS).
 * Direct port of `tsf/src/Connector.cpp`.
 *
 * Indexed by (left_token.rid, right_token.lid).  Lower cost = more
 * grammatical adjacency.  Together with [RichDictionary]'s per-entry
 * unigram cost, this is what lets Viterbi pick `私 + の` over `渡志野`
 * for わたしの.
 *
 * On Android we extract the asset to internal storage on first load,
 * then mmap the local file via [FileChannel.map].  Asset offsets in the
 * APK aren't guaranteed page-aligned, so direct asset mmap is fragile;
 * the one-time copy is cheap (~14 MB) and gives reliable mmap thereafter.
 *
 * File layout (little-endian):
 *
 *     char[4]  magic    = "KJCN"
 *     uint32   version  = 1
 *     uint16   dim
 *     uint16   _pad
 *     uint32   _reserved
 *     int16    matrix[dim * dim]      // row-major, cost[lid * dim + rid]
 */
class Connector {

    @Volatile private var buffer: ByteBuffer? = null
    private var dim: Int = 0

    val isLoaded: Boolean get() = buffer != null
    val dimension: Int get() = dim

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
        dim = buf.getShort(8).toInt() and 0xFFFF
        val expected = HEADER_SIZE.toLong() + dim.toLong() * dim * 2
        if (channel.size() < expected || dim == 0) {
            raf.close()
            return@withContext false
        }
        buffer = buf
        true
    }

    /**
     * Bigram transition cost from a left token with `rid = leftRid` to a
     * right token with `lid = rightLid`.  Returns [Short.MAX_VALUE] when
     * out-of-range or the connector failed to load — safe to add into a
     * Viterbi score because nothing legitimate ever beats max + max.
     */
    fun cost(leftRid: Int, rightLid: Int): Short {
        val buf = buffer ?: return Short.MAX_VALUE
        if (leftRid < 0 || rightLid < 0 || leftRid >= dim || rightLid >= dim) {
            return Short.MAX_VALUE
        }
        val offset = HEADER_SIZE + (leftRid * dim + rightLid) * 2
        return buf.getShort(offset)
    }

    private fun validateHeader(buf: ByteBuffer): Boolean {
        if (buf.capacity() < HEADER_SIZE) return false
        return buf.get(0) == 'K'.code.toByte() &&
            buf.get(1) == 'J'.code.toByte() &&
            buf.get(2) == 'C'.code.toByte() &&
            buf.get(3) == 'N'.code.toByte() &&
            buf.getInt(4) == VERSION
    }

    companion object {
        private const val ASSET_NAME = "kj_conn.bin"
        private const val HEADER_SIZE = 16
        private const val VERSION = 1
    }
}

/**
 * Extract [name] from `assets/` to `filesDir/name` if not already present
 * (or if size differs from the asset).  Returns the local file or null if
 * the asset is missing.  Shared by [RichDictionary] and [Connector] for
 * the same one-time-copy + mmap pattern.
 */
internal fun ensureExtracted(context: Context, name: String): File? {
    val target = File(context.filesDir, name)
    val expectedSize = try {
        context.assets.openFd(name).use { it.declaredLength }
    } catch (e: Exception) {
        return null
    }
    if (target.exists() && target.length() == expectedSize) return target
    return try {
        context.assets.open(name).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target
    } catch (e: Exception) {
        null
    }
}
