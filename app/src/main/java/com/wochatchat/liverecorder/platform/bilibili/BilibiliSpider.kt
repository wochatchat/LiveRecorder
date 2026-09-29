/*
 * BilibiliSpider — Phase 4 第一批：B 站直播爬虫。
 *
 * 对照上游 spider.py:
 *   - get_bilibili_room_info(:677)：room_init（uid + live_status）→ Master/info（uname）
 *       → getH5InfoByRoom（标题，spider.py:656）。
 *   - get_bilibili_stream_data(:707)：room/v1/Room/playUrl?cid&qn&platform=web
 *       → code==0 时 durl 优先含 "d1--cn-gotcha" 的直链，否则取 durl[-1]；
 *       否则回落 getRoomPlayInfo（xlive/web-room/v2，需登录 cookie 才能取最高画质）
 *       → codec 按 current_qn 降序，video_quality_options 下标取。
 *
 * 取流（stream.py:350 get_bilibili_stream_url）：
 *   qn 映射 OD→10000 BD→400 UHD→250 HD→150 SD/LD→80；record_url = playUrl API 返回直链。
 */
package com.wochatchat.liverecorder.platform.bilibili

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/** B 站爬虫：room_init/Master/info 房间信息 + playUrl 取流。 */
class BilibiliSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"
        private const val H5_UA =
            "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36"
        private const val ROOM_INIT_API = "https://api.live.bilibili.com/room/v1/Room/room_init?id=%s"
        private const val MASTER_INFO_API = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=%s"
        private const val PLAY_URL_API = "https://api.live.bilibili.com/room/v1/Room/playUrl"
        private const val ROOM_PLAY_INFO_API =
            "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
        private const val H5_INFO_API =
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom?room_id=%s"

        /** 上游 video_quality_options（spider.py:748）：qn → codec 排序下标。 */
        val VIDEO_QUALITY_OPTIONS = mapOf(
            "10000" to 0, "400" to 1, "250" to 2, "150" to 3, "80" to 4,
        )

        fun isBilibiliUrl(url: String): Boolean = url.contains("live.bilibili.com/")

        /** 房间号（spider.py:686：? 前最后一段）。 */
        fun parseRoomId(url: String): String? {
            val seg = url.split("?").first().substringAfterLast("/")
            return seg.ifEmpty { null }
        }
    }

    /** 房间信息（对齐上游 get_bilibili_room_info 返回 dict）。 */
    data class BiliRoomInfo(
        val roomId: String? = null,
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
    )

    /** 取流结果（recordUrl = playUrl 直链，FLV）。 */
    data class BiliPlayback(
        val anchorName: String,
        val title: String,
        val quality: String,
        val recordUrl: String,
    )

    /**
     * 房间信息（spider.py:677）：room_init（uid/live_status）→ Master/info（uname）→ H5 标题。
     * 任一异常 → 空结果（上游 except 语义：anchor_name='', live_status=false）。
     */
    suspend fun getRoomInfo(url: String, proxyAddr: String? = null, cookie: String? = null): BiliRoomInfo {
        val roomId = parseRoomId(url) ?: return BiliRoomInfo()
        val headers = roomHeaders(cookie)
        return try {
            val initJson = JSONObject(
                clientOrProxy(proxyAddr).get(ROOM_INIT_API.format(roomId), headers).text
            )
            val data = initJson.optJSONObject("data") ?: return BiliRoomInfo(roomId = roomId)
            val uid = data.optString("uid", "")
            val isLive = data.optInt("live_status", 0) == 1
            val master = JSONObject(
                clientOrProxy(proxyAddr).get(MASTER_INFO_API.format(uid), headers).text
            )
            val anchorName = master.optJSONObject("data")?.optJSONObject("info")
                ?.optString("uname", "") ?: ""
            val title = getRoomTitle(roomId, proxyAddr, cookie)
            BiliRoomInfo(roomId = roomId, anchorName = anchorName, title = title, isLive = isLive)
        } catch (e: Exception) {
            BiliRoomInfo(roomId = roomId)
        }
    }

    /** 标题（spider.py:656 get_bilibili_room_info_h5）。 */
    private suspend fun getRoomTitle(roomId: String, proxyAddr: String?, cookie: String?): String {
        return try {
            val headers = mapOf(
                "user-agent" to
                    "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36",
                "accept-language" to "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2",
                "origin" to "https://live.bilibili.com",
                "referer" to "https://live.bilibili.com/$roomId",
                "cookie" to (cookie ?: ""),
            )
            val json = JSONObject(
                clientOrProxy(proxyAddr).get(H5_INFO_API.format(roomId), headers).text
            )
            json.optJSONObject("data")?.optJSONObject("room_info")?.optString("title", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 取流（spider.py:707 get_bilibili_stream_data + stream.py:350）：
     * qn 映射 → playUrl API；code==0 优先 d1--cn-gotcha 直链，否则 durl[-1]；
     * code!=0 回落 getRoomPlayInfo（codec current_qn 降序下标取，需 cookie）。
     */
    suspend fun getStreamData(
        url: String,
        qn: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): String? {
        val roomId = parseRoomId(url) ?: return null
        val headers = roomHeaders(cookie)
        val playApi = "$PLAY_URL_API?cid=$roomId&qn=$qn&platform=web"
        val playJson = try {
            JSONObject(clientOrProxy(proxyAddr).get(playApi, headers).text)
        } catch (e: Exception) {
            null
        }
        if (playJson != null && playJson.optInt("code", -1) == 0) {
            val durl = playJson.optJSONObject("data")?.optJSONArray("durl")
            if (durl != null) {
                var last = ""
                for (i in 0 until durl.length()) {
                    val u = durl.optJSONObject(i)?.optString("url", "") ?: ""
                    if (u.contains("d1--cn-gotcha")) return u
                    last = u
                }
                return last
            }
        }
        // 回落：getRoomPlayInfo（spider.py:751-762，需登录 cookie 才能取最高画质）
        return getRoomPlayInfo(roomId, qn, proxyAddr, cookie)
    }

    /** getRoomPlayInfo 回落（spider.py:734-761）：codec current_qn 降序，按 options 下标取。 */
    private suspend fun getRoomPlayInfo(
        roomId: String,
        qn: String,
        proxyAddr: String?,
        cookie: String?,
    ): String? {
        val params = "room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1,2&qn=$qn" +
            "&platform=web&ptype=8&dolby=5&panorama=1&hdr_type=0,1"
        val headers = roomHeaders(cookie) +
            mapOf("referer" to "https://live.bilibili.com/26066074", "origin" to "https://live.bilibili.com")
        val json = try {
            JSONObject(clientOrProxy(proxyAddr).get("$ROOM_PLAY_INFO_API?$params", headers).text)
        } catch (e: Exception) {
            null
        } ?: return null
        val data = json.optJSONObject("data") ?: return null
        if (data.optInt("live_status", -1) == 0) return null
        val format = data.optJSONObject("playurl_info")?.optJSONObject("playurl")
            ?.optJSONArray("stream")?.optJSONObject(0)?.optJSONArray("format") ?: return null
        val codecs = format.optJSONObject(0)?.optJSONArray("codec") ?: return null
        val sorted = (0 until codecs.length()).mapNotNull { codecs.optJSONObject(it) }
            .sortedByDescending { it.optInt("current_qn", 0) }
        if (sorted.isEmpty()) return null
        val idx = (VIDEO_QUALITY_OPTIONS[qn] ?: 0).coerceAtMost(sorted.size - 1)
        val stream = sorted[idx]
        val baseUrl = stream.optString("base_url", "")
        val urlInfo = stream.optJSONArray("url_info")?.optJSONObject(0) ?: return null
        return urlInfo.optString("host", "") + baseUrl + urlInfo.optString("extra", "")
    }

    private fun roomHeaders(cookie: String?): Map<String, String> = buildMap {
        put("User-Agent", WEB_UA)
        put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
