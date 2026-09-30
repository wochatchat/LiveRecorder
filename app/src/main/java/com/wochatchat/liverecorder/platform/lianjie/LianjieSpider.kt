package com.wochatchat.liverecorder.platform.lianjie

import com.wochatchat.liverecorder.net.LiveHttpClient

/**
 * LianjieSpider — Phase 9f：连接直播爬虫。
 * 对照上游 spider.py:3278 get_lianjie_stream_url。
 *
 * 主流程：
 * - roomId = URL 路径末段（lailianjie.com/ 之后）
 * - GET https://api.lailianjie.com/ApiServices/service/live/getRoomInfo?roomNumber={roomId}
 *   → data.nickname / data.isonline / data.videoUrl
 * - isLive = isonline == 1
 * - videoUrl webrtc:// → https:// + .flv? / .m3u8? 变换
 */
open class LianjieSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
    }

    data class LianjieStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    /** roomId 为 lailianjie.com/ 后的路径段。 */
    fun parseRoomId(url: String): String =
        url.split("?").first().substringAfter("lailianjie.com/").substringAfterLast("/")

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): LianjieStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return LianjieStreamInfo()
        val headers = buildMap {
            put("User-Agent", UA)
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val apiUrl = "https://api.lailianjie.com/ApiServices/service/live/getRoomInfo" +
            "?&_$t=&_sign=&roomNumber=$roomId"
        val resp = runCatching { c.get(apiUrl, headers) }.getOrNull()
            ?: return LianjieStreamInfo()
        val json = runCatching { org.json.JSONObject(resp.text) }.getOrNull()
            ?: return LianjieStreamInfo()
        val data = json.optJSONObject("data") ?: return LianjieStreamInfo()
        val anchorName = data.optString("nickname", "")
        val isOnline = data.optInt("isonline", 0)
        if (isOnline != 1) return LianjieStreamInfo(anchorName = anchorName)
        val videoUrl = data.optString("videoUrl", "")
        if (videoUrl.isBlank()) return LianjieStreamInfo(anchorName = anchorName)
        val httpsUrl = "https://${videoUrl.removePrefix("webrtc://")}"
        val flvUrl = httpsUrl.replace("?", ".flv?")
        val m3u8Url = httpsUrl.replace("?", ".m3u8?")
        return LianjieStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = flvUrl,
            m3u8Url = m3u8Url,
            recordUrl = flvUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}