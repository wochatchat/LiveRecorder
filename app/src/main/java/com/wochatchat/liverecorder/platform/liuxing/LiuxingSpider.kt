package com.wochatchat.liverecorder.platform.liuxing

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * LiuxingSpider — Phase 9b：流星直播爬虫。
 * 对照上游 spider.py:2400 get_liuxing_stream_url。
 *
 * 主流程：
 * - room_id = URL 末段（7u66.com/{room_id}）
 * - GET wap.7u66.com/api/ui/room/v1.0.0/live.ashx?roomidx={room_id}&currentUrl=...
 * - roomInfo.live_stat==1 → flv = txpull1.5see.com/live/{idx}/{liveId1}.flv
 * record_url = flv（上游同语义）。
 */
open class LiuxingSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val API_UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        /** URL 末段 = room_id（上游 url.split('?')[0].rsplit('/', maxsplit=1)[1]）。 */
        fun parseRoomId(url: String): String =
            url.split("?").first().substringAfterLast("/")
    }

    data class LiuxingStreamInfo(
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
    ): LiuxingStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return LiuxingStreamInfo()
        val headers = buildMap {
            put("Accept", "application/json, text/plain, */*")
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("Referer", "https://wap.7u66.com/198189?promoters=0")
            put("User-Agent", API_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val params = mapOf(
            "promoters" to "0",
            "roomidx" to roomId,
            "currentUrl" to "https://www.7u66.com/$roomId?promoters=0",
        )
        val api = "https://wap.7u66.com/api/ui/room/v1.0.0/live.ashx?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        val resp = try {
            clientOrProxy(proxyAddr).get(api, headers)
        } catch (e: Exception) {
            return LiuxingStreamInfo()
        }
        val roomInfo = runCatching { JSONObject(resp.text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("roomInfo")
            ?: return LiuxingStreamInfo()
        val anchorName = roomInfo.optString("nickname", "")
        if (roomInfo.optInt("live_stat", 0) != 1) {
            return LiuxingStreamInfo(anchorName = anchorName)
        }
        val idx = roomInfo.optString("idx", "")
        val liveId = roomInfo.optString("liveId1", "")
        if (idx.isEmpty() || liveId.isEmpty()) return LiuxingStreamInfo(anchorName = anchorName)
        val flvUrl = "https://txpull1.5see.com/live/$idx/$liveId.flv"
        return LiuxingStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = flvUrl,
            recordUrl = flvUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
