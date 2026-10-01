package com.wochatchat.liverecorder.platform.twitcasting

import com.wochatchat.liverecorder.net.LiveHttpClient
import java.util.regex.Pattern

/**
 * TwitcastingSpider — Phase 9g：TwitCasting 直播爬虫。
 * 对照上游 spider.py:1877 get_twitcasting_stream_url。
 *
 * 主流程：
 * - URL = https://twitcasting.tv/{anchorId}
 * - 阶段1：GET 房间页 → data-is-onlive / data-movie-id / title 提取
 * - 阶段2：开播时 GET streamserver.php?target={anchorId}&mode=client&player=pc_web
 *   → .tc-hls.streams.high/medium/low → m3u8（high 优先）
 * - 未开播（data-is-onlive="false"）→ isLive=false，anchorName 从 title 提取
 */
open class TwitcastingSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_IS_LIVE = Pattern.compile("data-is-onlive=\"(.*?)\"")
        private val RE_MOVIE_ID = Pattern.compile("data-movie-id=\"(.*?)\"")
        private val RE_TITLE = Pattern.compile(
            "<title>(.*?) \\(@(.*?)\\)" + "[ 　]{2,}\u7684\u76f4\u64ad - Twit",
        )
        // twitter:title 元标签：用于提取开播时的直播标题（content 值紧跟引号后换行或直接 </head>）
        private val RE_TW_TITLE = Pattern.compile(
            "<meta name=\"twitter:title\" content=\"([^\"]+)\"",
            Pattern.DOTALL,
        )
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        /** anchorId 为 URL 第 4 段（twitcasting.tv/{anchorId}）。 */
        fun parseRoomId(url: String): String {
            val path = url.split("?").first()
            val parts = path.split("/")
            return parts.getOrNull(3) ?: ""
        }
    }

    data class TwitcastingStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val title: String = "",
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): TwitcastingStreamInfo {
        val anchorId = parseRoomId(url)
        if (anchorId.isBlank()) return TwitcastingStreamInfo()
        val headers = buildMap {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("Referer", "https://twitcasting.tv/?ch0")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val pageResp = runCatching { c.get(url, headers) }.getOrNull()
            ?: return TwitcastingStreamInfo()
        val html = pageResp.text

        // 提取基本信息
        val isLiveStr = RE_IS_LIVE.toRegex().find(html)?.groupValues?.get(1) ?: "false"
        val movieId = RE_MOVIE_ID.toRegex().find(html)?.groupValues?.get(1) ?: ""
        val titleMatch = RE_TITLE.toRegex().find(html)
        val liveTitle = RE_TW_TITLE.toRegex().find(html)?.groupValues?.get(1)?.trim() ?: ""
        val anchorName = if (titleMatch != null) {
            "${titleMatch.groupValues[1].trim()}-${titleMatch.groupValues[2].trim()}-$movieId"
        } else ""

        if (isLiveStr != "true") {
            return TwitcastingStreamInfo(anchorName = anchorName, title = liveTitle)
        }

        // 开播：获取 m3u8 流地址
        val streamResp = runCatching {
            c.get("https://twitcasting.tv/streamserver.php?target=$anchorId&mode=client&player=pc_web", headers)
        }.getOrNull() ?: return TwitcastingStreamInfo(anchorName = anchorName, isLive = true, title = liveTitle)
        val streamJson = runCatching { org.json.JSONObject(streamResp.text) }.getOrNull()
            ?: return TwitcastingStreamInfo(anchorName = anchorName, isLive = true, title = liveTitle)
        val hls = streamJson.optJSONObject("tc-hls") ?: return TwitcastingStreamInfo(anchorName = anchorName, isLive = true, title = liveTitle)
        val streams = hls.optJSONObject("streams") ?: return TwitcastingStreamInfo(anchorName = anchorName, isLive = true, title = liveTitle)

        // 画质优先级：high > medium > low
        val m3u8Url = streams.optString("high", "") .ifEmpty {
            streams.optString("medium", "").ifEmpty {
                streams.optString("low", "")
            }
        }
        if (m3u8Url.isBlank()) return TwitcastingStreamInfo(anchorName = anchorName, isLive = true, title = liveTitle)
        return TwitcastingStreamInfo(
            anchorName = anchorName,
            isLive = true,
            title = liveTitle,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}