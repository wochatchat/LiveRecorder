package com.wochatchat.liverecorder.platform.pandatv

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * PandatvSpider — Phase 9c：PandaTV（韩国）直播爬虫。
 * 对照上游 spider.py:1251 get_pandatv_stream_data。
 *
 * 主流程（双 POST API）：
 * - POST api.pandalive.co.kr/v1/member/bj（userId, info）→ bjInfo.id/nick → anchor_name
 * - 'media' in json → 开播 → POST /v1/live/play（action=watch）→
 *   errorData.needAdult → 未登录防御返回；PlayList.hls[0].url → m3u8
 * record_url = m3u8（上游 get_play_url_list 提取分档列表，录制层直接吃 master m3u8）。
 *
 * URL 格式：www.pandalive.co.kr/live/{userId}（可带 ?pwd= 房间密码）。
 */
open class PandatvSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        /** userId = URL 末段（上游 url.split('?')[0].rsplit('/', maxsplit=1)[1]）。 */
        fun parseUserId(url: String): String =
            url.split("?").first().substringAfterLast("/")

        /** URL 查询参数（上游 get_params：parse_qs 取首个）。 */
        fun getParam(url: String, key: String): String? =
            url.substringAfter("?", "").split("&")
                .firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
    }

    data class PandatvStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): PandatvStreamInfo {
        val userId = parseUserId(url)
        if (userId.isBlank()) return PandatvStreamInfo()
        val headers = buildMap {
            put("origin", "https://www.pandalive.co.kr")
            put("referer", "https://www.pandalive.co.kr/")
            put("user-agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        // 1) 主播信息（上游 bjInfo 校验 + message 兜底）
        val resp = try {
            c.postForm(
                "https://api.pandalive.co.kr/v1/member/bj", headers,
                mapOf("userId" to userId, "info" to "media fanGrade"),
            )
        } catch (e: Exception) {
            return PandatvStreamInfo()
        }
        val bjInfoJson = runCatching { JSONObject(resp.text) }.getOrNull()
            ?: return PandatvStreamInfo()
        if (!bjInfoJson.has("bjInfo")) return PandatvStreamInfo()
        val bjInfo = bjInfoJson.optJSONObject("bjInfo") ?: return PandatvStreamInfo()
        val anchorId = bjInfo.optString("id", "")
        val nick = bjInfo.optString("nick", "")
        if (nick.isEmpty()) return PandatvStreamInfo()
        val anchorName = "$nick-$anchorId"
        // 2) 未开播：'media' 不在响应中
        if (!bjInfoJson.has("media")) return PandatvStreamInfo(anchorName = anchorName)
        // 3) 取流（action=watch）
        val playForm = mapOf(
            "action" to "watch",
            "userId" to userId,
            "password" to (getParam(url, "pwd") ?: ""),
            "shareLinkType" to "",
        )
        val playResp = try {
            c.postForm("https://api.pandalive.co.kr/v1/live/play", headers, playForm)
        } catch (e: Exception) {
            return PandatvStreamInfo(anchorName = anchorName)
        }
        val playJson = runCatching { JSONObject(playResp.text) }.getOrNull()
            ?: return PandatvStreamInfo(anchorName = anchorName)
        val errorData = playJson.optJSONObject("errorData")
        if (errorData != null) {
            // needAdult → 需登录 cookie（上游 raise；安卓端防御返回未开播）
            return PandatvStreamInfo(anchorName = anchorName)
        }
        val m3u8Url = playJson.optJSONObject("PlayList")?.optJSONArray("hls")
            ?.optJSONObject(0)?.optString("url", "").orEmpty()
        if (m3u8Url.isEmpty()) return PandatvStreamInfo(anchorName = anchorName)
        return PandatvStreamInfo(anchorName = anchorName, isLive = true,
            m3u8Url = m3u8Url, recordUrl = m3u8Url)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
