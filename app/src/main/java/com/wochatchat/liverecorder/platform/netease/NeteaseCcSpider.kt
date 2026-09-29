package com.wochatchat.liverecorder.platform.netease

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * NeteaseCCSpider — Phase 7d：网易CC直播爬虫。
 * 对照上游 spider.py:1189 get_netease_stream_data + stream.py:382 get_netease_stream_url。
 *
 * 主流程：GET 房间页（cc.163.com/{rid}/）→ __NEXT_DATA__ JSON
 *   → props.pageProps.roomInfoInitData.live
 *   → status==1 开播：anchorName/title/sharefile(m3u8)/quickplay(FLV 多画质)
 * 画质选择（stream.py:382 get_netease_stream_url）：
 *   quickplay.resolution 按 blueray/ultra/high/standard 降序映射画质码，
 *   取对应 CDN FLV；无 stream_list 时回落 m3u8。
 */
open class NeteaseCcSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        fun isNeteaseUrl(url: String): Boolean = url.contains("cc.163.com/")

        /** 画质索引（上游 get_quality_index：OD/BD/UHD→0, HD→1, SD/LD→2，缺省 0）。 */
        fun qualityIndex(code: String?): Int = when (code) {
            "OD", "BD", "UHD" -> 0
            "HD" -> 1
            "SD", "LD" -> 2
            else -> 0
        }

        /** __NEXT_DATA__ JSON 提取（上游正则 DOTALL 同语义）。 */
        fun extractNextData(html: String): String? {
            val m = Pattern.compile(
                "<script id=\"__NEXT_DATA__\" .* crossorigin=\"anonymous\">(.*?)</script></body>",
                Pattern.DOTALL,
            ).matcher(html)
            return if (m.find()) m.group(1) else null
        }
    }

    data class NeteaseStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    /**
     * 画质选择（stream.py:390-398）：resolution 键按 blueray/ultra/high/standard 排序，
     * 取 [qualityIndex] 索引对应的 CDN URL，CDN 列表取第一项。
     */
    open fun selectFlv(quickplay: JSONObject?, qualityCode: String?): String? {
        val resolution = quickplay?.optJSONObject("resolution") ?: return null
        val order = listOf("blueray", "ultra", "high", "standard")
        val keys = order.filter { resolution.has(it) }
        if (keys.isEmpty()) return null
        val idx = qualityIndex(qualityCode).coerceAtMost(keys.size - 1)
        val cdn = resolution.getJSONObject(keys[idx]).optJSONObject("cdn") ?: return null
        val firstKey = cdn.keys()?.asSequence()?.firstOrNull() ?: return null
        return cdn.optString(firstKey, "").ifEmpty { null }
    }

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
        qualityCode: String? = null,
    ): NeteaseStreamInfo {
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("referer", "https://cc.163.com/")
            put("User-Agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val pageUrl = url.trimEnd('/') + "/"
        val resp = try { c.get(pageUrl, headers) } catch (e: Exception) { return NeteaseStreamInfo() }
        val nextData = extractNextData(resp.text) ?: return NeteaseStreamInfo()
        val json = try {
            // 上游同语义：undefined → null 后 json.loads
            JSONObject(nextData.replace("undefined", "null"))
        } catch (e: Exception) { return NeteaseStreamInfo() }
        val roomInfo = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("roomInfoInitData")
        val fallbackAnchor = json.optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("roomInfoInitData")?.optString("nickname", "") ?: ""
        if (roomInfo == null) return NeteaseStreamInfo(anchorName = fallbackAnchor)
        val live = roomInfo.optJSONObject("live") ?: return NeteaseStreamInfo(anchorName = fallbackAnchor)
        val anchorName = live.optString("nickname", roomInfo.optString("nickname", ""))
        if (live.optInt("status", 0) != 1) return NeteaseStreamInfo(anchorName = anchorName)
        val m3u8 = live.optString("sharefile", "")
        val flv = selectFlv(live.optJSONObject("quickplay"), qualityCode)
        return NeteaseStreamInfo(
            anchorName = anchorName,
            title = live.optString("title", ""),
            isLive = true,
            m3u8Url = m3u8,
            flvUrl = flv ?: "",
            recordUrl = flv ?: m3u8,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}