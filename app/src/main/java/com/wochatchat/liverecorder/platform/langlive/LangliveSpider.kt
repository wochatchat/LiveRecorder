package com.wochatchat.liverecorder.platform.langlive

import com.wochatchat.liverecorder.net.LiveHttpClient

/**
 * LangliveSpider — Phase 9e：浪Live 直播爬虫。
 * 对照上游 spider.py:2846 get_langlive_stream_url。
 *
 * 主流程：
 * - roomId = URL 尾段 rsplit('/',1)[-1]
 * - GET api.lang.live/langweb/v1/room/liveinfo?room_id={roomId}
 *   （headers: origin/referer = https://www.lang.live, abroad=True）
 *   → data.live_info.nickname（主播名）
 *   → data.live_info.live_status（1=直播）
 *   → data.live_info.liveurl（flv）+ liveurl_hls（m3u8）
 * - isLive = live_status == 1，recordUrl = m3u8Url
 */
open class LangliveSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        fun parseRoomId(url: String): String =
            url.split("?").first().substringAfterLast("/")
    }

    data class StreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): StreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return StreamInfo()
        val headers = buildMap {
            put("origin", "https://www.lang.live")
            put("referer", "https://www.lang.live/")
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        val resp = runCatching {
            c.get("https://api.lang.live/langweb/v1/room/liveinfo?room_id=$roomId", headers)
        }.getOrNull() ?: return StreamInfo()
        val json = org.json.JSONObject(resp.text)
        val liveInfo = json.optJSONObject("data")?.optJSONObject("live_info") ?: return StreamInfo()

        val anchorName = liveInfo.optString("nickname", "")
        val liveStatus = liveInfo.optInt("live_status", 0)
        if (liveStatus != 1) return StreamInfo(anchorName = anchorName)

        val flvUrl = liveInfo.optString("liveurl", "")
        val m3u8Url = liveInfo.optString("liveurl_hls", "")
        return StreamInfo(anchorName = anchorName, isLive = true, m3u8Url = m3u8Url, flvUrl = flvUrl, recordUrl = m3u8Url)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
