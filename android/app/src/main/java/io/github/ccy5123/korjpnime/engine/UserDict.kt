package io.github.ccy5123.korjpnime.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-kana ordered list of kanji the user has previously picked from the
 * candidate strip.  On the next lookup of the same kana, these surface
 * before the static dictionary candidates so the IME learns the user's
 * preferences without crossing into a server-backed model.
 *
 * Backed by [SharedPreferences] (one key per kana, tab-separated kanji
 * values).  Capped at [MAX_PER_KEY] kanji per kana so a user with hundreds
 * of distinct picks doesn't grow the prefs file unboundedly — older picks
 * fall off the back when the cap is hit.  Total prefs size for a typical
 * user (~few thousand picks across distinct kana) stays under 100 KB.
 *
 * Mirrors `tsf/src/UserDict.cpp` semantics with Android-native persistence
 * instead of the flat text file the Windows TSF used.
 */
class UserDict(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the user-pick history for [kana], most-recent-first.  Empty
     * list when nothing's been recorded for that kana yet.
     */
    fun getUserCandidates(kana: String): List<String> {
        if (kana.isEmpty()) return emptyList()
        val raw = prefs.getString(KEY_PREFIX + kana, null) ?: return emptyList()
        return raw.split(SEP).filter { it.isNotEmpty() }
    }

    /**
     * Move [kanji] to the front of [kana]'s pick list.  No-ops on empty
     * strings or when the user "picked" the kana itself (staying as
     * hiragana — already the fallback candidate, no point persisting).
     */
    fun recordPick(kana: String, kanji: String) {
        if (kana.isEmpty() || kanji.isEmpty()) return
        if (kana == kanji) return
        val key = KEY_PREFIX + kana
        val existing = prefs.getString(key, null)
            ?.split(SEP)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val updated = (listOf(kanji) + existing.filter { it != kanji })
            .take(MAX_PER_KEY)
        prefs.edit().putString(key, updated.joinToString(SEP)).apply()
    }

    companion object {
        private const val PREFS_NAME = "userdict"
        private const val KEY_PREFIX = "uk:"
        private const val SEP = "\t"
        private const val MAX_PER_KEY = 8
    }
}
