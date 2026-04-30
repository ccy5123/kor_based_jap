package io.github.ccy5123.korjpnime.engine

import android.content.Context
import android.content.SharedPreferences
import io.github.ccy5123.korjpnime.theme.InputLanguage

/**
 * Ordered list of values the user has previously picked from the candidate
 * strip, scoped by [InputLanguage].  On the next lookup of the same key in
 * the same language, these surface before the static dictionary or
 * wordlist candidates so the IME learns the user's preferences without
 * crossing into a server-backed model.
 *
 *   - JAPANESE: key = kana run, value = picked kanji.
 *   - KOREAN:   key = Hangul prefix, value = picked Korean word.
 *   - ENGLISH:  key = letter prefix (lowercased), value = picked word.
 *
 * Backed by [SharedPreferences] (one storage key per (language, key) pair,
 * tab-separated values).  Capped at [MAX_PER_KEY] entries per (lang, key)
 * so a user with hundreds of picks doesn't grow the prefs file
 * unboundedly — older picks fall off the back when the cap is hit.
 *
 * JAPANESE retains the legacy un-namespaced storage prefix (`uk:`) so
 * existing test-build histories survive the migration to language scopes
 * landed alongside KOR / ENG autocomplete.  KOR / ENG add their own
 * `uk:ko:` / `uk:en:` namespaces.
 */
class UserDict(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the user-pick history for [key] under [language], most-
     * recent-first.  Empty list when nothing's been recorded yet.
     */
    fun getUserCandidates(language: InputLanguage, key: String): List<String> {
        if (key.isEmpty()) return emptyList()
        val raw = prefs.getString(storageKey(language, key), null) ?: return emptyList()
        return raw.split(VAL_SEP).filter { it.isNotEmpty() }
    }

    /**
     * Move [value] to the front of the [key]'s pick list under [language].
     * No-ops on empty strings or when the user "picked" the key itself
     * (staying as the raw input — already the fallback candidate, no
     * point persisting).
     *
     * Also maintains an LRU index per language, evicting the oldest keys
     * once a language's distinct-key count exceeds [MAX_KEYS_PER_LANG].
     * Without this the prefs file grows unboundedly with every new
     * prefix the user picks for — fine for a few months but
     * uncomfortable across years of typing on a single device.
     */
    fun recordPick(language: InputLanguage, key: String, value: String) {
        if (key.isEmpty() || value.isEmpty()) return
        if (key == value) return
        val storageKey = storageKey(language, key)
        val existing = prefs.getString(storageKey, null)
            ?.split(VAL_SEP)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val updated = (listOf(value) + existing.filter { it != value })
            .take(MAX_PER_KEY)

        // LRU index: most-recent key first.  Move the just-picked key to
        // the front; if the language's index exceeds the total-keys cap,
        // drop the tail and remove their value entries in the same edit.
        val indexKey = indexKey(language)
        val index = prefs.getString(indexKey, null)
            ?.split(IDX_SEP)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val newIndex = (listOf(key) + index.filter { it != key })
        val (kept, evicted) =
            if (newIndex.size > MAX_KEYS_PER_LANG)
                newIndex.take(MAX_KEYS_PER_LANG) to newIndex.drop(MAX_KEYS_PER_LANG)
            else newIndex to emptyList()

        val editor = prefs.edit()
        editor.putString(storageKey, updated.joinToString(VAL_SEP))
        editor.putString(indexKey, kept.joinToString(IDX_SEP))
        for (evictedKey in evicted) {
            editor.remove(storageKey(language, evictedKey))
        }
        editor.apply()
    }

    /**
     * Build the SharedPreferences key.  JAPANESE uses the legacy
     * un-namespaced `uk:` prefix so previously-recorded picks persist
     * across the per-language migration; KOR / ENG live under
     * `uk:<lang>:` to keep their suggestion histories isolated.
     */
    private fun storageKey(language: InputLanguage, key: String): String = when (language) {
        InputLanguage.JAPANESE -> "uk:$key"
        InputLanguage.KOREAN -> "uk:ko:$key"
        InputLanguage.ENGLISH -> "uk:en:$key"
    }

    /** Per-language LRU index entry holding all currently-tracked keys. */
    private fun indexKey(language: InputLanguage): String = when (language) {
        InputLanguage.JAPANESE -> "idx:"
        InputLanguage.KOREAN -> "idx:ko:"
        InputLanguage.ENGLISH -> "idx:en:"
    }

    companion object {
        private const val PREFS_NAME = "userdict"
        private const val VAL_SEP = "\t"
        private const val IDX_SEP = "\n"
        private const val MAX_PER_KEY = 8

        /**
         * Per-language cap on tracked prefix keys.  At 2000 keys × ~60 B
         * average per entry that's ≈120 KB per language, ≈360 KB across
         * KOR / ENG / JP combined — well under SharedPreferences'
         * comfort zone.  Once a language exceeds this, the oldest keys
         * get evicted on the next [recordPick] call.
         */
        private const val MAX_KEYS_PER_LANG = 2000
    }
}
