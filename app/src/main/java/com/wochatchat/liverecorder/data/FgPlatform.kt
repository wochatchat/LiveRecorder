/*
 * FgPlatform — V3-4 R2：前台平台探测纯逻辑。
 *
 * 前台包名 → 平台键映射（与 PLATFORM_COLORS / PLATFORM_LABELS 键系一致）；
 * UsageStatsManager 查询与「使用情况访问权限」检查依赖 Context，留在 FloatingBallService / SettingsScreen。
 */
package com.wochatchat.liverecorder.data

/** 支持前台探测的直播/短视频 App 包名 → 平台键。 */
internal val PLATFORM_PACKAGES: Map<String, String> = mapOf(
    "com.ss.android.ugc.aweme" to "douyin", // 抖音
    "com.smile.gifmaker" to "kuaishou", // 快手
    "com.xingin.xhs" to "xiaohongshu", // 小红书
    "tv.danmaku.bili" to "bilibili", // B站
    "com.duowan.kiwi" to "huya", // 虎牙
    "air.tv.douyu.android" to "douyu", // 斗鱼
    "com.duowan.mobile" to "yy", // YY
)

/** 前台平台探测纯函数集（便于单测）。 */
object FgPlatform {

    /** 前台包名 → 平台键；非支持平台返回空串。 */
    fun platformForPackage(pkg: String): String =
        PLATFORM_PACKAGES[pkg?.trim().orEmpty()].orEmpty()

    /**
     * 前台为支持平台时的 Cookie 状态码（面板行文案由 UI 层映射资源字符串）：
     * - "expired"：健康度已标记失效（❌）
     * - "ok"：已配置且健康度正常/未标记（✅ / ⏳ 归并为已配置）
     * - "none"：未配置
     */
    fun cookieStatus(configured: Boolean, healthStatus: String): String = when {
        !configured -> "none"
        healthStatus == "expired" -> "expired"
        else -> "ok"
    }
}
