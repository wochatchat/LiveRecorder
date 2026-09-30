package com.wochatchat.liverecorder.platform.live17

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Live17Spider — Phase 9e：17Live 直播爬虫。
 * 对照上游 spider.py:2816 get_17live_stream_url。
 *
 * 主流程：
 * - roomId = URL 尾段 rsplit('/',1)[-1]
 * - GET wap-api.17app.co/api/v1/user/room/{roomId} → displayName
 * - POST wap-api.17app.co/api/v1/lives/{roomId}/viewers/alive
 *   （body: {"liveStreamID": roomId}）→ status（2=直播）+ pullURLsInfo.rtmpURLs[0].urlHighQuality
 * - isLive = status == 2，recordUrl = flvUrl
 */
open class Live17Spider(
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
            put("origin", "https://17.live")
            put("referer", "https://17.live/")
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        // 获取主播名
        val anchorName = runCatching {
            val resp = c.get("https://wap-api.17app.co/api/v1/user/room/$roomId", headers)
            org.json.JSONObject(resp.text).optString("displayName", "")
        }.getOrDefault("")

        // 查询开播状态 + 流地址
        val liveBody = "{\"liveStreamID\":\"$roomId\"}"
        val jsonBody = liveBody.toRequestBody("application/json".toMediaType())
        val liveJson = runCatching {
            c.post("https://wap-api.17app.co/api/v1/lives/$roomId/viewers/alive", headers, jsonBody)
        }.getOrNull() ?: return StreamInfo(anchorName = anchorName)

        val json = org.json.JSONObject(liveJson.text)
        val status = json.optInt("status", 0)
        if (status != 2) return StreamInfo(anchorName = anchorName)

        val flvUrl = json.optJSONObject("pullURLsInfo")
            ?.optJSONArray("rtmpURLs")
            ?.optJSONObject(0)
            ?.optString("urlHighQuality", "") ?: ""
        return StreamInfo(anchorName = anchorName, isLive = true, flvUrl = flvUrl, recordUrl = flvUrl)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}