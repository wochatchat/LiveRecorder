package com.wochatchat.liverecorder.platform.acfun

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * AcfunSpider — Phase 9a：AcFun 直播爬虫。
 * 对照上游 spider.py:2477 get_acfun_sign_params + spider.py:2498 get_acfun_stream_data。
 *
 * 主流程（快手协议）：
 * 1. GET /rest/pc-direct/user/userInfo?userId={author_id} → 主播名 + liveId 存在即开播
 * 2. visitor login → POST id.app.acfun.cn/rest/app/visitor/login → userId/did/visitor_st
 * 3. POST api.kuaishouzt.com/rest/zt/live/web/startPlay → videoPlayRes JSON
 *    → liveAdaptiveManifest[0].adaptationSet.representation → bitrate 降序播放列表
 * record_url = bitrate 最高档（上游 play_url_list[0]）。
 *
 * URL 格式：live.acfun.cn/live/{author_id}  或  m.acfun.cn/live/{author_id}
 */
open class AcfunSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** URL 末段 = author_id（上游 url.rsplit('/', maxsplit=1)[1]）。 */
        fun parseAuthorId(url: String): String {
            val segments = url.split("?").first().split("/")
            return if (segments.last().isEmpty()) "" else segments.last()
        }

        /** 生成上游格式 did：web_{随机16位小写字母数字}。 */
        internal fun generateDid(): String = "web_" + genRandomString(16)

        private fun genRandomString(len: Int): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val sb = StringBuilder()
            val random = java.security.SecureRandom()
            repeat(len) { sb.append(chars[random.nextInt(chars.length)]) }
            return sb.toString()
        }
    }

    data class AcfunStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): AcfunStreamInfo {
        val authorId = parseAuthorId(url)
        if (authorId.isBlank()) return AcfunStreamInfo()
        val c = clientOrProxy(proxyAddr)
        val headers = buildMap {
            put("referer", "https://live.acfun.cn/live/$authorId")
            put("User-Agent", WEB_UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }

        // 1) userInfo → 主播名 + 是否开播（上游 liveId in profile）
        val userApi = "https://live.acfun.cn/rest/pc-direct/user/userInfo?userId=$authorId"
        val userResp = try {
            c.get(userApi, headers)
        } catch (e: Exception) {
            return AcfunStreamInfo()
        }
        val profile = runCatching { JSONObject(userResp.text) }.getOrNull()
            ?.optJSONObject("profile") ?: return AcfunStreamInfo()
        val anchorName = profile.optString("name", "")
        if (!profile.has("liveId")) return AcfunStreamInfo(anchorName = anchorName)

        // 2) visitor login（上游 get_acfun_sign_params，did 随机 + sid 固定）
        val did = generateDid()
        val visitorHeaders = buildMap {
            put("referer", "https://live.acfun.cn/")
            put("User-Agent", WEB_UA)
            put("Cookie", "_did=$did;")
        }
        val visitorResp = try {
            c.postForm(
                "https://id.app.acfun.cn/rest/app/visitor/login",
                visitorHeaders,
                mapOf("sid" to "acfun.api.visitor"),
            )
        } catch (e: Exception) {
            return AcfunStreamInfo(anchorName = anchorName)
        }
        val visitorJson = runCatching { JSONObject(visitorResp.text) }.getOrNull()
            ?: return AcfunStreamInfo(anchorName = anchorName)
        val acUserId = visitorJson.optString("userId", "")
        val visitorSt = visitorJson.optString("acfun.api.visitor_st", "")
        if (acUserId.isEmpty()) return AcfunStreamInfo(anchorName = anchorName)

        // 3) startPlay → 快手协议流（上游 spider.py:2528-2534）
        val startPlayParams = mapOf(
            "subBiz" to "mainApp",
            "kpn" to "ACFUN_APP",
            "kpf" to "PC_WEB",
            "userId" to acUserId,
            "did" to did,
            "acfun.api.visitor_st" to visitorSt,
        )
        val startPlayUrl = "https://api.kuaishouzt.com/rest/zt/live/web/startPlay?" +
            startPlayParams.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        val startPlayResp = try {
            c.postForm(
                startPlayUrl,
                buildMap {
                    put("referer", "https://live.acfun.cn/live/$authorId")
                    put("User-Agent", WEB_UA)
                    put("Cookie", "_did=$did;")
                },
                mapOf("authorId" to authorId, "pullStreamType" to "FLV"),
            )
        } catch (e: Exception) {
            return AcfunStreamInfo(anchorName = anchorName)
        }
        val data = runCatching { JSONObject(startPlayResp.text) }.getOrNull()
            ?.optJSONObject("data") ?: return AcfunStreamInfo(anchorName = anchorName)
        val title = data.optString("caption", "")

        // videoPlayRes 内嵌 JSON → liveAdaptiveManifest[0].adaptationSet.representation
        val playUrl = parsePlayUrlList(data.optString("videoPlayRes", ""))
            ?: return AcfunStreamInfo(anchorName = anchorName, title = title)
        return AcfunStreamInfo(
            anchorName = anchorName,
            title = title,
            isLive = true,
            m3u8Url = playUrl,
            recordUrl = playUrl,
        )
    }

    /** 解析 videoPlayRes：representation 按 bitrate 降序，取首档 URL（上游 sorted(key=itemgetter('bitrate'), reverse=True)）。 */
    internal fun parsePlayUrlList(videoPlayRes: String): String? {
        if (videoPlayRes.isEmpty()) return null
        val vpr = runCatching { JSONObject(videoPlayRes) }.getOrNull() ?: return null
        val reps = vpr.optJSONArray("liveAdaptiveManifest")
            ?.optJSONObject(0)?.optJSONObject("adaptationSet")
            ?.optJSONArray("representation")
            ?: return null
        var best: Pair<Int, String>? = null
        for (i in 0 until reps.length()) {
            val rep = reps.optJSONObject(i) ?: continue
            val bitrate = rep.optInt("bitrate", 0)
            val url = rep.optString("url", "")
            if (url.isEmpty()) continue
            if (best == null || bitrate > best.first) best = bitrate to url
        }
        return best?.second
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
