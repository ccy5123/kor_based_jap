package io.github.ccy5123.korjpnime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App-wide DataStore for keyboard preferences.  Single-process, application-
 * scoped — both [io.github.ccy5123.korjpnime.KorJpnImeService] (the IME) and
 * [io.github.ccy5123.korjpnime.ui.SettingsActivity] (the configuration UI)
 * read / write the same store.
 *
 * For B (M1 closeout) we ship two settings: keyboard layout mode (두벌식 ↔
 * 천지인) and a haptics toggle.  Theme / candidate-count knobs land in M4.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "keyboard_prefs",
)

object KeyboardPreferences {

    private val MODE_KEY = stringPreferencesKey("keyboard_mode")
    private val HAPTICS_KEY = booleanPreferencesKey("haptics_enabled")

    /** Stream of the current keyboard layout mode.  Defaults to 두벌식. */
    fun modeFlow(context: Context): Flow<KeyboardMode> =
        context.dataStore.data.map { prefs ->
            when (prefs[MODE_KEY]) {
                KeyboardMode.CHEONJIIN.name -> KeyboardMode.CHEONJIIN
                else -> KeyboardMode.BEOLSIK
            }
        }

    /** Stream of the haptic-feedback-on-key-tap toggle.  Defaults to ON. */
    fun hapticsFlow(context: Context): Flow<Boolean> =
        context.dataStore.data.map { prefs -> prefs[HAPTICS_KEY] ?: true }

    suspend fun setMode(context: Context, mode: KeyboardMode) {
        context.dataStore.edit { it[MODE_KEY] = mode.name }
    }

    suspend fun setHaptics(context: Context, enabled: Boolean) {
        context.dataStore.edit { it[HAPTICS_KEY] = enabled }
    }
}
