package com.wochatchat.liverecorder.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

/** Phase 9-9.1 主题色取值（与 AppSettings.themeColor 约定）。 */
const val THEME_SYSTEM = "system"

/** Phase 9-9.1：品牌色（浅色 primary，深色 primary）——深色用提亮变体保对比度。 */
private val BRAND_COLORS = mapOf(
    "red" to (Color(0xFFD32F2F) to Color(0xFFEF9A9A)),
    "blue" to (Color(0xFF1976D2) to Color(0xFF90CAF9)),
    "green" to (Color(0xFF388E3C) to Color(0xFFA5D6A7)),
    "purple" to (Color(0xFF7B1FA2) to Color(0xFFCE93D8)),
    "orange" to (Color(0xFFF57C00) to Color(0xFFFFCC80)),
)

/** Phase 9-9.1：按品牌色生成方案——primary 着色，其余保持 M3 baseline。 */
private fun brandScheme(key: String, darkTheme: Boolean): ColorScheme {
    val (light, dark) = BRAND_COLORS[key] ?: BRAND_COLORS.values.first()
    val primary = if (darkTheme) dark else light
    return if (darkTheme) {
        darkColorScheme(primary = primary, primaryContainer = primary.copy(alpha = 0.25f))
    } else {
        lightColorScheme(primary = primary, primaryContainer = primary.copy(alpha = 0.18f))
    }
}

/**
 * R8（U5）：应用级主题——Android 12+ 跟随系统动态取色，
 * 低版本回落 baseline 浅/深色方案，深色模式全量适配。
 *
 * Phase 9-9.1：[themeColor] 非 system 时使用固定品牌色（不再走动态取色）。
 * Phase 9-9.2：[amoledBlack] 开启且深色时，背景/表面覆盖为纯黑 #000000。
 * Phase 9-9.3：状态色非空时经 LocalStatusColors 提供给全站徽标。
 */
@Composable
fun LiveRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: String = THEME_SYSTEM,
    amoledBlack: Boolean = false,
    statusRecordingColor: String = "",
    statusErrorColor: String = "",
    statusOfflineColor: String = "",
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        themeColor != THEME_SYSTEM -> brandScheme(themeColor, darkTheme)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val finalScheme = if (darkTheme && amoledBlack) colorScheme.amoled() else colorScheme
    val statusColors = StatusColors(
        recording = parseStatusColor(statusRecordingColor),
        error = parseStatusColor(statusErrorColor),
        offline = parseStatusColor(statusOfflineColor),
    )
    MaterialTheme(colorScheme = finalScheme) {
        CompositionLocalProvider(LocalStatusColors provides statusColors, content = content)
    }
}

/** Phase 9-9.2：深色方案背景/表面层级覆盖为纯黑（OLED 省电）。 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
)
