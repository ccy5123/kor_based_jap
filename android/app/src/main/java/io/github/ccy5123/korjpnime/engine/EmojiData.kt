package io.github.ccy5123.korjpnime.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Curated emoji set for the keyboard's emoji panel.  Loaded from
 * `assets/emoji_data.txt` (filtered Unicode emoji-test.txt v17.0; see
 * THIRD_PARTY_LICENSES.txt for terms).
 *
 * The asset's file format:
 *   `G\t<group name>`               — header for a category
 *   `E\t<emoji>`                    — base emoji in the most recent group
 *   `T\t<base>\t<v1>\t<v2>\t...`    — skin-tone variants for [base]
 *
 * Asset is small (~50 KB / ~1900 base emojis) so we load eagerly into
 * memory on first access.  Skin-tone variants are reachable via
 * [variantsOf]; the v1 panel surfaces only the base emoji and the
 * variants are reserved for a future long-press gesture.
 */
class EmojiData {

    /** One category (group) of emojis as they appear in the panel tabs. */
    data class Category(val name: String, val emojis: List<String>)

    @Volatile private var categories: List<Category> = emptyList()
    @Volatile private var skinVariants: Map<String, List<String>> = emptyMap()

    val isLoaded: Boolean get() = categories.isNotEmpty()

    /** Ordered list of categories, suitable for tab rendering. */
    fun categories(): List<Category> = categories

    /** Skin-tone variants for [base], or empty list if none are known.
     *  Surfaced by the panel's long-press gesture (v2 work). */
    fun variantsOf(base: String): List<String> = skinVariants[base] ?: emptyList()

    suspend fun load(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext true
        val cats = ArrayList<Category>(10)
        val variants = HashMap<String, List<String>>(500)
        var currentName: String? = null
        var currentEmojis: ArrayList<String>? = null
        try {
            context.assets.open("emoji_data.txt").use { input ->
                input.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith('#')) continue
                        val tab1 = line.indexOf('\t')
                        if (tab1 <= 0) continue
                        val tag = line[0]
                        when (tag) {
                            'G' -> {
                                // Flush previous group into categories.
                                if (currentName != null && currentEmojis != null) {
                                    cats.add(Category(currentName!!, currentEmojis!!))
                                }
                                currentName = line.substring(tab1 + 1)
                                currentEmojis = ArrayList()
                            }
                            'E' -> {
                                val emoji = line.substring(tab1 + 1)
                                currentEmojis?.add(emoji)
                            }
                            'T' -> {
                                val parts = line.substring(tab1 + 1).split('\t')
                                if (parts.size >= 2) {
                                    variants[parts[0]] = parts.drop(1)
                                }
                            }
                        }
                    }
                }
            }
            if (currentName != null && currentEmojis != null) {
                cats.add(Category(currentName!!, currentEmojis!!))
            }
        } catch (e: Exception) {
            android.util.Log.w("EmojiData", "Load failed", e)
            return@withContext false
        }
        categories = cats
        skinVariants = variants
        true
    }
}
