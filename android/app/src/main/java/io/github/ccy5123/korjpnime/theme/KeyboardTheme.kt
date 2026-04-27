package io.github.ccy5123.korjpnime.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class KeyboardMode { BEOLSIK, CHEONJIIN }

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
)

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
