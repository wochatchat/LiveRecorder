package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 平台文字徽标：显示平台名，颜色对应该平台主品牌色。
 * 优先级低于录制状态（录制中用红色徽标）；平台 Badge 用于离线/未开播/完成态。
 *
 * @param platformKey PlatformRouter.platformName(url) 返回的平台键（小写英文）
 * @param text 可选覆盖显示文本（默认用 [PLATFORM_LABELS] 映射）
 */
@Composable
fun PlatformBadge(
    platformKey: String,
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val label = text ?: PLATFORM_LABELS[platformKey.lowercase()] ?: platformKey
    val color = PLATFORM_COLORS[platformKey.lowercase()] ?: Color.Gray

    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** 平台显示名映射。 */
val PLATFORM_LABELS = mapOf(
    "douyin" to "抖音",
    "kuaishou" to "快手",
    "huya" to "虎牙",
    "douyu" to "斗鱼",
    "bilibili" to "B站",
    "yy" to "YY",
    "bigo" to "Bigo",
    "xiaohongshu" to "小红书",
    "tiktok" to "TikTok",
    "twitch" to "Twitch",
    "youtube" to "YouTube",
)

/** 平台主品牌色（Surface badge 背景色用 12% alpha）。 */
val PLATFORM_COLORS = mapOf(
    "douyin" to Color(0xFFFE2C55),
    "kuaishou" to Color(0xFF8632FF),
    "huya" to Color(0xFFFF6A0A),
    "douyu" to Color(0xFFFA3F5A),
    "bilibili" to Color(0xFF00A1D6),
    "yy" to Color(0xFFFF6600),
    "bigo" to Color(0xFF00AAFF),
    "xiaohongshu" to Color(0xFFFF2442),
    "tiktok" to Color(0xFF000000),
    "twitch" to Color(0xFF9146FF),
    "youtube" to Color(0xFFFF0000),
)