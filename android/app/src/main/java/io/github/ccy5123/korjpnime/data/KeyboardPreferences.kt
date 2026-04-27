package io.github.ccy5123.korjpnime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.ThemeMode
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
    private val DIRECTION_KEY = stringPreferencesKey("theme_direction")
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val HEIGHT_KEY = intPreferencesKey("keyboard_height_dp")

    /** Default theme direction id — d1 Stratus (cool blue, rounded, chip strip). */
    const val DEFAULT_DIRECTION_ID = "d1"

    /**
     * Default keyboard surface height in dp.  360 dp is the comfortable
     * middle ground for the 5-row Beolsik layout (~70 dp/row including
     * gaps) while still leaving display area for the editor on shorter
     * phones; user can resize via [HEIGHT_KEY].
     */
    const val DEFAULT_HEIGHT_DP = 360
    /** User-adjustable bounds for the keyboard height slider. */
    const val MIN_HEIGHT_DP = 240
    const val MAX_HEIGHT_DP = 480

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

    /** Stream of the user's selected theme direction id (`d1`..`d5`). */
    fun directionFlow(context: Context): Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[DIRECTION_KEY] ?: DEFAULT_DIRECTION_ID
        }

    suspend fun setDirection(context: Context, directionId: String) {
        context.dataStore.edit { it[DIRECTION_KEY] = directionId }
    }

    /** Stream of the user's light / dark / auto preference. */
    fun themeModeFlow(context: Context): Flow<ThemeMode> =
        context.dataStore.data.map { prefs ->
            when (prefs[THEME_MODE_KEY]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                else -> ThemeMode.AUTO
            }
        }

    suspend fun setThemeMode(context: Context, mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    /** Stream of the user's selected keyboard height in dp. */
    fun heightFlow(context: Context): Flow<Int> =
        context.dataStore.data.map { prefs ->
            (prefs[HEIGHT_KEY] ?: DEFAULT_HEIGHT_DP)
                .coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        }

    suspend fun setHeight(context: Context, dp: Int) {
        context.dataStore.edit {
            it[HEIGHT_KEY] = dp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
        }
    }
}
