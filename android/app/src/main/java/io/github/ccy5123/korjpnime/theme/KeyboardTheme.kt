package io.github.ccy5123.korjpnime.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class KeyboardMode { BEOLSIK, CHEONJIIN }

/**
 * Input-language mode — cycled by the 한/영/일 toggle key on the letters
 * page.  Determines:
 *   - SpaceKey label (한국어 / Eng. / 日本語)
 *   - Symbol page content (Korean ASCII vs Japanese full-width)
 *   - Whether digit Commits get full-width-ified at the service boundary
 *
 * For now letter output behaviour stays "Korean jamo → Japanese kana"
 * regardless of mode — actual per-mode letter output (Korean hangul mode,
 * English QWERTY mode) lands in a follow-up.
 */
enum class InputLanguage { KOREAN, ENGLISH, JAPANESE }

/**
 * Light / dark resolution policy.  AUTO follows the system setting (the
 * usual default); LIGHT / DARK pin the keyboard regardless of system
 * preference — useful when the user prefers a fixed look that won't
 * flicker as the OS theme switches at sunset.
 */
enum class ThemeMode { AUTO, LIGHT, DARK }

enum class KeyShape { ROUNDED, PILL, FLAT, SQUIRCLE }

enum class StripTreatment { CHIP, UNDERLINE, HAIRLINE, FLUSH }

@Immutable
data class DirectionPalette(
    val hue: Float,
    val name: String,
    val desat: Boolean = false,
)

@Immutable
data class Direction(
    val id: String,
    val name: String,
    val palette: DirectionPalette,
    val shape: KeyShape,
    val strip: StripTreatment,
    val note: String,
)

object Palettes {
    val Blue = DirectionPalette(hue = 235f, name = "Cool Blue")
    val Coral = DirectionPalette(hue = 25f, name = "Warm Coral")
    val Sage = DirectionPalette(hue = 145f, name = "Muted Sage")
    val Gray = DirectionPalette(hue = 260f, name = "Neutral Gray", desat = true)
    val Purple = DirectionPalette(hue = 295f, name = "Vibrant Purple")
}

val DIRECTIONS: List<Direction> = listOf(
    Direction("d1", "Stratus", Palettes.Blue, KeyShape.ROUNDED, StripTreatment.CHIP,
        "Cool blue · rounded square · chip candidates"),
    Direction("d2", "Persimmon", Palettes.Coral, KeyShape.PILL, StripTreatment.UNDERLINE,
        "Warm coral · pill keys · underlined candidates"),
    Direction("d3", "Hinoki", Palettes.Sage, KeyShape.FLAT, StripTreatment.HAIRLINE,
        "Muted sage · flat dividers · hairline candidate strip"),
    Direction("d4", "Slate", Palettes.Gray, KeyShape.SQUIRCLE, StripTreatment.FLUSH,
        "Neutral gray · squircle keys · minimal flush strip"),
    Direction("d5", "Iris", Palettes.Purple, KeyShape.PILL, StripTreatment.CHIP,
        "Vibrant purple · pill keys · chip candidates"),
    // Material You — colours derived from the system's dynamic palette
    // on Android 12+.  Pre-Android 12 falls back to a Stratus-like blue
    // since the dynamic API isn't available.  See [resolveTokens].
    Direction("d6", "System", Palettes.Blue, KeyShape.ROUNDED, StripTreatment.CHIP,
        "Material You · system dynamic colors (Android 12+)"),
)

/** Direction id reserved for Material You — checked at token-resolve time. */
const val MATERIAL_YOU_DIRECTION_ID = "d6"

@Immutable
data class KeyboardTokens(
    val sheet: Color,
    val strip: Color,
    val key: Color,
    val keyAlt: Color,
    val keyPress: Color,
    val ink: Color,
    val inkSoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val hairline: Color,
)

/**
 * Resolve the keyboard token set for a [direction] under the requested
 * [dark] mode.  Material You direction (id == [MATERIAL_YOU_DIRECTION_ID])
 * routes to [materialYouTokens] on Android 12+; everything else (and
 * pre-Android 12) uses the static [tokens] palette mapping.
 */
fun resolveTokens(direction: Direction, dark: Boolean, context: Context): KeyboardTokens {
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (direction.id == MATERIAL_YOU_DIRECTION_ID && dynamicSupported) {
        return materialYouTokens(context, dark)
    }
    return tokens(direction.palette, dark)
}

/**
 * Map Android 12+ dynamic [androidx.compose.material3.ColorScheme] (the
 * system wallpaper-derived palette) onto our [KeyboardTokens].  Slot
 * choices:
 *   - `sheet` ← surface (the keyboard chrome)
 *   - `strip` ← surfaceVariant (candidate strip — a hair off the chrome)
 *   - `key` ← surfaceContainerHigh / surface (regular key bg)
 *   - `keyAlt` ← surfaceContainerHighest (function-row keys)
 *   - `keyPress` ← primaryContainer (pressed-state highlight)
 *   - `accent` ← primary (Enter / accent buttons)
 *   - `onAccent` ← onPrimary
 *   - `ink` / `inkSoft` ← onSurface / onSurfaceVariant
 *   - `hairline` ← outlineVariant
 */
private fun materialYouTokens(context: Context, dark: Boolean): KeyboardTokens {
    val cs = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return KeyboardTokens(
        sheet = cs.surface,
        strip = cs.surfaceVariant,
        key = if (dark) cs.surfaceContainerHigh else cs.surface,
        keyAlt = if (dark) cs.surfaceContainerHighest else cs.surfaceVariant,
        keyPress = cs.primaryContainer,
        ink = cs.onSurface,
        inkSoft = cs.onSurfaceVariant,
        accent = cs.primary,
        accentSoft = cs.primaryContainer,
        onAccent = cs.onPrimary,
        hairline = cs.outlineVariant,
    )
}

// Direct port of the JSX `tokens(palette, dark)` function from
// the Claude Design handoff (korjapime/project/keyboards.jsx).
fun tokens(palette: DirectionPalette, dark: Boolean): KeyboardTokens {
    val hue = palette.hue
    val cAccent = if (palette.desat) 0.02f else 0.12f
    val cAccentSoft = if (palette.desat) 0.015f else 0.08f
    val cFaint = if (palette.desat) 0.005f else 0.015f
    val cVeryFaint = if (palette.desat) 0.003f else 0.01f
    val cKeyAlt = if (palette.desat) 0.005f else 0.025f

    return if (!dark) {
        KeyboardTokens(
            sheet = oklch(0.97f, cFaint, hue),
            strip = oklch(0.985f, cVeryFaint, hue),
            key = Color.White,
            keyAlt = oklch(0.93f, cKeyAlt, hue),
            keyPress = oklch(0.88f, cAccentSoft, hue),
            ink = oklch(0.22f, 0.01f, hue),
            inkSoft = oklch(0.45f, 0.01f, hue),
            accent = oklch(0.55f, cAccent, hue),
            accentSoft = oklch(0.92f, cAccentSoft, hue),
            onAccent = oklch(0.99f, 0f, hue),
            hairline = oklch(0.85f, 0.01f, hue),
        )
    } else {
        val cKeyDark = if (palette.desat) 0.008f else 0.025f
        val cKeyAltDark = if (palette.desat) 0.006f else 0.02f
        KeyboardTokens(
            sheet = oklch(0.18f, cFaint, hue),
            strip = oklch(0.16f, if (palette.desat) 0.003f else 0.012f, hue),
            key = oklch(0.27f, cKeyDark, hue),
            keyAlt = oklch(0.22f, cKeyAltDark, hue),
            keyPress = oklch(0.36f, cAccentSoft, hue),
            ink = oklch(0.95f, 0.005f, hue),
            inkSoft = oklch(0.72f, 0.01f, hue),
            accent = oklch(0.78f, cAccent, hue),
            accentSoft = oklch(0.32f, cAccentSoft, hue),
            onAccent = oklch(0.15f, 0.01f, hue),
            hairline = oklch(0.32f, 0.01f, hue),
        )
    }
}
