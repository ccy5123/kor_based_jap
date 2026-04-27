package io.github.ccy5123.korjpnime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode

// ─── d1 Stratus ──────────────────────────────────────────────────

@Preview(name = "d1 Stratus · 두벌식 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewStratusBeolsikLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[0], dark = false, mode = KeyboardMode.BEOLSIK)
}

@Preview(name = "d1 Stratus · 두벌식 · dark", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFF111114)
@Composable
fun PreviewStratusBeolsikDark() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[0], dark = true, mode = KeyboardMode.BEOLSIK)
}

@Preview(name = "d1 Stratus · 천지인 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewStratusCheonjiinLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[0], dark = false, mode = KeyboardMode.CHEONJIIN)
}

@Preview(name = "d1 Stratus · 천지인 · dark", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFF111114)
@Composable
fun PreviewStratusCheonjiinDark() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[0], dark = true, mode = KeyboardMode.CHEONJIIN)
}

// ─── All 5 directions, beolsik light only (sanity matrix) ────────

@Preview(name = "d2 Persimmon · 두벌식 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewPersimmonBeolsikLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[1], dark = false, mode = KeyboardMode.BEOLSIK)
}

@Preview(name = "d3 Hinoki · 두벌식 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewHinokiBeolsikLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[2], dark = false, mode = KeyboardMode.BEOLSIK)
}

@Preview(name = "d4 Slate · 두벌식 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewSlateBeolsikLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[3], dark = false, mode = KeyboardMode.BEOLSIK)
}

@Preview(name = "d5 Iris · 두벌식 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewIrisBeolsikLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[4], dark = false, mode = KeyboardMode.BEOLSIK)
}

// ─── Cheonjiin all-directions strip (light only) ─────────────────

@Preview(name = "d2 Persimmon · 천지인 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewPersimmonCheonjiinLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[1], dark = false, mode = KeyboardMode.CHEONJIIN)
}

@Preview(name = "d3 Hinoki · 천지인 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewHinokiCheonjiinLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[2], dark = false, mode = KeyboardMode.CHEONJIIN)
}

@Preview(name = "d4 Slate · 천지인 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewSlateCheonjiinLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[3], dark = false, mode = KeyboardMode.CHEONJIIN)
}

@Preview(name = "d5 Iris · 천지인 · light", widthDp = 380, heightDp = 360, showBackground = true, backgroundColor = 0xFFE9E5DD)
@Composable
fun PreviewIrisCheonjiinLight() = PreviewFrame {
    KeyboardSurface(direction = DIRECTIONS[4], dark = false, mode = KeyboardMode.CHEONJIIN)
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFE9E5DD))
            .padding(8.dp),
    ) {
        Box(modifier = Modifier.width(360.dp)) { content() }
    }
}
