package io.github.ccy5123.korjpnime.engine

import android.content.Context
import android.content.SharedPreferences

/**
 * Most-recent-first list of emojis the user has tapped from the panel.
 * Surfaced as the "최근" tab so frequent picks stay one tap away.
 *
 * Backed by [SharedPreferences] (single key, U+001F unit-separator
 * joining).  Capped at [MAX_ITEMS] (16) — older roll off.
 */
class EmojiHistory(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val items: List<String>
        get() {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            return raw.split(SEP).filter { it.isNotEmpty() }
        }

    /** Push [emoji] to the front (deduped + capped). */
    fun add(emoji: String) {
        if (emoji.isEmpty()) return
        val current = items
        if (current.firstOrNull() == emoji) return
        val updated = (listOf(emoji) + current.filter { it != emoji })
            .take(MAX_ITEMS)
        prefs.edit()
            .putString(KEY, updated.joinToString(SEP))
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "emoji_history"
        private const val KEY = "items"
        // U+001F unit separator — never appears in legitimate emoji.
        private const val SEP = ""
        private const val MAX_ITEMS = 16
    }
}
