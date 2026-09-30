package com.wochatchat.liverecorder.platform.blued

import com.wochatchat.liverecorder.net.LiveHttpClient
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * BluedSpider — Phase 9g：Blued 直播爬虫。
 * 对照上游 spider.py:876 get_blued_stream_url。
 *
 * 主流程：
 * - GET URL（含 app.blued.cn）with iOS UA
 * - HTML 中 decodeURIComponent("...") 块 URL 解码 → JSON
 * - JSON.userInfo.name / .onLive + .liveInfo.liveUrl（m3u8）
 */
open class BluedSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_DATA = Pattern.compile(
            "decodeURIComponent\\(\"(.+?)\"\\)\\),window\\.Promise",
            Pattern.DOTALL
        )
        private const val UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        fun parseRoomId(url: String): String =
            url.split("?").first().substringAfterLast("/")
    }

    data class BluedStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): BluedStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return BluedStreamInfo()
        val headers = buildMap {
            put("User-Agent", UA)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val resp = runCatching { c.get(url, headers) }.getOrNull()
            ?: return BluedStreamInfo()
        val encoded = RE_DATA.toRegex().find(resp.text)?.groupValues?.get(1) ?: ""
        if (encoded.isBlank()) return BluedStreamInfo()
        val jsonStr = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault("")
        if (jsonStr.isBlank()) return BluedStreamInfo()
        val json = runCatching { org.json.JSONObject(jsonStr) }.getOrNull()
            ?: return BluedStreamInfo()
        val userInfo = json.optJSONObject("userInfo") ?: return BluedStreamInfo()
        val anchorName = userInfo.optString("name", "")
        val isLive = userInfo.optInt("onLive", 0) == 1
        if (!isLive) return BluedStreamInfo(anchorName = anchorName)
        val liveInfo = json.optJSONObject("liveInfo") ?: return BluedStreamInfo(anchorName = anchorName, isLive = true)
        val m3u8Url = liveInfo.optString("liveUrl", "")
        if (m3u8Url.isBlank()) return BluedStreamInfo(anchorName = anchorName, isLive = true)
        return BluedStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}