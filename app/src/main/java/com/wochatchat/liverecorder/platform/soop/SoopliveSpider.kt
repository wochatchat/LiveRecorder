package com.wochatchat.liverecorder.platform.soop

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * SoopliveSpider — Phase 9c：SOOP（原 AfreecaTV）直播爬虫。
 * 对照上游 spider.py:900 login_sooplive / 938 get_sooplive_cdn_url / 965 get_sooplive_tk /
 * 1078 get_sooplive_stream_data。
 *
 * 双路径：
 * - 韩国站（sooplive.co.kr）：POST api.m.sooplive.co.kr/broad/a/watch →
 *   result==1 直接拿 broad_no + hls_authentication_key → CDN API → m3u8；
 *   code -3002（19+ 未登录）→ 账密登录 → tk 双 API；-3004（需登录）→ cookie 直用
 * - 国际站（sooplive.com）：api.sooplive.com/v2/channel+stream/info → global-media master.m3u8
 *
 * 移动端差异：
 * - 上游 get_url_list 拉取 m3u8 提取 auth_playlist 分档列表；安卓端录制层以 master m3u8
 *   作为 record_url（ffmpeg 原生支持 master playlist，与 Twitch/YouTube 同口径）。
 * - 未配账密时 -3002/-3004 防御性返回未开播（上游 raise）。
 */
open class SoopliveSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
        private const val LOGIN_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:122.0) Gecko/20100101 Firefox/122.0"
        private const val GLOBAL_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, " +
                "like Gecko) Version/18.5 Mobile/15E148 Safari/604.1 Edg/141.0.0.0"

        /**
         * bj_id 提取（上游 split_url = url.split('/')，
         * bj_id = split_url[3] if len < 6 else split_url[5]）。
         * play.sooplive.co.kr/{bj}/{no} → index 3；www.sooplive.co.kr/station/{no}/{bj} → index 5。
         */
        fun parseBjId(url: String): String {
            val parts = url.split("/")
            return if (parts.size < 6) parts.getOrElse(3) { "" } else parts.getOrElse(5) { "" }
        }

        /** URL 查询参数（上游 get_params）。 */
        fun getParam(url: String, key: String): String? =
            url.substringAfter("?", "").split("&")
                .firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
    }

    data class SoopStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val newCookies: String? = null,
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
        username: String? = null,
        password: String? = null,
    ): SoopStreamInfo = if (url.contains("sooplive.com")) {
        fetchGlobal(url, proxyAddr, cookie)
    } else {
        fetchKorean(url, proxyAddr, cookie, username, password)
    }

    // ── 韩国站（sooplive.co.kr）──────────────────────────────────────────────

    private suspend fun fetchKorean(
        url: String,
        proxyAddr: String?,
        cookie: String?,
        username: String?,
        password: String?,
    ): SoopStreamInfo {
        val bjId = parseBjId(url)
        if (bjId.isBlank()) return SoopStreamInfo()
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            put("Referer", "https://m.sooplive.co.kr/")
            put("Content-Type", "application/x-www-form-urlencoded")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val form = mapOf(
            "bj_id" to bjId,
            "broad_no" to "",
            "agent" to "web",
            "confirm_adult" to "true",
            "player_type" to "webm",
            "mode" to "live",
        )
        val resp = try {
            clientOrProxy(proxyAddr).postForm(
                "http://api.m.sooplive.co.kr/broad/a/watch", headers, form,
            )
        } catch (e: Exception) {
            return SoopStreamInfo()
        }
        val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return SoopStreamInfo()
        val data = json.optJSONObject("data")
        var anchorName = data?.optString("user_nick", "").orEmpty()
        if (anchorName.isNotEmpty() && data!!.has("bj_id")) {
            anchorName = "$anchorName-${data.optString("bj_id")}"
        }
        val result = SoopStreamInfo(anchorName = anchorName)
        if (anchorName.isEmpty()) {
            // 未取到昵称 → 按 data.code 分支（上游 -3001/-3002/-3004/-6001）
            return when (data?.optInt("code", 0)) {
                -3002 -> loginAndFetch(url, proxyAddr, username, password)
                -3004 -> if (!cookie.isNullOrBlank()) fetchWithCookie(url, cookie, proxyAddr)
                         else SoopStreamInfo()
                else -> SoopStreamInfo()
            }
        }
        if (json.optInt("result", 0) != 1) return SoopStreamInfo(anchorName = anchorName)
        val broadNo = data!!.optString("broad_no", "")
        val hlsKey = data.optString("hls_authentication_key", "")
        if (broadNo.isEmpty() || hlsKey.isEmpty()) return SoopStreamInfo(anchorName = anchorName)
        val m3u8Url = getCdnViewUrl(broadNo, proxyAddr, cookie)
            ?.let { "$it?aid=$hlsKey" } ?: return SoopStreamInfo(anchorName)
        return SoopStreamInfo(anchorName = anchorName, isLive = true, m3u8Url = m3u8Url, recordUrl = m3u8Url)
    }

    /** -3002：19+ 直播需登录（上游 login_sooplive → tk 双 API）。 */
    private suspend fun loginAndFetch(
        url: String,
        proxyAddr: String?,
        username: String?,
        password: String?,
    ): SoopStreamInfo {
        val loginCookie = login(username, password, proxyAddr) ?: return SoopStreamInfo()
        val info = fetchWithCookie(url, loginCookie, proxyAddr)
        return if (info.isLive) info.copy(newCookies = loginCookie) else info
    }

    /** 登录拿 cookie（上游 login_sooplive：AuthTicket 校验）。 */
    suspend fun login(username: String?, password: String?, proxyAddr: String?): String? {
        if (username.isNullOrEmpty() || password.isNullOrEmpty() ||
            username.length < 6 || password.length < 10
        ) return null
        val headers = mapOf(
            "User-Agent" to LOGIN_UA,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin" to "https://play.sooplive.co.kr",
            "Referer" to "https://play.sooplive.co.kr/superbsw123/277837074",
        )
        val form = mapOf(
            "szWork" to "login", "szType" to "json",
            "szUid" to username, "szPassword" to password,
            "isSaveId" to "true", "isSavePw" to "true", "isSaveJoin" to "true",
            "isLoginRetain" to "Y",
        )
        return try {
            val resp = clientOrProxy(proxyAddr).postForm(
                "https://login.sooplive.co.kr/app/LoginAction.php", headers, form, timeoutSec = 20,
            )
            if (resp.cookies.containsKey("AuthTicket")) {
                resp.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** 登录态拉流（上游 fetch_data：aid token + info 双 tk API → CDN → m3u8）。 */
    private suspend fun fetchWithCookie(
        url: String, cookie: String, proxyAddr: String?,
    ): SoopStreamInfo {
        val aidToken = getSoopliveTkAid(url, proxyAddr, cookie) ?: return SoopStreamInfo()
        val info = getTkInfo(url, proxyAddr, cookie) ?: return SoopStreamInfo()
        val m3u8Url = getCdnViewUrl(info.second, proxyAddr, cookie)
            ?.let { "$it?aid=$aidToken" } ?: return SoopStreamInfo(anchorName = info.first)
        return SoopStreamInfo(
            anchorName = info.first,
            isLive = true,
            m3u8Url = m3u8Url,
            recordUrl = m3u8Url,
            newCookies = cookie,
        )
    }

    /** tk API rtype=info：返回 (主播名-bjid, broad_no)（上游 get_sooplive_tk else 分支）。 */
    private suspend fun getTkInfo(
        url: String, proxyAddr: String?, cookie: String?,
    ): Pair<String, String>? {
        val bjId = parseBjId(url)
        if (bjId.isBlank()) return null
        val headers = mapOf(
            "User-Agent" to LOGIN_UA,
            "Origin" to "https://play.sooplive.co.kr",
            "Referer" to "https://play.sooplive.co.kr/secretx/250989857",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to (cookie ?: ""),
        )
        val form = mapOf(
            "bid" to bjId, "bno" to "", "type" to "info", "pwd" to (getParam(url, "pwd") ?: ""),
            "player_type" to "html5", "stream_type" to "common", "quality" to "master",
            "mode" to "landing", "from_api" to "0", "is_revive" to "false",
        )
        val resp = try {
            clientOrProxy(proxyAddr).postForm(
                "https://live.sooplive.co.kr/afreeca/player_live_api.php?bjid=$bjId",
                headers, form,
            )
        } catch (e: Exception) {
            return null
        }
        val channel = runCatching { JSONObject(resp.text).optJSONObject("CHANNEL") }.getOrNull()
            ?: return null
        val bjName = channel.optString("BJNICK", "")
        val bjIdCh = channel.optString("BJID", "")
        val bno = channel.optString("BNO", "")
        if (bjName.isEmpty() || bno.isEmpty()) return null
        return "$bjName-$bjIdCh" to bno
    }

    /** CDN 分配 API（spider.py:938）→ view_url。 */
    private suspend fun getCdnViewUrl(
        broadNo: String, proxyAddr: String?, cookie: String?,
    ): String? {
        val headers = mapOf(
            "User-Agent" to WEB_UA,
            "Accept-Language" to "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2",
            "Origin" to "https://play.sooplive.co.kr",
            "Referer" to "https://play.sooplive.co.kr/oul282/249469582",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to (cookie ?: ""),
        )
        val params = mapOf(
            "return_type" to "gcp_cdn", "use_cors" to "false",
            "cors_origin_url" to "play.sooplive.co.kr",
            "broad_key" to "$broadNo-common-master-hls",
            "time" to "8361.086329376785",
        )
        val api = "http://livestream-manager.sooplive.co.kr/broad_stream_assign.html?" +
            params.entries.joinToString("&") {
                java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                    java.net.URLEncoder.encode(it.value, "UTF-8")
            }
        return try {
            val resp = clientOrProxy(proxyAddr).get(api, headers)
            runCatching { JSONObject(resp.text).optString("view_url", "") }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getSoopliveTkAid(
        url: String, proxyAddr: String?, cookie: String?,
    ): String? {
        val bjId = parseBjId(url)
        if (bjId.isBlank()) return null
        val headers = mapOf(
            "User-Agent" to LOGIN_UA,
            "Origin" to "https://play.sooplive.co.kr",
            "Referer" to "https://play.sooplive.co.kr/secretx/250989857",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cookie" to (cookie ?: ""),
        )
        val form = mapOf(
            "bid" to bjId, "bno" to "", "type" to "aid", "pwd" to (getParam(url, "pwd") ?: ""),
            "player_type" to "html5", "stream_type" to "common", "quality" to "master",
            "mode" to "landing", "from_api" to "0", "is_revive" to "false",
        )
        val resp = try {
            clientOrProxy(proxyAddr).postForm(
                "https://live.sooplive.co.kr/afreeca/player_live_api.php?bjid=$bjId",
                headers, form,
            )
        } catch (e: Exception) {
            return null
        }
        return runCatching {
            JSONObject(resp.text).optJSONObject("CHANNEL")?.optString("AID", "")
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ── 国际站（sooplive.com）────────────────────────────────────────────────

    private suspend fun fetchGlobal(
        url: String, proxyAddr: String?, cookie: String?,
    ): SoopStreamInfo {
        val bjId = parseBjId(url)
        if (bjId.isBlank()) return SoopStreamInfo()
        val headers = getGlobalHeaders(cookie)
        val c = clientOrProxy(proxyAddr)
        // 1) 频道信息 → anchor_name
        val anchorName = try {
            val resp = c.get("https://api.sooplive.com/v2/channel/info/$bjId", headers)
            val data = JSONObject(resp.text).optJSONObject("data")
                ?.optJSONObject("streamerChannelInfo") ?: return SoopStreamInfo()
            val nickname = data.optString("nickname", "")
            val channelId = data.optString("channelId", "")
            if (nickname.isEmpty()) "" else "$nickname-$channelId"
        } catch (e: Exception) {
            return SoopStreamInfo()
        }
        // 2) 流信息 → isStream / title
        val (isStream, title) = try {
            val resp = c.get("https://api.sooplive.com/v2/stream/info/$bjId", headers)
            val data = JSONObject(resp.text).optJSONObject("data")
            (data?.optBoolean("isStream", false) ?: false) to data?.optString("title", "").orEmpty()
        } catch (e: Exception) {
            false to ""
        }
        if (!isStream) return SoopStreamInfo(anchorName = anchorName, title = title)
        // 3) master.m3u8（上游 global-media 域名直拼）
        val m3u8Url = "https://global-media.sooplive.com/live/$bjId/master.m3u8"
        return SoopStreamInfo(anchorName = anchorName, isLive = true, title = title,
            m3u8Url = m3u8Url, recordUrl = m3u8Url)
    }

    private fun getGlobalHeaders(cookie: String?): Map<String, String> = buildMap {
        put("client-id", java.util.UUID.randomUUID().toString())
        put("user-agent", GLOBAL_UA)
        if (!cookie.isNullOrBlank()) put("cookie", cookie)
    }

    // ── 公共工具 ─────────────────────────────────────────────────────────────

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
