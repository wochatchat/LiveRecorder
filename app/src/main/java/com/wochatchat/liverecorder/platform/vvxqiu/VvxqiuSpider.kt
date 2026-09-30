package com.wochatchat.liverecorder.platform.vvxqiu

import com.wochatchat.liverecorder.net.LiveHttpClient

/**
 * VvxqiuSpider — Phase 9d：VV星球直播爬虫。
 * 对照上游 spider.py:2776 get_vvxqiu_stream_url。
 *
 * 主流程：
 * - roomId = URL query param (roomId=xxx)
 * - GET h5p.vvxqiu.com/activity-center/fanclub/activity/captain/banner?roomId={roomId}&product=vvstar
 *   → anchorName（主播名）
 * - anchorName 空时：GET h5p.vvxqiu.com/activity-center/halloween2023/banner?... → data.memberVO.memberName
 * - m3u8 URL 固定拼接：https://liveplay-pro.wasaixiu.com/live/1400442770_{roomId}_{roomId[2:]}_single.m3u8
 * - 探测 m3u8 有效性：GET m3u8 URL，响应不含 "Not Found" → isLive=true
 */
open class VvxqiuSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        private const val M3U8_BASE = "https://liveplay-pro.wasaixiu.com/live/"

        fun parseRoomId(url: String): String {
            val params = url.split("?").getOrNull(1) ?: return ""
            params.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv[0] == "roomId") return kv.getOrElse(1) { "" }
            }
            return ""
        }

        private fun buildM3u8Url(roomId: String): String {
            return "${M3U8_BASE}1400442770_${roomId}_${roomId.substring(2)}_single.m3u8"
        }
    }

    data class VvxqiuStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): VvxqiuStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank() || roomId.length < 3) return VvxqiuStreamInfo()
        val headers = buildMap {
            put("User-Agent", UA)
            put("Access-Control-Request-Method", "GET")
            put("Origin", "https://h5webcdn-pro.vvxqiu.com")
            put("Referer", "https://h5webcdn-pro.vvxqiu.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        // 第一次：captain/banner API
        val api1 = "https://h5p.vvxqiu.com/activity-center/fanclub/activity/captain/banner?roomId=$roomId&product=vvstar"
        var anchorName = runCatching {
            val resp = c.get(api1, headers)
            val json = org.json.JSONObject(resp.text)
            json.optJSONObject("data")?.optString("anchorName", "") ?: ""
        }.getOrDefault("")

        // anchorName 为空时用备选 API
        if (anchorName.isBlank()) {
            val api2 = "https://h5p.vvxqiu.com/activity-center/halloween2023/banner?" +
                "sessionId=&userId=&product=vvstar&tickToken=&roomId=$roomId"
            anchorName = runCatching {
                val resp = c.get(api2, headers)
                val json = org.json.JSONObject(resp.text)
                json.optJSONObject("data")?.optJSONObject("memberVO")?.optString("memberName", "") ?: ""
            }.getOrDefault("")
        }

        val m3u8Url = buildM3u8Url(roomId)
        val m3u8Resp = runCatching {
            c.get(m3u8Url, headers).text
        }.getOrNull() ?: ""
        if ("Not Found" in m3u8Resp) {
            return VvxqiuStreamInfo(anchorName = anchorName)
        }
        return VvxqiuStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}