package com.wochatchat.liverecorder.platform

import java.util.regex.Pattern

/**
 * Phase 2：从分享文本（分享面板 / 剪贴板）中提取第一个受支持的直播 URL。
 * 纯逻辑函数，无 Android 依赖，可直接单元测试。
 */
object UrlExtractor {

    /** 匹配形如 "xxx.example.com/path" 的域名+路径片段（含协议或不带）。 */
    private val DOMAIN_PATH_RE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9\\-.]+/\\S*")

    /**
     * 从文本中提取第一个受支持的平台直播 URL。
     *
     * 优先级：
     * 1. 整个文本 trim 后就是格式良好的 URL 且被支持
     * 2. 分词后各片段（以 https:// 开头）取第一个受支持的
     * 3. 回退扫描：去掉已知中文前缀，匹配域名路径（含不带协议的 domain/path）
     *
     * @param text 分享文本或剪贴板内容，可能含标题、说明文字
     * @return 第一个受支持的平台 URL，或 null
     */
    fun extractSupported(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val trimmed = text.trim()

        // 1. 整个文本就是单个 URL（无空白 + 以协议开头）。
        //    含空白时跳过（isSupported 用 contains 子串匹配，多 URL 拼接段会误判）
        if (!trimmed.contains(Regex("\\s")) && looksLikeUrl(trimmed) && PlatformRouter.isSupported(trimmed)) {
            return stripTrailingPunct(trimmed)
        }

        // 2. 分词后各片段单独判断（分享文本中 URL 通常有空格分隔）
        for (segment in trimmed.split(Regex("\\s+"))) {
            if (looksLikeUrl(segment)) {
                val candidate = stripTrailingPunct(segment)
                if (PlatformRouter.isSupported(candidate)) return candidate
            }
        }

        // 3. 扫描 + 修复：处理微信分享等不带 https:// 前缀的场景
        //    先去掉已知中文前缀（如「【直播】抖音直播间 」），再匹配域名路径
        val preprocessed = stripLeadingChinesePrefix(text)
        val matcher = DOMAIN_PATH_RE.matcher(preprocessed)
        while (matcher.find()) {
            val raw = stripTrailingPunct(matcher.group())
            // 带协议的完整 URL
            if (looksLikeUrl(raw) && PlatformRouter.isSupported(raw)) return raw
            // 不带协议前缀的 domain/path（补上 https:// 后验证）
            if (!looksLikeUrl(raw) && raw.contains('.')) {
                val withProtocol = "https://$raw"
                if (PlatformRouter.isSupported(withProtocol)) return withProtocol
            }
        }

        return null
    }

    /** 字符串是否形似完整 URL（以 https:// 或 http:// 开头）。 */
    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("https://") || s.startsWith("http://")

    /**
     * 去掉文本开头的常见中文标签前缀（微信/微博分享场景）。
     * 例如「【直播】抖音直播间 live.douyin.com/123456」→「live.douyin.com/123456 快来！」
     * （第一个非 ASCII、非数字字符前的部分为 URL 起点；返回原文供 DOMAIN_PATH_RE 扫描）
     */
    private fun stripLeadingChinesePrefix(text: String): String {
        var result = text.trimStart()
        while (result.isNotEmpty() &&
            result[0] !in 'a'..'z' && result[0] !in 'A'..'Z' &&
            result[0] !in '0'..'9'
        ) {
            result = result.drop(1).trimStart()
        }
        return result
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
