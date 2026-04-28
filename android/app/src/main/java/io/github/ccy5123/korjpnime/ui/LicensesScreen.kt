package io.github.ccy5123.korjpnime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open-source license screen — reads the bundled
 * `assets/licenses/THIRD_PARTY_LICENSES.txt` (Mozc / NAIST IPAdic /
 * Okinawa Public-Domain Dictionary attribution) and renders it as
 * scrollable monospace text.  Required by the IPAdic license to
 * ship attribution alongside the dictionary data.
 *
 * Asset read happens off the main thread the first time the screen
 * is opened; the file is small (~7 KB) so this completes well within
 * the screen's first frame on any modern device.
 */
@Composable
fun LicensesScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        content = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/THIRD_PARTY_LICENSES.txt").use {
                    it.bufferedReader().readText()
                }
            }.getOrElse { e ->
                "라이선스 파일을 불러오지 못했습니다.\n\n${e.message}"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Header(title = "오픈소스 라이선스", onClose = onClose)
        Spacer(Modifier.height(16.dp))

        Text(
            text = "이 앱은 다음 오픈소스 프로젝트의 데이터를 사용하고 있으며, " +
                "각 프로젝트의 라이선스 조항에 따라 출처를 표기합니다.",
            fontSize = 13.sp,
            color = Color(0xFF6B6B6B),
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))

        // Render the bundled license file in monospace so the section
        // banners (`====` and `---`) line up.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFAFAFA))
                .padding(14.dp),
        ) {
            Text(
                text = content ?: "불러오는 중…",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF333333),
                lineHeight = 15.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(title: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "←",
            fontSize = 22.sp,
            color = Color(0xFF333333),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onClose)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
