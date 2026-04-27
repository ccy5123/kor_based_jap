package io.github.ccy5123.korjpnime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ccy5123.korjpnime.data.KeyboardPreferences
import io.github.ccy5123.korjpnime.theme.DIRECTIONS
import io.github.ccy5123.korjpnime.theme.Direction
import io.github.ccy5123.korjpnime.theme.KeyboardMode
import io.github.ccy5123.korjpnime.theme.ThemeMode
import io.github.ccy5123.korjpnime.theme.tokens
import kotlinx.coroutines.launch

/**
 * Settings UI — covers everything wired through DataStore so far:
 *
 *  - **한글 키보드 레이아웃**: 두벌식 ↔ 천지인
 *  - **테마**: 5 design directions (Stratus / Persimmon / Hinoki /
 *    Slate / Iris) sourced from `theme/KeyboardTheme.kt`'s `DIRECTIONS`
 *  - **다크 모드**: 자동 / 라이트 / 다크
 *  - **진동** toggle (haptic feedback on key tap)
 *
 * The screen scrolls because direction + mode + dark + haptic together
 * exceed a typical phone's portrait viewport once the cards are sized
 * for tap targets.
 */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val mode by KeyboardPreferences.modeFlow(context)
        .collectAsState(initial = KeyboardMode.BEOLSIK)
    val directionId by KeyboardPreferences.directionFlow(context)
        .collectAsState(initial = KeyboardPreferences.DEFAULT_DIRECTION_ID)
    val themeMode by KeyboardPreferences.themeModeFlow(context)
        .collectAsState(initial = ThemeMode.AUTO)
    val haptics by KeyboardPreferences.hapticsFlow(context)
        .collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Header(onClose = onClose)

        Spacer(Modifier.height(24.dp))
        SectionLabel("한글 키보드 레이아웃")
        Spacer(Modifier.height(10.dp))

        ModeCard(
            selected = mode == KeyboardMode.BEOLSIK,
            title = "두벌식",
            subtitle = "표준 한글 자판 (QWERTY 배열)",
            preview = "ㅂㅈㄷㄱㅅ ㅛㅕㅑㅐㅔ",
            onClick = { scope.launch { KeyboardPreferences.setMode(context, KeyboardMode.BEOLSIK) } },
        )
        Spacer(Modifier.height(10.dp))
        ModeCard(
            selected = mode == KeyboardMode.CHEONJIIN,
            title = "천지인",
            subtitle = "3×4 자판 (multi-tap 입력)",
            preview = "ㄱㅋ  ㄴㄹ  ㄷㅌ\nㅁㅂㅍ  ㅅㅎ\nㅇㅈㅊ  ㅣ ㆍ ㅡ",
            onClick = { scope.launch { KeyboardPreferences.setMode(context, KeyboardMode.CHEONJIIN) } },
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("테마")
        Spacer(Modifier.height(10.dp))

        DirectionPicker(
            directionId = directionId,
            dark = (themeMode == ThemeMode.DARK),
            onSelect = { id ->
                scope.launch { KeyboardPreferences.setDirection(context, id) }
            },
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("다크 모드")
        Spacer(Modifier.height(10.dp))

        ThemeModeSegmented(
            current = themeMode,
            onChange = { newMode ->
                scope.launch { KeyboardPreferences.setThemeMode(context, newMode) }
            },
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("입력 피드백")
        Spacer(Modifier.height(10.dp))

        ToggleRow(
            title = "진동",
            subtitle = "키 탭마다 짧은 햅틱 피드백",
            checked = haptics,
            onChange = { scope.launch { KeyboardPreferences.setHaptics(context, it) } },
        )

        Spacer(Modifier.height(32.dp))
    }

    LaunchedEffect(Unit) { /* placeholder for future side effects */ }
}

@Composable
private fun DirectionPicker(
    directionId: String,
    dark: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (dir in DIRECTIONS) {
            DirectionCard(
                direction = dir,
                selected = dir.id == directionId,
                dark = dark,
                onClick = { onSelect(dir.id) },
            )
        }
    }
}

@Composable
private fun DirectionCard(
    direction: Direction,
    selected: Boolean,
    dark: Boolean,
    onClick: () -> Unit,
) {
    val tokens = tokens(direction.palette, dark)
    val borderColor = if (selected) tokens.accent else Color(0xFFE0E0E0)
    val borderWidth = if (selected) 2.dp else 1.dp

    Column(
        modifier = Modifier
            .width(108.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .background(tokens.sheet)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // Mini key sample — uses the direction's actual shape + colors so the
        // card itself previews what the keyboard will look like.
        val keyShape = when (direction.shape) {
            io.github.ccy5123.korjpnime.theme.KeyShape.PILL -> RoundedCornerShape(999.dp)
            io.github.ccy5123.korjpnime.theme.KeyShape.SQUIRCLE -> RoundedCornerShape(10.dp)
            io.github.ccy5123.korjpnime.theme.KeyShape.FLAT -> RoundedCornerShape(0.dp)
            io.github.ccy5123.korjpnime.theme.KeyShape.ROUNDED -> RoundedCornerShape(6.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 18.dp)
                        .clip(keyShape)
                        .background(if (it == 1) tokens.accent else tokens.key),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = direction.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = direction.palette.name,
            fontSize = 10.sp,
            color = tokens.inkSoft,
        )
    }
}

@Composable
private fun ThemeModeSegmented(
    current: ThemeMode,
    onChange: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF0F0F0))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((mode, label) in listOf(
            ThemeMode.AUTO to "자동",
            ThemeMode.LIGHT to "라이트",
            ThemeMode.DARK to "다크",
        )) {
            val selected = current == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) Color.White else Color.Transparent)
                    .clickable { onChange(mode) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color(0xFF222222) else Color(0xFF6B6B6B),
                )
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
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
            text = "키보드 설정",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF6B6B6B),
        letterSpacing = 0.5.sp,
    )
}

@Composable
private fun ModeCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    preview: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Color(0xFF3B82F6) else Color(0xFFE0E0E0)
    val bgColor = if (selected) Color(0xFFEFF6FF) else Color(0xFFFAFAFA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 13.sp, color = Color(0xFF6B6B6B))
            Spacer(Modifier.height(8.dp))
            Text(
                text = preview,
                fontSize = 12.sp,
                color = Color(0xFF9A9A9A),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFAFAFA))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(12.dp))
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 13.sp, color = Color(0xFF6B6B6B))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
