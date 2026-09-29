package com.wochatchat.liverecorder.platform.huajiao

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * HuajiaoSpider — Phase 9b：花椒直播爬虫。
 * 对照上游 spider.py:2351 get_huajiao_stream_url。
 *
 * 双路径：
 * - 房间 URL（www.huajiao.com/l/{roomId}）：直接 app API → getFeedInfo → substream
 * - 用户 URL（www.huajiao.com/user/{uid}）：需 cookie → webh.huajiao.com/User/getUserFeeds
 * record_url = h264_url（上游同语义）。
 */
open class HuajiaoSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        private const val APP_UA =
            "living/9.4.0 (com.huajiao.seeding; build:2410231746; iOS 17.0.0) Alamofire/9.4.0"

        private val RE_TITLE = Pattern.compile("<title>(.*?)的主页.*</title>")

        fun parseRoomId(url: String): String? {
            val path = url.split("?").first()
            return if (path.contains("/l/")) path.substringAfterLast("/l/") else null
        }

        fun parseUid(url: String): String? {
            return if (url.contains("/user/")) {
                url.split("?").first().substringAfterLast("/user/")
            } else null
        }
    }

    data class HuajiaoStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val title: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): HuajiaoStreamInfo {
        val c = clientOrProxy(proxyAddr)
        val baseHeaders = buildMap {
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("referer", "https://www.huajiao.com/")
            put("user-agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        return if (url.contains("/user/")) {
            fetchUserPath(url, c, baseHeaders, cookie)
        } else {
            fetchRoomPath(url, c, baseHeaders)
        }
    }

    private suspend fun fetchRoomPath(
        url: String,
        client: LiveHttpClient,
        baseHeaders: Map<String, String>,
    ): HuajiaoStreamInfo {
        val roomId = parseRoomId(url) ?: return HuajiaoStreamInfo()
        val feedInfo = getFeedInfo(roomId, client) ?: return HuajiaoStreamInfo()
        val feed = feedInfo.optJSONObject("feed") ?: return HuajiaoStreamInfo()
        val author = feedInfo.optJSONObject("author") ?: return HuajiaoStreamInfo()
        val anchorName = author.optString("nickname")
        val sn = feed.optString("sn")
        val liveId = feed.optString("relateid")
        val uid = author.optString("uid")
        val title = feed.optString("title")
        if (sn.isEmpty() || liveId.isEmpty()) return HuajiaoStreamInfo(anchorName = anchorName)
        val substream = getSubstream(sn, liveId, uid, title, client)
            ?: return HuajiaoStreamInfo(anchorName = anchorName, title = title)
        val h264Url = substream.optJSONObject("data")?.optString("h264_url") ?: ""
        return HuajiaoStreamInfo(
            anchorName = anchorName,
            isLive = h264Url.isNotEmpty(),
            title = title,
            flvUrl = h264Url,
            recordUrl = h264Url,
        )
    }

    private suspend fun fetchUserPath(
        url: String,
        client: LiveHttpClient,
        baseHeaders: Map<String, String>,
        cookie: String?,
    ): HuajiaoStreamInfo {
        if (cookie.isNullOrBlank()) return HuajiaoStreamInfo()
        val uid = parseUid(url) ?: return HuajiaoStreamInfo()
        // user feeds API
        val resp = try { client.get(buildUserFeedsUrl(uid), baseHeaders) } catch (e: Exception) {
            return HuajiaoStreamInfo()
        }
        val feeds = runCatching {
            JSONObject(resp.text).optJSONObject("data")?.optJSONArray("feeds")
                ?.optJSONObject(0)?.optJSONObject("feed")
        }.getOrNull() ?: return HuajiaoStreamInfo()
        val anchorName = try {
            client.get("https://www.huajiao.com/user/$uid", baseHeaders).text
                .let { html -> RE_TITLE.toRegex().find(html)?.groupValues?.get(1) ?: "" }
        } catch (e: Exception) { "" }
        val sn = feeds.optString("sn", "")
        if (sn.isEmpty()) return HuajiaoStreamInfo(anchorName = anchorName, isLive = false)
        val liveId = feeds.optString("relateid", "")
        val title = feeds.optString("title", "")
        val substream = getSubstream(sn, liveId, uid, title, client)
            ?: return HuajiaoStreamInfo(anchorName = anchorName, isLive = true, title = title)
        val h264Url = substream.optJSONObject("data")?.optString("h264_url") ?: ""
        return HuajiaoStreamInfo(
            anchorName = anchorName,
            isLive = h264Url.isNotEmpty(),
            title = title,
            flvUrl = h264Url,
            recordUrl = h264Url,
        )
    }

    private suspend fun getFeedInfo(roomId: String, client: LiveHttpClient): JSONObject? {
        val headers = mapOf(
            "User-Agent" to APP_UA,
            "accept-language" to "zh-Hans-US;q=1.0",
            "sdk_version" to "1",
        )
        val api = "https://live.huajiao.com/feed/getFeedInfo?relateid=$roomId"
        val resp = try { client.get(api, headers) } catch (e: Exception) { return null }
        return runCatching {
            val data = JSONObject(resp.text).optJSONObject("data")
            if (data == null || !data.has("creatime")) null else data
        }.getOrNull()
    }

    private suspend fun getSubstream(
        sn: String, liveId: String, uid: String, title: String,
        client: LiveHttpClient,
    ): JSONObject? {
        val headers = mapOf(
            "User-Agent" to WEB_UA,
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "referer" to "https://www.huajiao.com/",
        )
        val params = mapOf(
            "time" to (System.currentTimeMillis() / 1000).toString(),
            "version" to "1.0.0",
            "sn" to sn,
            "liveid" to liveId,
            "uid" to uid,
            "title" to title,
            "encode" to "h265",
        )
        val api = "https://live.huajiao.com/live/substream?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        val resp = try { client.get(api, headers) } catch (e: Exception) { return null }
        return runCatching { JSONObject(resp.text) }.getOrNull()
    }

    private fun buildUserFeedsUrl(uid: String): String {
        val params = mapOf(
            "uid" to uid,
            "fmt" to "json",
            "_" to System.currentTimeMillis().toString(),
        )
        return "https://webh.huajiao.com/User/getUserFeeds?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}