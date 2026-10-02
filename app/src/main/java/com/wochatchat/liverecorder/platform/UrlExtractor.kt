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
     * - 若文本本身（trim 后）就是有效 URL 且被支持，直接返回
     * - 否则扫描文本中所有 URL，取第一个受支持的
     * @param text 分享文本或剪贴板内容，可能含标题、说明文字
     * @return 第一个受支持的平台 URL，或 null
     */
    fun extractSupported(text: String?): String? {
        if (text.isNullOrBlank()) return null

        // 1. 直接完整文本（trim 后去掉首尾常见标点）
        val trimmed = text.trim().let { stripTrailingPunct(it) }
        if (PlatformRouter.isSupported(trimmed)) return trimmed

        // 2. 扫描文本中的 URL
        val matcher = URL_RE.matcher(text)
        while (matcher.find()) {
            val url = stripTrailingPunct(matcher.group())
            if (PlatformRouter.isSupported(url)) return url
        }

        return null
    }

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