package io.github.ccy5123.korjpnime.engine

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences

/**
 * Most-recent-first list of clipboard text snapshots, persisted to
 * SharedPreferences so the user's recent copy/paste history survives
 * IME restarts.  Capped at [MAX_ITEMS] (10) — older items roll off.
 *
 * The IME service registers an [ClipboardManager.OnPrimaryClipChangedListener]
 * on attach; whenever the listener fires, [add] is called with the
 * fresh clipboard text.  The system clipboard is the only entry point —
 * we never proactively read clipboard contents (which would require
 * extra permission prompts on Android 10+).
 *
 * UI consumers (the ⋯ menu's 클립보드 entry) read [items] and show a
 * panel of taps; selecting one commits the text via the editor's
 * InputConnection.
 */
class ClipboardHistory(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Most-recent-first view of the saved clipboard texts. */
    val items: List<String>
        get() {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            return raw.split(SEP).filter { it.isNotEmpty() }
        }

    /** Push [text] to the front of the history; deduped + capped. */
    fun add(text: String) {
        if (text.isEmpty()) return
        val trimmed = text.take(MAX_ITEM_LENGTH)
        val current = items
        if (current.firstOrNull() == trimmed) return  // identical to head, no-op
        val updated = (listOf(trimmed) + current.filter { it != trimmed })
            .take(MAX_ITEMS)
        prefs.edit()
            .putString(KEY, updated.joinToString(SEP))
            .apply()
    }

    /** Remove [text] from the history (e.g. user long-pressed → delete). */
    fun remove(text: String) {
        val updated = items.filter { it != text }
        prefs.edit()
            .putString(KEY, updated.joinToString(SEP))
            .apply()
    }

    /** Wipe everything. */
    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "clipboard_history"
        private const val KEY = "items"
        // U+001F unit separator — won't legitimately appear in copied text.
        private const val SEP = ""
        private const val MAX_ITEMS = 10
        private const val MAX_ITEM_LENGTH = 500
    }
}
