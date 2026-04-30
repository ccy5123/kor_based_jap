package io.github.ccy5123.korjpnime.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Prefix-completion wordlist for the candidate strip in KOR / ENG modes.
 * Loaded from `assets/<asset>` where the file is `<word>\t<freq>\n` lines
 * (`#` = comment).  See `dict/LICENSES.txt` for upstream attribution
 * (FrequencyWords MIT, OpenSubtitles2016 corpus).
 *
 * Words are sorted by frequency descending after load so [lookup]'s
 * top-K prefix scan returns the most-common-first results without
 * re-sorting on every call.
 *
 * Memory: ~50 K entries × ~32 bytes each ≈ 1.5 MB resident.  Lookup is
 * a linear scan over the prefix-matched set — fine at our user-typing
 * cadence (a few hundred ms between keystrokes leaves plenty of budget).
 * Could swap for a trie if the strip ever feels laggy, but the simple
 * scan keeps the load path trivial.
 */
class WordlistDictionary(private val assetName: String) {

    /** One wordlist entry — the word and its raw frequency. */
    data class Entry(val word: String, val freq: Long)

    @Volatile private var entries: List<Entry> = emptyList()

    val isLoaded: Boolean get() = entries.isNotEmpty()
    val size: Int get() = entries.size

    /**
     * Load `<word>\t<freq>` lines from the named asset.  Idempotent;
     * returns false on any parse / IO failure (logged) and stays empty —
     * the candidate strip will simply show no autocomplete suggestions.
     */
    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        val list = ArrayList<Entry>(50_000)
        try {
            context.assets.open(assetName).use { input ->
                input.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith('#')) continue
                        val tab = line.indexOf('\t')
                        if (tab <= 0 || tab + 1 >= line.length) continue
                        val word = line.substring(0, tab)
                        val freq = line.substring(tab + 1).toLongOrNull() ?: continue
                        list.add(Entry(word, freq))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WordlistDictionary", "Load failed for $assetName", e)
            return@withContext false
        }
        list.sortByDescending { it.freq }
        entries = list
        true
    }

    /**
     * Top-[max] words that start with [prefix], frequency-descending.
     * Empty list when the prefix is empty or the dict isn't loaded yet.
     */
    fun lookup(prefix: String, max: Int = 16): List<String> {
        if (prefix.isEmpty() || !isLoaded) return emptyList()
        val out = ArrayList<String>(max)
        for (e in entries) {
            if (e.word.startsWith(prefix) && e.word != prefix) {
                out.add(e.word)
                if (out.size >= max) break
            }
        }
        return out
    }
}
