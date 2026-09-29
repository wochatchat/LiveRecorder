package com.wochatchat.liverecorder.platform.chzzk

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * CHZZKSpider — Phase 9a：CHZZK 直播爬虫（韩国 Naver 平台）。
 * 对照上游 spider.py:2696 get_chzzk_stream_data。
 *
 * 主流程（REST API）：
 * - GET https://api.chzzk.naver.com/service/v3/channels/{room_id}/live-detail
 * - status=='OPEN' → livePlaybackJson（JSON 字符串二次解析）→ media[0].path m3u8
 * record_url = m3u8（上游 get_play_url_list 拉取切片列表；录制层 ffmpeg 直接吃 m3u8）。
 *
 * URL 格式：chzzk.naver.com/live/{room_id}
 */
open class CHZZKSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** URL 末段 = room_id（上游 url.split('?')[0].rsplit('/')[-1]）。 */
        fun parseRoomId(url: String): String {
            val segments = url.split("?").first().split("/")
            return if (segments.last().isEmpty()) "" else segments.last()
        }

        fun isCHZZKUrl(url: String): Boolean = url.contains("chzzk.naver.com/")
    }

    data class ChzzkStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): ChzzkStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return ChzzkStreamInfo()
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("origin", "https://chzzk.naver.com")
            put("referer", "https://chzzk.naver.com/")
            put("User-Agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val api = "https://api.chzzk.naver.com/service/v3/channels/$roomId/live-detail"
        val resp = try {
            clientOrProxy(proxyAddr).get(api, headers)
        } catch (e: Exception) {
            return ChzzkStreamInfo()
        }
        val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return ChzzkStreamInfo()
        val liveData = json.optJSONObject("content") ?: return ChzzkStreamInfo()
        val anchorName = liveData.optJSONObject("channel")?.optString("channelName", "") ?: ""
        val status = liveData.optString("status", "")
        if (status != "OPEN") return ChzzkStreamInfo(anchorName = anchorName)
        // livePlaybackJson 是内嵌 JSON 字符串（上游 json.loads 二次解析）
        val playback = runCatching {
            JSONObject(liveData.optString("livePlaybackJson", "{}"))
        }.getOrNull() ?: return ChzzkStreamInfo(anchorName = anchorName)
        val m3u8Url = playback.optJSONArray("media")?.optJSONObject(0)?.optString("path", "").orEmpty()
        if (m3u8Url.isEmpty()) return ChzzkStreamInfo(anchorName = anchorName)
        return ChzzkStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
