package com.wochatchat.liverecorder.platform.maoerfm

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * MaoerfmSpider — Phase 9d：猫耳FM直播爬虫。
 * 对照上游 spider.py:1303 get_maoerfm_stream_url。
 *
 * 主流程：
 * - room_id = URL 末段（fm.missevan.com/live/{room_id}）
 * - GET https://fm.missevan.com/api/v2/live/{room_id}
 * - info.room.status.broadcasting == true → m3u8/hls_pull_url + flv/flv_pull_url
 */
open class MaoerfmSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        fun parseRoomId(url: String): String =
            url.split("?").first().substringAfterLast("/")
    }

    data class MaoerfmStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): MaoerfmStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return MaoerfmStreamInfo()
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("referer", "https://fm.missevan.com/live/$roomId")
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val apiUrl = "https://fm.missevan.com/api/v2/live/$roomId"
        val resp = runCatching {
            c.get(apiUrl, headers)
        }.getOrNull() ?: return MaoerfmStreamInfo()
        val root = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: return MaoerfmStreamInfo()
        val info = root.optJSONObject("info") ?: return MaoerfmStreamInfo()
        val creator = info.optJSONObject("creator")
        val anchorName = creator?.optString("username", "") ?: ""
        val room = info.optJSONObject("room") ?: return MaoerfmStreamInfo(anchorName = anchorName)
        val status = room.optJSONObject("status") ?: return MaoerfmStreamInfo(anchorName = anchorName)
        if (status.optInt("broadcasting", 0) != 1) {
            return MaoerfmStreamInfo(anchorName = anchorName)
        }
        val channel = runCatching {
            room.optJSONObject("channel")
        }.getOrNull() ?: return MaoerfmStreamInfo(anchorName = anchorName)
        val m3u8Url = channel.optString("hls_pull_url", "")
        val flvUrl = channel.optString("flv_pull_url", "")
        val title = room.optString("name", "")
        return MaoerfmStreamInfo(
            anchorName = anchorName,
            title = title,
            isLive = true,
            m3u8Url = m3u8Url,
            flvUrl = flvUrl,
            recordUrl = flvUrl.ifEmpty { m3u8Url },
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}