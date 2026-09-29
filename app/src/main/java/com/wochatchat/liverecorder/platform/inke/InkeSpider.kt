package com.wochatchat.liverecorder.platform.inke

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * InkeSpider — Phase 9b：映客直播爬虫。
 * 对照上游 spider.py:2582 get_yingke_stream_url。
 *
 * 主流程：
 * - URL query 提取 uid + id（www.inke.cn/...?uid=xxx&id=yyy）
 * - GET webapi.busi.inke.cn/web/live_share_pc?uid=&id=&_t={秒级时间戳}
 * - data.status==1 → live_addr[0] 的 hls_stream_addr（m3u8）/ stream_addr（flv）
 * record_url = m3u8（上游同语义，HLS 优先）。
 */
open class InkeSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** URL query 参数提取（上游 urllib.parse.parse_qs）。 */
        fun extractQuery(url: String, name: String): String? {
            val query = url.split("?").getOrNull(1) ?: return null
            return query.split("&").mapNotNull { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2 && kv[0] == name && kv[1].isNotEmpty()) kv[1] else null
            }.firstOrNull()
        }
    }

    data class InkeStreamInfo(
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
    ): InkeStreamInfo {
        val uid = extractParam(url, "uid") ?: return InkeStreamInfo()
        val liveId = extractParam(url, "id") ?: return InkeStreamInfo()
        val headers = buildMap {
            put("Referer", "https://www.inke.cn/")
            put("User-Agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val params = mapOf(
            "uid" to uid,
            "id" to liveId,
            "_t" to (System.currentTimeMillis() / 1000).toString(),
        )
        val api = "https://webapi.busi.inke.cn/web/live_share_pc?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        val resp = try {
            clientOrProxy(proxyAddr).get(api, headers)
        } catch (e: Exception) {
            return InkeStreamInfo()
        }
        val data = runCatching { JSONObject(resp.text) }.getOrNull()
            ?.optJSONObject("data") ?: return InkeStreamInfo()
        val anchorName = data.optJSONObject("media_info")?.optString("nick", "") ?: ""
        if (data.optInt("status", 0) != 1) {
            return InkeStreamInfo(anchorName = anchorName)
        }
        val liveAddr = data.optJSONArray("live_addr")?.optJSONObject(0)
            ?: return InkeStreamInfo(anchorName = anchorName)
        val m3u8Url = liveAddr.optString("hls_stream_addr", "")
        val flvUrl = liveAddr.optString("stream_addr", "")
        if (m3u8Url.isEmpty()) return InkeStreamInfo(anchorName = anchorName)
        return InkeStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8Url,
            flvUrl = flvUrl,
            recordUrl = m3u8Url,
        )
    }

    private fun extractParam(url: String, name: String): String? {
        val query = url.split("?").getOrNull(1) ?: return null
        return query.split("&").mapNotNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name && kv[1].isNotEmpty()) kv[1] else null
        }.firstOrNull()
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
