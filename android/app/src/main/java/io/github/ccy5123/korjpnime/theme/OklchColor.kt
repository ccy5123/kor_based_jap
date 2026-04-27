package io.github.ccy5123.korjpnime.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// OKLCH (L 0..1, C 0..~0.4, H 0..360°) -> linear sRGB -> sRGB Color.
// Matches CSS oklch() so the design's parametric tokens port verbatim.
// @MX:NOTE: [AUTO] Ported from Björn Ottosson's OKLab spec (https://bottosson.github.io/posts/oklab/)
// because the Claude Design handoff (korjapime/project/keyboards.jsx) builds every token
// from a single OKLCH hue. Hardcoding hex per direction would lose the parametric system.
fun oklch(l: Float, c: Float, hDeg: Float): Color {
    val hRad = hDeg * (Math.PI.toFloat() / 180f)
    val a = c * cos(hRad)
    val b = c * sin(hRad)

    // OKLab -> LMS (cube)
    val lp = l + 0.3963377774f * a + 0.2158037573f * b
    val mp = l - 0.1055613458f * a - 0.0638541728f * b
    val sp = l - 0.0894841775f * a - 1.2914855480f * b

    val ls = lp * lp * lp
    val ms = mp * mp * mp
    val ss = sp * sp * sp

    // LMS -> linear sRGB
    var r =  4.0767416621f * ls - 3.3077115913f * ms + 0.2309699292f * ss
    var g = -1.2684380046f * ls + 2.6097574011f * ms - 0.3413193965f * ss
    var bl = -0.0041960863f * ls - 0.7034186147f * ms + 1.7076147010f * ss

    r = linearToSrgb(r).coerceIn(0f, 1f)
    g = linearToSrgb(g).coerceIn(0f, 1f)
    bl = linearToSrgb(bl).coerceIn(0f, 1f)

    return Color(r, g, bl, 1f)
}

private fun linearToSrgb(x: Float): Float =
    if (x <= 0.0031308f) 12.92f * x
    else 1.055f * x.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

@Suppress("unused")
private fun srgbToLinear(x: Float): Float =
    if (x <= 0.04045f) x / 12.92f
    else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

// cbrt is in kotlin.math but kept here for parity with reference impls.
@Suppress("unused")
private fun cubeRoot(x: Float): Float = cbrt(x)
