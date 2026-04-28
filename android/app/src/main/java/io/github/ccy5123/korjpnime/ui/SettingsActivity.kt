package io.github.ccy5123.korjpnime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Configuration entry point — opened from:
 *   1. The keyboard's gear icon in [io.github.ccy5123.korjpnime.keyboard.TopChrome].
 *   2. The system "input methods" settings page (deep-linked via
 *      `settingsActivity` in `res/xml/method.xml`).
 *   3. [io.github.ccy5123.korjpnime.MainActivity]'s setup flow.
 *
 * Hosts a tiny in-process navigation stack: SETTINGS (root) ↔ LICENSES
 * (open-source attribution).  No NavHost / fragments — Compose state
 * is enough at this scale.
 */
class SettingsActivity : ComponentActivity() {
    private enum class Screen { SETTINGS, LICENSES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var current by remember { mutableStateOf(Screen.SETTINGS) }
                    when (current) {
                        Screen.SETTINGS -> SettingsScreen(
                            onClose = { finish() },
                            onOpenLicenses = { current = Screen.LICENSES },
                        )
                        Screen.LICENSES -> LicensesScreen(
                            onClose = { current = Screen.SETTINGS },
                        )
                    }
                }
            }
        }
    }
}
