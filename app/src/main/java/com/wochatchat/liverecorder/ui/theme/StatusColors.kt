package com.wochatchat.liverecorder.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Phase 9-9.3：用户自定义状态色（录制中/异常/离线）。
 * 字段为 null = 跟随语义默认色（由使用处在组合期从 MaterialTheme 取，保证深浅色自适应）。
 */
data class StatusColors(
    val recording: Color? = null,
    val error: Color? = null,
    val offline: Color? = null,
)

/** Phase 9-9.3：状态色组合局部，主题根部提供，全站徽标统一读取。 */
val LocalStatusColors = staticCompositionLocalOf { StatusColors() }

/** Phase 9-9.3 状态色预设（hex 字符串用于持久化，键与 9.1 主题色约定一致）。 */
val STATUS_COLOR_PRESETS: Map<String, String> = linkedMapOf(
    "red" to "0xFFD32F2F",
    "blue" to "0xFF1976D2",
    "green" to "0xFF388E3C",
    "purple" to "0xFF7B1FA2",
    "orange" to "0xFFF57C00",
)

/**
 * 解析持久化的状态色字符串（纯函数便于单测）。
 * 支持 "0xFFD32F2F" / "#D32F2F" / "D32F2F"（6 位自动补不透明 alpha）；
 * 空串/非法/位数不对返回 null（= 走语义默认色）。
 */
fun parseStatusColor(hex: String): Color? {
    val s = hex.trim().removePrefix("0x").removePrefix("0X").removePrefix("#")
    if (s.isEmpty()) return null
    val argb = if (s.length == 6) "FF$s" else s
    if (argb.length != 8) return null
    val v = argb.toLongOrNull(16) ?: return null
    return Color(v)
}
