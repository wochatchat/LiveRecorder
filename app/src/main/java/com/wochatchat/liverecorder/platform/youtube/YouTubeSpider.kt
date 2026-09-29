package com.wochatchat.liverecorder.platform.youtube

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * YouTubeSpider — Phase 9a：YouTube 直播爬虫。
 * 对照上游 spider.py:3002 get_youtube_stream_url。
 *
 * 主流程：
 * - GET 页面 → 正则提取 `var ytInitialPlayerResponse = {...};var meta=...`
 * - videoDetails.isLive（需要 cookie，无 cookie 返回请登录提示）→ streamingData.hlsManifestUrl
 * record_url = m3u8（上游 get_play_url_list 解析切片列表，录制层直接吃 master m3u8）。
 *
 * 移动端差异：
 * - 上游无 cookie 时 print 提示"请在 config.ini 配置 cookies"；安卓端防御性返回未开播。
 * - m3u8 不走 get_play_url_list 直接作为 recordUrl（ffmpeg 原生支持 master playlist）。
 */
open class YouTubeSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** ytInitialPlayerResponse 正则（spider.py:3021，原文无 DOTALL 修饰符，但 JSON 内含换行需 DOTALL）。 */
        private val RE_PLAYER_RESPONSE = Pattern.compile(
            "var ytInitialPlayerResponse = (.*?);var meta = document\\.createElement",
            Pattern.DOTALL,
        )

        /** URL 末段 = videoId（上游 url.split('?')[0].rsplit('/')[-1]）。 */
        fun parseVideoId(url: String): String {
            // youtu.be/xxx 或 youtube.com/xxx?v=xxx
            val withoutQuery = url.split("?").first().trimEnd('/')
            return if (withoutQuery.contains("youtu.be/")) {
                withoutQuery.substringAfterLast("/")
            } else {
                // youtube.com/live/xxx 或 youtube.com/watch?v=xxx
                url.split("?").associate {
                    it.split("=").let { parts -> parts.getOrNull(0) to parts.drop(1).joinToString("=") }
                }.entries.find { it.key == "v" || it.key == "live" }?.value ?: withoutQuery.substringAfterLast("/")
            }
        }
    }

    data class YouTubeStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): YouTubeStreamInfo {
        val headers = buildMap {
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("User-Agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val html = try {
            clientOrProxy(proxyAddr).get(url, headers).text
        } catch (e: Exception) {
            return YouTubeStreamInfo()
        }
        val jsonStr = RE_PLAYER_RESPONSE.find(html)?.groupValues?.get(1)
            ?: return YouTubeStreamInfo()
        val json = runCatching { JSONObject(jsonStr) }.getOrNull()
            ?: return YouTubeStreamInfo()
        // videoDetails 缺失 = 未登录无法访问（上游打印错误提示）
        val videoDetails = json.optJSONObject("videoDetails") ?: return YouTubeStreamInfo()
        val anchorName = videoDetails.optString("author", "")
        val isLive = videoDetails.optBoolean("isLive", false)
        if (!isLive) return YouTubeStreamInfo(anchorName = anchorName)
        val m3u8Url = json.optJSONObject("streamingData")?.optString("hlsManifestUrl", "").orEmpty()
        if (m3u8Url.isEmpty()) return YouTubeStreamInfo(anchorName = anchorName)
        return YouTubeStreamInfo(
            anchorName = anchorName,
            title = videoDetails.optString("title", ""),
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}