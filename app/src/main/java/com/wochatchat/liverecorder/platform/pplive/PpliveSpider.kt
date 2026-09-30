package com.wochatchat.liverecorder.platform.pplive

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * PpliveSpider — Phase 9e：漂漂直播 / 花猫 直播爬虫。
 * 对照上游 spider.py:2872 get_pplive_stream_url。
 *
 * 主流程：
 * - roomId = URL query param "anchorUid"（非路径）
 * - catshow 域名变体（h.catshow168.com）走 api.catshow168.com（花猫）
 * - 其余走 api.pp.weimipopo.com（漂漂）
 * - POST preview API（body: {"inviteUuid":"","anchorUuid":roomId}）
 *   → data.name（主播名）+ data.living（bool）+ data.pullUrl（m3u8）
 * - isLive = living == true，recordUrl = m3u8Url
 */
open class PpliveSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        fun parseRoomId(url: String): String {
            val params = url.split("?").getOrNull(1) ?: return ""
            params.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv[0] == "anchorUid") return kv.getOrElse(1) { "" }
            }
            return ""
        }
    }

    data class StreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): StreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return StreamInfo()

        val (api, origin, referer) = if (url.contains("catshow")) {
            Triple(
                "https://api.catshow168.com/live/preview",
                "https://h.catshow168.com",
                "https://h.catshow168.com",
            )
        } else {
            Triple(
                "https://api.pp.weimipopo.com/live/preview",
                "https://m.pp.weimipopo.com",
                "https://m.pp.weimipopo.com/",
            )
        }

        val headers = buildMap {
            put("Content-Type", "application/json")
            put("origin", origin)
            put("referer", referer)
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        val body = "{\"inviteUuid\":\"\",\"anchorUuid\":\"$roomId\"}"
        val jsonBody = body.toRequestBody("application/json".toMediaType())
        val resp = runCatching {
            c.post(api, headers, jsonBody)
        }.getOrNull() ?: return StreamInfo()

        val json = org.json.JSONObject(resp.text)
        val liveInfo = json.optJSONObject("data") ?: return StreamInfo()

        val anchorName = liveInfo.optString("name", "")
        val living = liveInfo.optBoolean("living", false)
        if (!living) return StreamInfo(anchorName = anchorName)

        val m3u8Url = liveInfo.optString("pullUrl", "")
        return StreamInfo(anchorName = anchorName, isLive = true, m3u8Url = m3u8Url, recordUrl = m3u8Url)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}