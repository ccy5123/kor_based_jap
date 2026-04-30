package io.github.ccy5123.korjpnime.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-syllable Hangul → Hanja lookup loaded from
 * `assets/hanja_dict.txt`.  Filtered + redistributed from libhangul's
 * `data/hanja/hanja.txt` (BSD-3-clause, see THIRD_PARTY_LICENSES.txt).
 *
 *   - File format: `<hangul>\t<hanja>\t<gloss>\n` lines (`#` = comment).
 *   - One Hangul syllable maps to many Hanja, each with its own gloss
 *     (e.g. `한 → 韓 (나라 한)`, `한 → 漢 (한나라 한)`, …).
 *   - Loaded once into a HashMap<Char, List<Entry>> on first access;
 *     ~28 K entries, < 2 MB resident.
 *
 * Multi-syllable conversion lives in upstream libhangul but isn't shipped
 * here — the user's spec for the `한자` key is "한 글자씩 변환하는 그
 * 시스템" (single-syllable conversion), so we filtered to single keys
 * to keep the dict small and the lookup hot path trivial.
 */
class HanjaDictionary {

    /** One Hanja candidate for a given Hangul syllable. */
    data class Entry(val hanja: Char, val gloss: String)

    @Volatile private var byHangul: Map<Char, List<Entry>> = emptyMap()

    val isLoaded: Boolean get() = byHangul.isNotEmpty()
    val keyCount: Int get() = byHangul.size

    /**
     * Load and index `assets/hanja_dict.txt`.  Runs on [Dispatchers.IO] —
     * caller should `launch` from a coroutine scope.  Idempotent.
     */
    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        val map = HashMap<Char, MutableList<Entry>>(32_000)
        try {
            context.assets.open("hanja_dict.txt").use { input ->
                input.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith('#')) continue
                        val tab1 = line.indexOf('\t')
                        if (tab1 != 1) continue  // hangul column must be 1 char
                        val tab2 = line.indexOf('\t', tab1 + 1)
                        if (tab2 != tab1 + 2) continue  // hanja column must be 1 char
                        val hangul = line[0]
                        val hanja = line[tab1 + 1]
                        val gloss = if (tab2 + 1 < line.length) line.substring(tab2 + 1) else ""
                        map.getOrPut(hangul) { mutableListOf() }.add(Entry(hanja, gloss))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("HanjaDictionary", "Load failed", e)
            return@withContext false
        }
        byHangul = map
        true
    }

    /** Return all Hanja candidates for [syllable], ordered by libhangul's
     *  source ordering (roughly frequency-then-stroke for the curated dict).
     *  Empty list if the syllable has no Hanja mapping or the dict isn't
     *  loaded yet. */
    fun lookup(syllable: Char): List<Entry> = byHangul[syllable] ?: emptyList()
}
