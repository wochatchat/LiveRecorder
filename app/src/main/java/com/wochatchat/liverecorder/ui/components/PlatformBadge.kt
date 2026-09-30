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
    "custom" to "自定义",
    "netease" to "网易CC",
    "baidu" to "百度",
    "weibo" to "微博",
    "jd" to "京东",
    "zhihu" to "知乎",
    "haixiu" to "嗨秀",
    "lehaitv" to "乐嗨",
    "laixiu" to "来秀",
    "liveme" to "LiveMe",
    "taobao" to "淘宝",
    "shopee" to "Shopee",
    "chzzk" to "CHZZK",
    "acfun" to "AcFun",
    "huajiao" to "花椒",
    "liuxing" to "流星",
    "inke" to "映客",
    "yinbo" to "音播",
    "soop" to "SOOP",
    "pandatv" to "PandaTV",
    "winktv" to "WinkTV",
    "flextv" to "FlexTV",
    "popkontv" to "PopkonTV",
    "maoerfm" to "猫耳FM",
    "kugou" to "酷狗",
    "changliao" to "畅聊",
    "vvxqiu" to "VV星球",
    "live17" to "17Live",
    "langlive" to "浪Live",
    "pplive" to "漂漂",
    "liujianfang" to "六间房",
    "lianjie" to "连接",
    "qiandurebo" to "千度热播",
    "showroom" to "ShowRoom",
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
    "custom" to Color(0xFF607D8B),
    "netease" to Color(0xFF0C8E5D),
    "baidu" to Color(0xFF2932E1),
    "weibo" to Color(0xFFE6162D),
    "jd" to Color(0xFFE2231A),
    "zhihu" to Color(0xFF0084FF),
    "haixiu" to Color(0xFFFF5B8C),
    "lehaitv" to Color(0xFFFF9800),
    "laixiu" to Color(0xFF7C4DFF),
    "liveme" to Color(0xFF21C2F8),
    "taobao" to Color(0xFFFF5000),
    "shopee" to Color(0xFFEE4D2D),
    "chzzk" to Color(0xFF03C75A),
    "acfun" to Color(0xFFFD4C5B),
    "huajiao" to Color(0xFFFF9800),
    "liuxing" to Color(0xFF9C27B0),
    "inke" to Color(0xFF4CAF50),
    "yinbo" to Color(0xFF2196F3),
    "soop" to Color(0xFFFF5722),
    "pandatv" to Color(0xFF2196F3),
    "winktv" to Color(0xFFFF9800),
    "flextv" to Color(0xFF9C27B0),
    "popkontv" to Color(0xFF03A9F4),
    "maoerfm" to Color(0xFFFF6B6B),
    "kugou" to Color(0xFF00D2FF),
    "changliao" to Color(0xFF4CAF50),
    "vvxqiu" to Color(0xFFFF9800),
    "live17" to Color(0xFF00E5CC),
    "langlive" to Color(0xFFFF6D00),
    "pplive" to Color(0xFF00BFA5),
    "liujianfang" to Color(0xFFFF4081),
    "lianjie" to Color(0xFF2196F3),
    "qiandurebo" to Color(0xFFFF9800),
    "showroom" to Color(0xFF9C27B0),
)