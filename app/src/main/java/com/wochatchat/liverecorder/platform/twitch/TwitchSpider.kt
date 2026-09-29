package com.wochatchat.liverecorder.platform.twitch

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * TwitchSpider — Phase 9a：Twitch 直播爬虫。
 * 对照上游 spider.py:2102 get_twitchtv_room_info + spider.py:2141 get_twitchtv_stream_data。
 *
 * 主流程（GQL GraphQL API）：
 * - PlaybackAccessToken_Template 查询 → streamPlaybackAccessToken（value+signature）
 * - ChannelShell persistedQuery → login/displayName/stream 判开播
 * - usher.ttvnw.net/api/channel/hls/{uid}.m3u8?{access_key} → m3u8
 *
 * 移动端差异：
 * - 上游 get_play_url_list 拉取 m3u8 正文提取流地址列表；安卓端录制层以 usher m3u8
 *   作为 record_url（ffmpeg 原生支持 master playlist，上游列表仅作备用源参考）。
 * - Client-Integrity 头上游取 token，安卓端省略（GQL 无该头也可用）。
 */
open class TwitchSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        /** 上游硬编码公共 Client-ID（spider.py:2111/2148）。 */
        const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

        /** ChannelShell persistedQuery sha256（spider.py:2118）。 */
        const val CHANNEL_SHELL_SHA256 =
            "580ab410bcd0c1ad194224957ae2241e5d252b2c5173d8e0cce9d32d5bb14efe"

        /** PlaybackAccessToken_Template 查询体（spider.py:2165-2176 原文，参数占位符 $ 需 Kotlin 转义）。 */
        internal fun playbackQuery(): String =
            "query PlaybackAccessToken_Template(\$login: String!, \$isLive: Boolean!, \$vodID: ID!, " +
                "\$isVod: Boolean!, \$playerType: String!) {  streamPlaybackAccessToken(channelName: \$login, " +
                "params: {platform: \"web\", playerBackend: \"mediaplayer\", playerType: \$playerType}) @include(if: " +
                "\$isLive) {    value    signature   authorization { isForbidden forbiddenReasonCode }   __typename  " +
                "}  videoPlaybackAccessToken(id: \$vodID, params: {platform: \"web\", playerBackend: \"mediaplayer\", " +
                "playerType: \$playerType}) @include(if: \$isVod) {    value    signature   __typename  }}"

        private const val GQL_URL = "https://gql.twitch.tv/gql"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** URL 末段 = 频道 login（上游 url.split('?')[0].rsplit('/')[-1]）。 */
        fun parseChannelLogin(url: String): String {
            val segments = url.split("?").first().split("/")
            return if (segments.last().isEmpty()) "" else segments.last()
        }
    }

    data class TwitchStreamInfo(
        val anchorName: String = "",
        val loginName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): TwitchStreamInfo {
        val uid = parseChannelLogin(url)
        if (uid.isBlank()) return TwitchStreamInfo()
        val c = clientOrProxy(proxyAddr)
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Client-ID", CLIENT_ID)
            put("Content-Type", "application/json; charset=utf-8")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }

        // 1) PlaybackAccessToken（上游 spider.py:2148-2178）
        val tokenBody = JSONObject()
            .put("operationName", "PlaybackAccessToken_Template")
            .put("query", playbackQuery())
            .put(
                "variables",
                JSONObject()
                    .put("isLive", true)
                    .put("login", uid)
                    .put("isVod", false)
                    .put("vodID", "")
                    .put("playerType", "site"),
            )
            .toString()
        val tokenResp = try {
            c.post(GQL_URL, headers, tokenBody.toRequestBody("application/json".toMediaTypeOrNull()))
        } catch (e: Exception) {
            return TwitchStreamInfo()
        }
        val tokenData = runCatching { JSONObject(tokenResp.text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
            ?: return TwitchStreamInfo()
        val token = tokenData.optString("value", "")
        val sign = tokenData.optString("signature", "")
        if (token.isEmpty() || sign.isEmpty()) return TwitchStreamInfo()

        // 2) ChannelShell → 开播状态 + 主播名（上游 spider.py:2102-2140）
        val channelBody = JSONArray()
            .put(
                JSONObject()
                    .put("operationName", "ChannelShell")
                    .put("variables", JSONObject().put("login", uid))
                    .put(
                        "extensions",
                        JSONObject().put(
                            "persistedQuery",
                            JSONObject()
                                .put("version", 1)
                                .put("sha256Hash", CHANNEL_SHELL_SHA256),
                        ),
                    ),
            )
            .toString()
        val channelResp = try {
            c.post(GQL_URL, headers, channelBody.toRequestBody("application/json".toMediaTypeOrNull()))
        } catch (e: Exception) {
            return TwitchStreamInfo()
        }
        val userData = runCatching { JSONArray(channelResp.text) }.getOrNull()
            ?.optJSONObject(0)?.optJSONObject("data")?.optJSONObject("userOrError")
            ?: return TwitchStreamInfo()
        val loginName = userData.optString("login", "")
        val displayName = userData.optString("displayName", loginName)
        val nickname = if (loginName.isNotEmpty()) "$displayName-$loginName" else displayName
        if (userData.optJSONObject("stream") == null) {
            return TwitchStreamInfo(anchorName = nickname, loginName = loginName, isLive = false)
        }

        // 3) usher m3u8（上游 spider.py:2188-2206）
        val m3u8Url = buildUsherUrl(uid, token, sign)
        return TwitchStreamInfo(
            anchorName = nickname,
            loginName = loginName,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
        )
    }

    /**usher m3u8 访问串（上游 params urlencode 同字段序，token/sign 需 URL 编码）。 */
    internal fun buildUsherUrl(uid: String, token: String, sign: String): String {
        val playSession =
            if (java.util.UUID.randomUUID().leastSignificantBits % 2 == 0L)
                "bdd22331a986c7f1073628f2fc5b19da" else "064bc3ff1722b6f53b0b5b8c01e46ca5"
        val encodedToken = java.net.URLEncoder.encode(token, "UTF-8")
        val encodedSign = java.net.URLEncoder.encode(sign, "UTF-8")
        val params = "acmb=e30=&allow_source=true&browser_family=firefox&browser_version=124.0" +
            "&cdm=wv&fast_bread=true&os_name=Windows&os_version=NT%2010.0&p=3553732&platform=web" +
            "&play_session_id=$playSession&player_backend=mediaplayer&player_version=1.28.0-rc.1" +
            "&playlist_include_framerate=true&reassignments_supported=true" +
            "&sig=$encodedSign&token=$encodedToken&transcode_mode=cbr_v1"
        return "https://usher.ttvnw.net/api/channel/hls/$uid.m3u8?$params"
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
