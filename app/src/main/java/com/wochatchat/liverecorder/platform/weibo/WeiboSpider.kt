package com.wochatchat.liverecorder.platform.weibo

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * 微博直播（spider.py:2007 get_weibo_stream_data）。
 * show/{id} 直取 live_id；/u/{uid} 先查 mymblog 找 object_id
 * → weibo.com/l/pc/anchor/live?live_id= → item.stream_info.pull（hls+flv）。
 */
open class WeiboSpider(private val client: LiveHttpClient = LiveHttpClient()) {
    companion object {
        private const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
        fun isWeiboUrl(url: String) = url.contains("weibo.com/")

        /** show/{roomId} 提取（上游 split('?')[0].split('show/')[1]）。 */
        fun parseShowId(url: String): String? =
            if (url.contains("show/")) url.split("?").first().substringAfter("show/", "").ifEmpty { null } else null
    }

    data class WeiboStreamInfo(
        val anchorName: String = "", val title: String = "", val isLive: Boolean = false,
        val m3u8Url: String = "", val flvUrl: String = "", val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(url: String, proxyAddr: String? = null, cookie: String? = null): WeiboStreamInfo {
        val headers = buildMap {
            put("Accept-Language", "zh-CN,zh;q=0.9")
            put("User-Agent", WEB_UA)
            put("Referer", "https://weibo.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
        val roomId = parseShowId(url) ?: resolveLiveIdFromBlog(url, c, headers) ?: return WeiboStreamInfo()
        val api = "https://weibo.com/l/pc/anchor/live?live_id=$roomId"
        val resp = try { c.get(api, headers) } catch (e: Exception) { return WeiboStreamInfo() }
        val json = try { JSONObject(resp.text) } catch (e: Exception) { return WeiboStreamInfo() }
        val data = json.optJSONObject("data") ?: return WeiboStreamInfo()
        val anchorName = data.optJSONObject("user_info")?.optString("name", "") ?: ""
        val item = data.optJSONObject("item") ?: return WeiboStreamInfo(anchorName = anchorName)
        if (item.optInt("status", 0) != 1) return WeiboStreamInfo(anchorName = anchorName)
        val pull = item.optJSONObject("stream_info")?.optJSONObject("pull") ?: JSONObject()
        val m3u8 = pull.optString("live_origin_hls_url", "")
        val flv = pull.optString("live_origin_flv_url", "")
        return WeiboStreamInfo(
            anchorName = anchorName,
            title = item.optString("desc", ""),
            isLive = true,
            m3u8Url = m3u8,
            flvUrl = flv,
            recordUrl = flv.ifEmpty { m3u8 },
        )
    }

    /** /u/{uid} → mymblog 首条 live 帖 object_id（上游同语义）。 */
    private suspend fun resolveLiveIdFromBlog(url: String, c: LiveHttpClient, headers: Map<String, String>): String? {
        if (!url.contains("/u/")) return null
        val uid = url.split("?").first().substringAfterLast("/u/", "")
        if (uid.isBlank()) return null
        val resp = try {
            c.get("https://weibo.com/ajax/statuses/mymblog?uid=$uid&page=1&feature=0", headers)
        } catch (e: Exception) { return null }
        val json = try { JSONObject(resp.text) } catch (e: Exception) { return null }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val pageInfo = item.optJSONObject("page_info") ?: continue
            if (pageInfo.optString("object_type") == "live") {
                return pageInfo.optString("object_id", "").ifEmpty { null }
            }
        }
        return null
    }
}