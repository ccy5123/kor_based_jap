package io.github.ccy5123.korjpnime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.keyboard.KeyboardSurface
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.KeyboardMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnableImeScreen(
                        onOpenInputSettings = {
                            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnableImeScreen(onOpenInputSettings: () -> Unit) {
    val direction = DIRECTIONS.first()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "한국어 → 日本語",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "KorJpnIme · v0.1.0-alpha",
            fontSize = 13.sp,
            color = Color(0xFF666666),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "설정 → 시스템 → 언어 및 입력 → 화면 키보드 에서 KorJpnIme 를 활성화하세요.",
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenInputSettings) {
            Text("입력 방식 설정 열기")
        }
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Preview · ${direction.name}",
            fontSize = 11.sp,
            color = Color(0xFF888888),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .background(Color(0xFFF0EEE9), shape = RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            KeyboardSurface(
                direction = direction,
                dark = false,
                mode = KeyboardMode.BEOLSIK,
            )
        }
    }
}
