package com.wochatchat.liverecorder.platform.bigo

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern/*
 * BigoSpider — Phase 4 第二批：Bigo 直播爬虫。
 * 对照上游 spider.py:824 get_bigo_stream_url。
 *
 * 主流程：POST ta.bigo.tv/official_website/studio/getInternalStudioInfo
 *         → alive=1 → live，hls_src 即 m3u8 地址
 *         → alive=0 → 未开播，anchorName 仍填充
 * 回退（anchor_name='' 且 alive!=1）：
 *         GET www.bigo.tv/{seg}/{room_id} → 标题正则
 *
 * 上游 main.py:665：www.bigo.tv/ 或 slink.bigovideo.tv/ 均走此链路。
 */
open class BigoSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0"
        private const val STUDIO_API = "https://ta.bigo.tv/official_website/studio/getInternalStudioInfo"

        /** 上游 main.py:665：含 www.bigo.tv/ 或 slink.bigovideo.tv/ 的 URL。 */
        fun isBigoUrl(url: String): Boolean =
            url.contains("www.bigo.tv/") || url.contains("slink.bigovideo.tv/")

        /**
         * 解析 roomId（spider.py:850-858）。
         * 非 bigo.tv 短链 → 提取 al:web:url meta content → &h= 后段
         * bigo.tv URL → &h= 后段 或 末尾路径段
         */
        fun parseRoomId(url: String): String? {
            if (!url.contains("bigo.tv/")) {
                // slink / 其他短链：从 meta al:web:url 取 roomId
                return null  // 由调用方 GET 页面后再解析
            }
            // bigo.tv URL
            if (url.contains("&h=")) {
                return url.substringAfter("&h=").split("?").first().ifEmpty { null }
            }
            // 末尾路径段
            return url.split("?").first().trimEnd('/').substringAfterLast("/").ifEmpty { null }
        }

        /**
         * 从 bigo.tv 房间页 HTML 提取主播名（spider.py:872-877）。
         * 正则1：欢迎来到{anchor}的直播间  正则2：og:title "{anchor} - BIGO LIVE"
         */
        fun parseAnchorFromHtml(html: String): String {
            val m1 = Pattern.compile("欢迎来到(.*?)的直播间", Pattern.DOTALL).matcher(html)
            if (m1.find()) return m1.group(1) ?: ""
            val m2 = Pattern.compile("og:title.*?property=\"og:title\" content=\"(.*?) - BIGO LIVE\"",
                Pattern.CASE_INSENSITIVE).matcher(html)
            if (m2.find()) return m2.group(1) ?: ""
            return ""
        }

        /** al:web:url content（…&amp;h={roomId}）→ roomId（spider.py:846-848）。 */
        fun webUrlToRoomId(webUrl: String): String? =
            webUrl.substringAfter("&amp;h=", "").ifEmpty { null }
    }
    data class BigoStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getBigoStreamInfo(url: String, proxyAddr: String? = null, cookie: String? = null): BigoStreamInfo {
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            put("Referer", "https://www.bigo.tv/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val roomId = resolveRoomId(url, c, headers) ?: return BigoStreamInfo()
        val resp = try { c.postForm(STUDIO_API, headers, mapOf("siteId" to roomId)) } catch (e: Exception) { return BigoStreamInfo() }
        val json = try { JSONObject(resp.text) } catch (e: Exception) { return BigoStreamInfo() }
        val data = json.optJSONObject("data") ?: return BigoStreamInfo()
        val anchorName = data.optString("nick_name", "")
        val alive = data.optInt("alive", 0)
        val title = data.optString("roomTopic", "")
        if (alive == 1) {
            val m3u8Url = data.optString("hls_src", "")
            return BigoStreamInfo(anchorName = anchorName, title = title, isLive = true, m3u8Url = m3u8Url, recordUrl = m3u8Url)
        }
        if (anchorName.isEmpty()) {
            val fallback = try {
                c.get("https://www.bigo.tv/${url.split("/").getOrNull(3) ?: ""}/$roomId", headers).text
            } catch (e: Exception) { "" }
            val name = parseAnchorFromHtml(fallback)
            if (name.isNotEmpty()) return BigoStreamInfo(anchorName = name)
        }
        return BigoStreamInfo(anchorName = anchorName)
    }
    /**
     * roomId 解析（spider.py:849-858）：
     * bigo.tv 域名 → &h= 后段或末段路径；其他短链 → GET 页面提 al:web:url meta。
     */
    private suspend fun resolveRoomId(url: String, c: LiveHttpClient, headers: Map<String, String>): String? {
        parseRoomId(url)?.let { return it }
        if (url.contains("bigo.tv/")) return null
        val html = try { c.get(url, headers).text } catch (e: Exception) { return null }
        val m = Pattern.compile(
            "<meta[^>]*property=\"al:web:url\"[^>]*content=\"(.*?)\">",
            Pattern.CASE_INSENSITIVE,
        ).matcher(html)
        if (!m.find()) return null
        val webUrl = m.group(1) ?: return null
        return webUrlToRoomId(webUrl)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}