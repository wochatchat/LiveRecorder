package com.wochatchat.liverecorder.platform.yinbo

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * YinboSpider — Phase 9b：音播直播爬虫。
 * 对照上游 spider.py:2615 get_yinbo_stream_url。
 *
 * 主流程：
 * - room_id = URL 末段（ybw1666.com/{room_id}）
 * - GET wap.ybw1666.com/api/ui/room/v1.0.0/live.ashx?roomidx={room_id}
 * - roomInfo.live_stat==1 → 再抓房间页提取 var config = {...} 里的
 *   domainpullstream_flv / domainpullstream_hls → 拼 flv/m3u8
 * record_url = flv（上游同语义）。
 */
open class YinboSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val API_UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        private val RE_CONFIG = Pattern.compile(
            "var config = (.*?)config\\.webskins",
            Pattern.DOTALL,
        )

        fun parseRoomId(url: String): String =
            url.split("?").first().trimEnd('/').substringAfterLast("/")

        /** 房间页 config JSON 提取 → (flv 域名, hls 域名)，供测试用。 */
        internal fun extractDomains(html: String): Pair<String, String>? {
            val m = RE_CONFIG.matcher(html)
            if (!m.find()) return null
            val configStr = m.group(1).substringBeforeLast(";").trim()
            val json = runCatching { JSONObject(configStr) }.getOrNull() ?: return null
            val flv = json.optString("domainpullstream_flv", "")
            val hls = json.optString("domainpullstream_hls", "")
            if (flv.isEmpty()) return null
            return flv to hls
        }
    }

    data class YinboStreamInfo(
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
    ): YinboStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return YinboStreamInfo()
        val headers = buildMap {
            put("User-Agent", API_UA)
            put("Accept", "application/json, text/plain, */*")
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            put("Referer", "https://live.ybw1666.com/800005143?promoters=0")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val params = mapOf(
            "roomidx" to roomId,
            "currentUrl" to "https://wap.ybw1666.com/$roomId",
        )
        val api = "https://wap.ybw1666.com/api/ui/room/v1.0.0/live.ashx?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        val resp = try {
            c.get(api, headers)
        } catch (e: Exception) {
            return YinboStreamInfo()
        }
        val roomInfo = runCatching { JSONObject(resp.text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("roomInfo")
            ?: return YinboStreamInfo()
        val anchorName = roomInfo.optString("nickname", "")
        if (roomInfo.optInt("live_stat", 0) != 1) {
            return YinboStreamInfo(anchorName = anchorName)
        }
        val liveId = roomInfo.optString("liveID", "")
        if (liveId.isEmpty()) return YinboStreamInfo(anchorName = anchorName)

        // 房间页 var config 提取流域名（上游 get_live_domain(url)）
        val html = try {
            c.get(url, headers).text
        } catch (e: Exception) {
            return YinboStreamInfo(anchorName = anchorName)
        }
        val (flvDomain, hlsDomain) = extractDomains(html)
            ?: return YinboStreamInfo(anchorName = anchorName)
        return YinboStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = "$hlsDomain/$liveId.m3u8",
            flvUrl = "$flvDomain/$liveId.flv",
            recordUrl = "$flvDomain/$liveId.flv",
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
