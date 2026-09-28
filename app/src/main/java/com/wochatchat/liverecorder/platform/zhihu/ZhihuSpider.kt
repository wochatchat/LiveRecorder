package com.wochatchat.liverecorder.platform.zhihu

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * ZhihuSpider — Phase 7d：知乎直播爬虫。
 * 对照上游 spider.py:2657 get_zhihu_stream_url。
 *
 * 主流程：
 * - people/{uid} URL → api.zhihu.com/people/{uid}/profile 取 living_theater.theater_url
 * - GET 直播页 → js-initialData JSON → initialState.theater.theaters.{webId}
 *   → actor.name / drama.status==1 / drama.playInfo（hlsUrl + playUrl）
 * record_url = hlsUrl（上游同语义）。
 */
open class ZhihuSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        fun isZhihuUrl(url: String): Boolean = url.contains("zhihu.com/")

        /** 直播页 URL 末段 = theaters key（上游 web_id：split('?')[0].rsplit('/')[-1]）。 */
        fun parseWebId(pageUrl: String): String =
            pageUrl.split("?").first().trimEnd('/').substringAfterLast("/")

        /** people/{uid} → user id。 */
        fun peopleUrlToUserId(url: String): String? =
            if (url.contains("people/")) url.substringAfter("people/").split("/").first()
                .split("?").first().ifEmpty { null } else null

        /** js-initialData JSON 提取。 */
        fun extractInitialData(html: String): String? {
            val m = Pattern.compile(
                "<script id=\"js-initialData\" type=\"text/json\">(.*?)</script>",
                Pattern.DOTALL,
            ).matcher(html)
            return if (m.find()) m.group(1) else null
        }
    }

    data class ZhihuStreamInfo(
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
    ): ZhihuStreamInfo {
        val headers = mapOf(
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "User-Agent" to WEB_UA,
        )
        val c = clientOrProxy(proxyAddr)

        // people/{uid} → 先查个人资料拿直播页地址（上游 main.py 前置分流）
        val livePageUrl = peopleUrlToUserId(url)?.let { userId ->
            val api = "https://api.zhihu.com/people/$userId/profile?profile_new_version="
            val resp = try { c.get(api, headers) } catch (e: Exception) { return ZhihuStreamInfo() }
            val theaterUrl = runCatching {
                JSONObject(resp.text).optJSONObject("drama")?.optJSONObject("living_theater")
                    ?.optString("theater_url", "")
            }.getOrNull()
            theaterUrl?.takeIf { it.isNotBlank() } ?: return ZhihuStreamInfo()
        } ?: url

        val pageResp = try { c.get(livePageUrl, headers) } catch (e: Exception) { return ZhihuStreamInfo() }
        val initial = extractInitialData(pageResp.text) ?: return ZhihuStreamInfo()
        val json = try { JSONObject(initial) } catch (e: Exception) { return ZhihuStreamInfo() }
        val webId = parseWebId(livePageUrl)
        val theater = json.optJSONObject("initialState")?.optJSONObject("theater")
            ?.optJSONObject("theaters")?.optJSONObject(webId) ?: return ZhihuStreamInfo()
        val anchorName = theater.optJSONObject("actor")?.optString("name", "") ?: ""
        val drama = theater.optJSONObject("drama") ?: return ZhihuStreamInfo(anchorName = anchorName)
        if (drama.optInt("status", 0) != 1) return ZhihuStreamInfo(anchorName = anchorName)
        val playInfo = drama.optJSONObject("playInfo") ?: JSONObject()
        return ZhihuStreamInfo(
            anchorName = anchorName,
            title = theater.optString("theme", ""),
            isLive = true,
            m3u8Url = playInfo.optString("hlsUrl", ""),
            flvUrl = playInfo.optString("playUrl", ""),
            recordUrl = playInfo.optString("hlsUrl", ""),
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}