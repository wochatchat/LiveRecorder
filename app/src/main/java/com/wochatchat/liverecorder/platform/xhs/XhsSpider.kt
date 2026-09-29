package com.wochatchat.liverecorder.platform.xhs

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * XhsSpider — Phase 8b：小红书直播爬虫。
 * 对照上游 spider.py:769 get_xhs_stream_url（逐行对齐）。
 *
 * 主流程：
 *   1. xhslink.com 短链 → GET 跟随重定向取最终地址（上游 redirect_url=True）
 *   2. user_id：路径 /user/profile/{id} 优先，缺省回落 query host_id
 *   3. GET 房间页 → <script>window.__INITIAL_STATE__=...</script>
 *      → JSON（undefined→null 同上游）→ liveStream.liveStatus=="success"
 *      → roomData.roomInfo：roomTitle 含「回放」则视为未开播；
 *        deeplink 解出 host_nickname / flvUrl，roomId = flvUrl 'live/' 段首段
 *        → 固定 CDN 直链 http://live-source-play.xhscdn.com/live/{roomId}.flv（+ .m3u8）
 *   4. 未开播/回放/无 liveStream：回落个人主页取 <title>@昵称 的个人主页</title> 兜底主播名
 *
 * 上游异常语义（flvUrl 无 'live/' 段等）安卓侧防御性回落 is_live=false，不发抛异常。
 */
open class XhsSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val APP_UA =
            "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        private val STATE_REGEX =
            Pattern.compile("<script>window.__INITIAL_STATE__=(.*?)</script>")
        private val TITLE_ANCHOR = Pattern.compile("<title>@(.*?) 的个人主页</title>")

        fun isXhsUrl(url: String): Boolean =
            url.contains("xiaohongshu.com") || url.contains("xhslink.com")

        /** 上游 get_params：query 首个值（URL-decoded），缺失返回 null。 */
        fun queryParam(url: String, name: String): String? {
            val q = url.substringAfter('?', "")
            if (q.isEmpty()) return null
            for (pair in q.split('&')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == name) {
                    return runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrNull()
                }
            }
            return null
        }

        /** 上游正则 /user/profile/(.*?)(?=/|\?|$) 同语义：路径段取 user_id。 */
        fun userIdFromPath(url: String): String? {
            val m = Pattern.compile("/user/profile/(.*?)(?=/|\\?|\$)").matcher(url)
            return if (m.find()) m.group(1) else null
        }

        /** window.__INITIAL_STATE__ JSON 提取（上游正则同语义）。 */
        fun extractInitialState(html: String): String? {
            val m = STATE.matcher(html)
            return if (m.find()) m.group(1) else null
        }
    }

    data class XhsStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): XhsStreamInfo {
        val headers = buildMap {
            put("User-Agent", APP_UA)
            put("xy-common-params", "platform=iOS&sid=session.1722166379345546829388")
            put("referer", "https://app.xhs.cn/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        // 上游：xhslink.com 短链先解析重定向
        var target = url
        if (url.contains("xhslink.com")) {
            target = try { c.get(url, headers).finalUrl } catch (e: Exception) { return XhsStreamInfo() }
        }
        val hostId = queryParam(target, "host_id")
        val userId = userIdFromPath(target) ?: hostId ?: ""

        val roomHtml = try { c.get(target, headers).text } catch (e: Exception) { return XhsStreamInfo() }
        val state = extractInitialState(roomHtml)
        if (state != null) {
            val json = try {
                JSONObject(state.replace("undefined", "null"))
            } catch (e: Exception) { null }
            val live = json?.optJSONObject("liveStream")
            if (live != null) {
                val info = parseLive(live)
                if (info != null) return info
            }
        }

        // 回落：个人主页取主播名（上游 spider.py:811-820）
        val anchorName = fetchProfileAnchor(c, "https://www.xiaohongshu.com/user/profile/$user_id", headers)
        return XhsStreamInfo(anchorName = anchorName)
    }

    /** 开播分支（spider.py:786-806）：liveStatus=="success" 且标题非回放 → 固定 CDN 直链。 */
    open fun parseLive(liveStream: JSONObject): XhsStreamInfo? {
        if (liveStream.optString("liveStatus") != "success") return null
        val roomInfo = liveStream.optJSONObject("roomData")?.optJSONObject("roomInfo")
            ?: return null
        val title = roomInfo.optString("roomTitle")
        if (title.isEmpty() || title.contains("回放")) return null
        val liveLink = roomInfo.optString("deeplink")
        val anchorName = queryParam(liveLink, "host_nickname") ?: ""
        val flvParam = queryParam(liveLink, "flvUrl") ?: return null
        // 上游 split('live/')[1].split('.')[0]：取 live/ 后至首个 '.' 的房间号
        val roomId = flvParam.substringAfter("live/", "").substringBefore('.').ifEmpty { return null }
        val flv = "http://live-source-play.xhscdn.com/live/$roomId.flv"
        return XhsStreamInfo(
            anchorName = anchorName,
            title = title,
            isLive = true,
            flvUrl = flv,
            m3u8Url = flv.replace(".flv", ".m3u8"),
            recordUrl = flv,
        )
    }

    private suspend fun fetchProfileAnchor(
        c: LiveHttpClient, profileUrl: String, headers: Map<String, String>,
    ): String = try {
        TITLE_ANCHOR.matcher(c.get(profileUrl, headers).text)
            .takeIf { it.find() }?.group(1).orEmpty()
    } catch (e: Exception) { "" }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
