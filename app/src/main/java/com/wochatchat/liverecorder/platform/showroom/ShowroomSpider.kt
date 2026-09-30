package com.wochatchat.liverecorder.platform.showroom

import com.wochatchat.liverecorder.net.LiveHttpClient
import java.util.regex.Pattern

/**
 * ShowroomSpider - Phase 9f: ShowRoom.
 */
open class ShowroomSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_ROOM_ID = Pattern.compile("href=\"/room/profile\\?room_id=(.*?)\"")
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        fun parseRoomId(url: String): String =
            if (url.contains("room_id=")) url.split("room_id=").last().split("&").first()
            else url.split("?").first().substringAfterLast("/")
    }

    data class ShowroomStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): ShowroomStreamInfo {
        val headers = buildMap {
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val roomId = if (url.contains("room_id=")) {
            parseRoomId(url)
        } else {
            val pageResp = runCatching { c.get(url, headers) }.getOrNull()
                ?: return ShowroomStreamInfo()
            RE_ROOM_ID.toRegex().find(pageResp.text)?.groupValues?.get(1) ?: ""
        }
        if (roomId.isBlank()) return ShowroomStreamInfo()
        val infoResp = runCatching {
            c.get("https://www.showroom-live.com/api/live/live_info?room_id=$roomId", headers)
        }.getOrNull() ?: return ShowroomStreamInfo()
        val infoJson = runCatching { org.json.JSONObject(infoResp.text) }.getOrNull()
            ?: return ShowroomStreamInfo()
        val anchorName = infoJson.optString("room_name", "")
        val liveStatus = infoJson.optInt("live_status", 0)
        if (liveStatus != 2) return ShowroomStreamInfo(anchorName = anchorName)
        val webResp = runCatching {
            c.get("https://www.showroom-live.com/api/live/streaming_url?room_id=$roomId&abr_available=1", headers)
        }.getOrNull() ?: return ShowroomStreamInfo(anchorName = anchorName, isLive = true)
        val webJson = runCatching { org.json.JSONObject(webResp.text) }.getOrNull()
            ?: return ShowroomStreamInfo(anchorName = anchorName, isLive = true)
        val urlList = webJson.optJSONArray("streaming_url_list") ?: return ShowroomStreamInfo(anchorName = anchorName, isLive = true)
        var m3u8Url = ""
        for (i in 0 until urlList.length()) {
            val item = urlList.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "hls_all") {
                m3u8Url = item.optString("url", "")
                break
            }
        }
        if (m3u8Url.isBlank()) return ShowroomStreamInfo(anchorName = anchorName, isLive = true)
        val recordUrl = m3u8Url.replace("https://", "http://")
        return ShowroomStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = recordUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}