package com.wochatchat.liverecorder.platform.winktv

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * WinktvSpider — Phase 9c：WinkTV（韩国）直播爬虫。
 * 对照上游 spider.py:1335 get_winktv_bj_info + 1361 get_winktv_stream_data。
 *
 * 主流程（双 POST API，与 PandaTV 同构）：
 * - POST api.winktv.co.kr/v1/member/bj（userId, info=media）→ live_status = 'media' in json
 * - 开播 → POST /v1/live/play（action=watch）→ errorData.needAdult → 防御返回；
 *   PlayList.hls[0].url → m3u8
 * - resp 403 = 网络被封禁（上游 ConnectionError，安卓端防御返回未开播）
 * record_url = m3u8。
 *
 * URL 格式：www.winktv.co.kr/live/{userId}（可带 ?pwd= 房间密码）。
 */
open class WinktvSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        /** userId = URL 末段（上游 url.split('?')[0].rsplit('/', maxsplit=1)[-1]）。 */
        fun parseUserId(url: String): String =
            url.split("?").first().substringAfterLast("/")

        /** URL 查询参数（上游 get_params：parse_qs 取首个）。 */
        fun getParam(url: String, key: String): String? =
            url.substringAfter("?", "").split("&")
                .firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
    }

    data class WinktvStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): WinktvStreamInfo {
        val userId = parseUserId(url)
        if (userId.isBlank()) return WinktvStreamInfo()
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("content-type", "application/x-www-form-urlencoded")
            put("referer", "https://www.winktv.co.kr")
            put("origin", "https://www.winktv.co.kr")
            put("user-agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        // 1) 主播信息（上游 get_winktv_bj_info：bjInfo.id/nick）
        val bjResp = try {
            c.postForm(
                "https://api.winktv.co.kr/v1/member/bj", headers,
                mapOf("userId" to userId, "info" to "media"),
            )
        } catch (e: Exception) {
            return WinktvStreamInfo()
        }
        if (bjResp.code == 403) return WinktvStreamInfo()
        val bjJson = runCatching { JSONObject(bjResp.text) }.getOrNull()
            ?: return WinktvStreamInfo()
        val bjInfo = bjJson.optJSONObject("bjInfo") ?: return WinktvStreamInfo()
        val anchorId = bjInfo.optString("id", "")
        val nick = bjInfo.optString("nick", "")
        if (nick.isEmpty()) return WinktvStreamInfo()
        val anchorName = "$nick-$anchorId"
        val liveStatus = bjJson.has("media")
        if (!liveStatus) return WinktvStreamInfo(anchorName = anchorName)
        // 2) 取流（action=watch）
        val playForm = mapOf(
            "action" to "watch",
            "userId" to userId,
            "password" to (getParam(url, "pwd") ?: ""),
            "shareLinkType" to "",
        )
        val playResp = try {
            c.postForm("https://api.winktv.co.kr/v1/live/play", headers, playForm)
        } catch (e: Exception) {
            return WinktvStreamInfo(anchorName = anchorName)
        }
        if (playResp.code == 403 || playResp.text.contains("403: Forbidden")) {
            return WinktvStreamInfo(anchorName = anchorName)
        }
        val playJson = runCatching { JSONObject(playResp.text) }.getOrNull()
            ?: return WinktvStreamInfo(anchorName = anchorName)
        val errorData = playJson.optJSONObject("errorData")
        if (errorData != null) {
            // needAdult → 需登录 cookie（上游 raise；安卓端防御返回未开播）
            return WinktvStreamInfo(anchorName = anchorName)
        }
        val m3u8Url = playJson.optJSONObject("PlayList")?.optJSONArray("hls")
            ?.optJSONObject(0)?.optString("url", "").orEmpty()
        if (m3u8Url.isEmpty()) return WinktvStreamInfo(anchorName = anchorName)
        return WinktvStreamInfo(anchorName = anchorName, isLive = true,
            m3u8Url = m3u8Url, recordUrl = m3u8Url)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
