package com.wochatchat.liverecorder.platform.flextv

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * FlextvSpider — Phase 9c：FlexTV（韩国，原 ttinglive）直播爬虫。
 * 对照上游 spider.py:1406 login_flextv + 1444 get_flextv_stream_url + 1472 get_flextv_stream_data。
 *
 * 主流程：
 * - GET www.ttinglive.com/channels/{user_id}/live → __NEXT_DATA__ JSON → channel
 * - channel 含 '로그인후 이용이 가능합니다.'（需登录）→ 账密登录（可选）→ 带 cookie 重试
 * - 开播（无 message）：owner.nickname-loginId；GET /api/channels/{user_id}/stream?option=all
 *   → sources[0].url（.m3u8 → master，否则 FLV）
 * - 未开播：GET /channels/{user_id} → meta twitter:title 提取 `xxx의` 前缀
 * record_url = m3u8 ?: flv。
 *
 * 移动端差异：未配置账密时 19+ 房间防御性返回未开播（上游 raise）。
 */
open class FlextvSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
        private const val LOGIN_NEED_MARK = "로그인후 이용이 가능합니다."

        /** __NEXT_DATA__ 提取（JSON 含换行，需 DOTALL，同 8b 小红书教训）。 */
        private val RE_NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\".*?\">(.*?)</script>", Pattern.DOTALL,
        )
        private val RE_OFFLINE_TITLE = Pattern.compile("<meta name=\"twitter:title\" content=\"(.*?)의")

        /** userId：URL 中 '/live' 前的末段（上游 url.split('/live')[0].rsplit('/',1)[-1]）。 */
        fun parseUserId(url: String): String =
            url.split("/live").first().substringAfterLast("/").split("?").first()
    }

    data class FlextvStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
        val newCookies: String? = null,
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
        username: String? = null,
        password: String? = null,
    ): FlextvStreamInfo {
        val userId = parseUserId(url)
        if (userId.isBlank()) return FlextvStreamInfo()
        val c = clientOrProxy(proxyAddr)
        var effectiveCookie = cookie
        // 1) 房间页 __NEXT_DATA__（19+ 未登录时走登录重取）
        var channelData = fetchChannelData(c, url, userId, effectiveCookie)
            ?: return FlextvStreamInfo()
        if (channelData.has("message") &&
            channelData.optString("message", "").contains(LOGIN_NEED_MARK)
        ) {
            // 19+ 房间需登录：账密登录后带新 cookie 重取
            val loginCookie = login(username, password, proxyAddr) ?: return FlextvStreamInfo()
            effectiveCookie = loginCookie
            channelData = fetchChannelData(c, url, userId, effectiveCookie)
                ?: return FlextvStreamInfo()
        }
        val liveStatus = !channelData.has("message")
        if (!liveStatus) {
            // 未开播：频道页 meta twitter:title 兜底主播名
            val anchorName = try {
                c.get("https://www.ttinglive.com/channels/$userId", baseHeaders(effectiveCookie)).text
                    .let { html -> RE_OFFLINE_TITLE.toRegex().find(html)?.groupValues?.get(1) ?: "" }
            } catch (e: Exception) {
                ""
            }
            return FlextvStreamInfo(anchorName = anchorName, newCookies = effectiveCookie)
        }
        val owner = channelData.optJSONObject("owner") ?: return FlextvStreamInfo()
        val anchorName = owner.optString("nickname", "") + "-" + owner.optString("loginId", "")
        // 2) 取流（上游 get_flextv_stream_url：sources[0].url）
        val playResp = try {
            c.get("https://www.ttinglive.com/api/channels/$userId/stream?option=all",
                baseHeaders(effectiveCookie))
        } catch (e: Exception) {
            return FlextvStreamInfo(anchorName = anchorName)
        }
        val playJson = runCatching { JSONObject(playResp.text) }.getOrNull()
            ?: return FlextvStreamInfo(anchorName = anchorName)
        val playUrl = playJson.optJSONArray("sources")?.optJSONObject(0)
            ?.optString("url", "").orEmpty()
        if (playUrl.isEmpty()) return FlextvStreamInfo(anchorName = anchorName)
        return if (playUrl.contains(".m3u8")) {
            FlextvStreamInfo(anchorName = anchorName, isLive = true,
                m3u8Url = playUrl, recordUrl = playUrl, newCookies = effectiveCookie)
        } else {
            FlextvStreamInfo(anchorName = anchorName, isLive = true,
                flvUrl = playUrl, recordUrl = playUrl, newCookies = effectiveCookie)
        }
    }

    /** 房间页 __NEXT_DATA__ → props.pageProps.channel（防御：结构缺失返回 null）。 */
    private suspend fun fetchChannelData(
        c: LiveHttpClient, url: String, userId: String, cookie: String?,
    ): JSONObject? {
        val resp = try {
            c.get("https://www.ttinglive.com/channels/$userId/live", baseHeaders(cookie))
        } catch (e: Exception) {
            return null
        }
        val jsonStr = RE_NEXT_DATA.toRegex().find(resp.text)?.groupValues?.get(1)
            ?: return null
        return runCatching {
            JSONObject(jsonStr).optJSONObject("props")?.optJSONObject("pageProps")
                ?.optJSONObject("channel")
        }.getOrNull()
    }

    /** 登录（上游 login_flextv：POST /v2/api/auth/signin JSON → flx_oauth_access cookie）。 */
    suspend fun login(username: String?, password: String?, proxyAddr: String?): String? {
        if (username.isNullOrEmpty() || password.isNullOrEmpty() ||
            username.length < 6 || password.length < 8
        ) return null
        val headers = mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "content-type" to "application/json;charset=UTF-8",
            "referer" to "https://www.ttinglive.com/",
            "user-agent" to WEB_UA,
        )
        val body = JSONObject()
            .put("loginId", username)
            .put("password", password)
            .put("loginKeep", true)
            .put("saveId", true)
            .put("device", "PCWEB")
        return try {
            val resp = clientOrProxy(proxyAddr).post(
                "https://www.ttinglive.com/v2/api/auth/signin", headers,
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                timeoutSec = 20,
            )
            if (resp.cookies.containsKey("flx_oauth_access")) {
                resp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun baseHeaders(cookie: String?): Map<String, String> = buildMap {
        put("accept", "application/json, text/plain, */*")
        put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        put("referer", "https://www.ttinglive.com/")
        put("user-agent", WEB_UA)
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
