package com.wochatchat.liverecorder.data

/**
 * 代理设置（Phase 4a，对齐上游 config.ini「是否使用代理ip / 代理地址 / 使用代理录制的平台」）。
 *
 * 判定语义对齐上游 main.py:558-573：开启代理后仅当 URL 命中平台关键词才走代理
 * （proxy_address 先置 None，URL 含 enable_proxy_platform_list 中任一关键词才用 proxy_addr）。
 *
 * 与上游的差异（移动端简化，见 CHANGELOG 4a）：
 * - 不做 extra_enable_proxy_platform_list / proxy_addr_bak 备用代理（上游备用地址与主地址同源）
 * - 不做系统代理检测（global_proxy / ProxyDetector）：Android 端 HTTP 层默认 NO_PROXY，
 *   海外平台一律走显式配置的代理地址
 * - ffmpeg 分段录制不经代理（上游 subprocess 调 ffmpeg 同样不走代理）
 */
data class ProxySettings(
    /** 是否使用代理（上游「是否使用代理ip(是/否)」）。 */
    val enabled: Boolean = false,
    /** 代理地址：http://host:port / https://host:port / socks5://host:port / host:port。 */
    val addr: String = "",
    /** 平台关键词列表（上游「使用代理录制的平台(逗号分隔)」，子串匹配 URL）。 */
    val platforms: List<String> = DEFAULT_PLATFORMS,
) {
    /**
     * 解析某条监控 URL 应使用的代理地址。
     * 未启用 / 地址为空 / URL 未命中任何平台关键词 → null（直连）。
     */
    fun resolveProxy(url: String): String? =
        if (!enabled || addr.isBlank()) null
        else if (platforms.any { it.isNotBlank() && url.contains(it.trim()) }) addr.trim() else null

    /** 平台关键词序列化为逗号分隔串（持久化 / UI 编辑用）。 */
    fun platformsCsv(): String = platforms.joinToString(",")

    companion object {
        /** 上游 config.ini「使用代理录制的平台」默认值（原样保留，含 youtu 前缀覆盖 youtube）。 */
        val DEFAULT_PLATFORMS = listOf(
            "tiktok", "sooplive", "pandalive", "winktv", "flextv", "popkontv",
            "twitch", "liveme", "showroom", "chzzk", "shopee", "shp", "youtu",
        )

        /** 从逗号分隔串解析平台关键词（中英文逗号均支持，空段丢弃）。 */
        fun parsePlatforms(csv: String, fallback: List<String> = DEFAULT_PLATFORMS): List<String> =
            csv.replace('，', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { fallback }
    }
}
