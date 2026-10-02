package com.wochatchat.liverecorder.platform

import java.util.regex.Pattern

/**
 * Phase 2：从分享文本（分享面板 / 剪贴板）中提取第一个受支持的直播 URL。
 * 纯逻辑函数，无 Android 依赖，可直接单元测试。
 */
object UrlExtractor {

    /** 匹配 https:// 或 http:// 开头的 URL（贪婪到空白或常见标点截止）。 */
    private val URL_RE = Pattern.compile("https?://\\S+")

    /**
     * 从文本中提取第一个受支持的平台直播 URL。
     * - 若整个文本（trimmed）本身就是一个格式良好的 URL 且被支持，返回它
     * - 否则用空白分词，从分词后的 URL 片段中取第一个受支持的
     * - 最后回落到扫描所有匹配正则的子串（处理无空格的嵌入 URL）
     *
     * @param text 分享文本或剪贴板内容，可能含标题、说明文字
     * @return 第一个受支持的平台 URL，或 null
     */
    fun extractSupported(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val trimmed = text.trim()

        // 1. 整个文本就是 URL（格式良好：以协议开头）
        if (looksLikeUrl(trimmed) && PlatformRouter.isSupported(trimmed)) {
            return stripTrailingPunct(trimmed)
        }

        // 2. 分词后各片段单独判断（分享文本中 URL 通常有空格分隔）
        for (segment in trimmed.split(Regex("\\s+"))) {
            if (looksLikeUrl(segment)) {
                val candidate = stripTrailingPunct(segment)
                if (PlatformRouter.isSupported(candidate)) return candidate
            }
        }

        // 3. 扫描文本中的 URL（处理无空格分隔的嵌入场景，如 "链接：https://...")
        val matcher = URL_RE.matcher(text)
        while (matcher.find()) {
            val candidate = stripTrailingPunct(matcher.group())
            if (looksLikeUrl(candidate) && PlatformRouter.isSupported(candidate)) return candidate
        }

        return null
    }

    /** 字符串是否形似完整 URL（以 https:// 或 http:// 开头）。 */
    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("https://") || s.startsWith("http://")

    /**
     * 去掉 URL 尾部常见标点符号（分享文本中常见）。
     * 例如 `https://live.douyin.com/123 )` → `https://live.douyin.com/123`
     */
    private fun stripTrailingPunct(url: String): String {
        var i = url.length - 1
        while (i >= 0 && url[i] in ".,;:!?。，；：！？)）]」』】}\"'·•›"  ) {
            i--
        }
        return if (i < url.length - 1) url.substring(0, i + 1) else url
    }
}