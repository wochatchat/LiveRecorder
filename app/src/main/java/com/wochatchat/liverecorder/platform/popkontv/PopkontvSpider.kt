package com.wochatchat.liverecorder.platform.popkontv

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * PopkontvSpider — Phase 9c：PopkonTV（韩国）直播爬虫。
 * 对照上游 spider.py:1629 login_popkontv + 1675 get_popkontv_stream_data + 1740 get_popkontv_stream_url。
 *
 * 主流程（搜索 + 房间页 + 观看开关三段）：
 * - anchor_id = URL 参数 mcid= 或 castId=
 * - POST broadcast/v1/search/all（JSON）→ broadCastList 匹配 mcSignId → 主播名 + partnerCode
 * - 找不到时：URL mcPartnerCode/partnerCode → 频道 notices 页 mcNickName 兜底
 * - GET live/view?castId={id}&partnerCode={code} → __NEXT_DATA__ → mcData.data 房间信息
 * - POST broadcast/v1/castwatchonoffguest → L0000/L0001 → data.castHlsUrl；
 *   400/E5000 → 账密登录（token 640 位）重试；L000A → 未验证会员
 * record_url = m3u8。
 *
 * 移动端差异：未配置账密时私有房间/需登录场景防御性返回未开播（上游 raise）。
 */
open class PopkontvSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
        const val AUTH_BASIC =
            "Basic FpAhe6mh8Qtz116OENBmRddbYVirNKasktdXQiuHfm88zRaFydTsFy63tzkdZY0u"
        const val CLIENT_KEY =
            "Client FpAhe6mh8Qtz116OENBmRddbYVirNKasktdXQiuHfm88zRaFydTsFy63tzkdZY0u"
        private val RE_NEXT_DATA = Pattern.compile(
            "<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL,
        )

        fun parseAnchorId(url: String): String? {
            val m = if (url.contains("mcid=")) {
                Regex("mcid=(.*?)(?=&|$)").find(url)
            } else {
                Regex("castId=(.*?)(?=&|$)").find(url)
            }
            return m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
        }

        /** URL 查询参数（上游 get_params：parse_qs 取首个）。 */
        fun getParam(url: String, key: String): String? =
            url.substringAfter("?", "").split("&")
                .firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
    }

    data class PopkontvStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val recordUrl: String = "",
        val newToken: String? = null,
    )

    /** 房间信息（上游 castStartDate/partnerCode/signId/castType/isPrivate 五元组）。 */
    private data class RoomData(
        val castStartDate: String,
        val partnerCode: String,
        val mcSignId: String,
        val castType: String,
        val isPrivate: String,
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
        accessToken: String? = null,
        username: String? = null,
        password: String? = null,
        partnerCode: String = "P-00001",
    ): PopkontvStreamInfo {
        val anchorId = parseAnchorId(url) ?: return PopkontvStreamInfo()
        val c = clientOrProxy(proxyAddr)
        val headers = baseHeaders(cookie, accessToken)
        val room = fetchRoomInfo(c, headers, url, anchorId, username, partnerCode)
            ?: return PopkontvStreamInfo()
        val (anchorName, roomData) = room
        val data = roomData ?: return PopkontvStreamInfo(anchorName = anchorName)
        val roomPassword = getParam(url, "pwd") ?: ""
        if (roomPassword.isEmpty() && data.isPrivate != "0") {
            return PopkontvStreamInfo(anchorName = anchorName)
        }
        var effectiveHeaders = headers
        var effectivePartnerCode = partnerCode
        var jsonStr = try {
            fetchWatchJson(c, headers, data, roomPassword, username, partnerCode)
        } catch (e: Exception) {
            ""
        }
        var newToken: String? = null
        if (jsonStr.startsWith("HTTP Error 400") || jsonStr.contains("\"statusCd\":\"E5000")) {
            val loginResult = login(username, password, proxyAddr, partnerCode)
                ?: return PopkontvStreamInfo(anchorName = anchorName)
            val (newAccessToken, newPartnerCode) = loginResult
            if (newAccessToken.length != 640) return PopkontvStreamInfo(anchorName = anchorName)
            newToken = "Bearer $newAccessToken"
            effectiveHeaders = headers + ("Authorization" to "Bearer $newAccessToken")
            effectivePartnerCode = newPartnerCode
            jsonStr = try {
                fetchWatchJson(c, effectiveHeaders, data, roomPassword, username, newPartnerCode)
            } catch (e: Exception) {
                ""
            }
        }
        val watchJson = runCatching { JSONObject(jsonStr) }.getOrNull()
            ?: return PopkontvStreamInfo(anchorName = anchorName)
        val m3u8Url = when (watchJson.optString("statusCd", "")) {
            "L0001" -> {
                val retryDate = ((data.castStartDate.toIntOrNull() ?: 0) - 1).toString()
                val retryJson = try {
                    fetchWatchJson(c, effectiveHeaders, data, roomPassword, username,
                        effectivePartnerCode, overrideStartDate = retryDate)
                } catch (e: Exception) {
                    ""
                }
                runCatching { JSONObject(retryJson).optJSONObject("data")?.optString("castHlsUrl", "") }
                    .getOrNull().orEmpty()
            }
            "L0000" -> watchJson.optJSONObject("data")?.optString("castHlsUrl", "").orEmpty()
            else -> ""
        }
        if (m3u8Url.isEmpty()) return PopkontvStreamInfo(anchorName = anchorName)
        return PopkontvStreamInfo(anchorName = anchorName, isLive = true,
            m3u8Url = m3u8Url, recordUrl = m3u8Url, newToken = newToken)
    }

    // ── 内部实现 ─────────────────────────────────────────────────────────────

    private fun baseHeaders(cookie: String?, accessToken: String?): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
        put("Content-Type", "application/json")
        put("Origin", "https://www.popkontv.com")
        put("User-Agent", WEB_UA)
        if (!accessToken.isNullOrBlank()) put("Authorization", "Bearer $accessToken")
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }

    private suspend fun fetchRoomInfo(
        c: LiveHttpClient, headers: Map<String, String>, url: String, anchorId: String,
        username: String?, defaultPartnerCode: String,
    ): Pair<String, RoomData?>? {
        // 1) 搜索 broadCastList（上游 get_popkontv_stream_data）
        val searchBody = JSONObject()
            .put("partnerCode", defaultPartnerCode)
            .put("searchKeyword", anchorId)
            .put("signId", username ?: "")
        val searchResp = postJson(c, "https://www.popkontv.com/api/proxy/broadcast/v1/search/all",
            headers, searchBody) ?: return null
        val searchJson = runCatching { JSONObject(searchResp.text) }.getOrNull() ?: return null
        var partnerCode = ""
        var anchorName = "Unknown"
        val broadCastList = searchJson.optJSONObject("data")?.optJSONArray("broadCastList")
        if (broadCastList != null) {
            for (i in 0 until broadCastList.length()) {
                val item = broadCastList.optJSONObject(i) ?: continue
                if (item.optString("mcSignId", "") == anchorId) {
                    anchorName = "${item.optString("nickName", "")}-$anchorId"
                    partnerCode = item.optString("mcPartnerCode", "")
                    break
                }
            }
        }
        if (partnerCode.isEmpty()) {
            // URL 兜底 partnerCode（mcPartnerCode 优先于 partnerCode）+ notices 页昵称
            val regexResult = Regex("mcPartnerCode=(P-\\d+)").find(url)
                ?: Regex("partnerCode=(P-\\d+)").find(url)
            partnerCode = regexResult?.groupValues?.get(1) ?: defaultPartnerCode
            val noticesUrl = "https://www.popkontv.com/channel/notices?mcid=$anchorId&mcPartnerCode=$partnerCode"
            val noticesResp = try { c.get(noticesUrl, headers) } catch (e: Exception) { null }
            val mcName = noticesResp?.text?.let {
                Regex("\"mcNickName\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            } ?: "Unknown"
            anchorName = "$anchorId-$mcName"
        }
        // 2) live/view 房间页 __NEXT_DATA__ → mcData
        val liveUrl = "https://www.popkontv.com/live/view?castId=$anchorId&partnerCode=$partnerCode"
        val liveResp = try { c.get(liveUrl, headers) } catch (e: Exception) { return null }
        val nextData = RE_NEXT_DATA.toRegex().find(liveResp.text)?.groupValues?.get(1)
            ?: return anchorName to null
        val pageProps = runCatching {
            JSONObject(nextData).optJSONObject("props")?.optJSONObject("pageProps")
        }.getOrNull() ?: return anchorName to null
        val mcData = pageProps.optJSONObject("mcData")?.optJSONObject("data")
            ?: return anchorName to null
        return anchorName to RoomData(
            castStartDate = mcData.optString("mc_castStartDate", ""),
            partnerCode = partnerCode,
            mcSignId = mcData.optString("mc_signId", ""),
            castType = mcData.optString("castType", ""),
            isPrivate = mcData.optString("mc_isPrivate", "0"),
        )
    }

    private fun postJson(c: LiveHttpClient, url: String, headers: Map<String, String>, data: JSONObject) = try {
        c.post(url, headers, data.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
    } catch (e: Exception) { null }

    /** 观看开关 API（上游 fetch_data 闭包；overrideStartDate 供 L0001 偏移重试用）。 */
    private suspend fun fetchWatchJson(
        c: LiveHttpClient,
        headers: Map<String, String>,
        data: RoomData,
        roomPassword: String,
        username: String?,
        partnerCode: String,
        overrideStartDate: String? = null,
    ): String {
        val body = JSONObject()
            .put("androidStore", 0)
            .put("castCode", "${data.mcSignId}-${overrideStartDate ?: data.castStartDate}")
            .put("castPartnerCode", data.partnerCode)
            .put("castSignId", data.mcSignId)
            .put("castType", data.castType)
            .put("commandType", 0)
            .put("exePath", 5)
            .put("isSecret", data.isPrivate)
            .put("partnerCode", partnerCode)
            .put("password", roomPassword)
            .put("signId", username ?: "")
            .put("version", "4.6.2")
        val resp = c.post(
            "https://www.popkontv.com/api/proxy/broadcast/v1/castwatchonoffguest", headers,
            body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
        )
        return resp.text
    }

    /** 登录（上游 login_popkontv：POST member/v1/login → token + partnerCode）。 */
    suspend fun login(
        username: String?, password: String?, proxyAddr: String?, code: String = "P-00001",
    ): Pair<String, String>? {
        if (username.isNullOrEmpty() || password.isNullOrEmpty() ||
            username.length < 4 || password.length < 10
        ) return null
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6",
            "Authorization" to AUTH_BASIC,
            "Content-Type" to "application/json",
            "Origin" to "https://www.popkontv.com",
            "User-Agent" to WEB_UA,
        )
        val body = JSONObject()
            .put("partnerCode", code)
            .put("signId", username)
            .put("signPwd", password)
        return try {
            val resp = clientOrProxy(proxyAddr).post(
                "https://www.popkontv.com/api/proxy/member/v1/login", headers,
                body.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
                timeoutSec = 20,
            )
            val json = runCatching { JSONObject(resp.text) }.getOrNull() ?: return null
            when (json.optString("statusCd", "")) {
                "S2000" -> {
                    val data = json.optJSONObject("data") ?: return null
                    val token = data.optString("token", "")
                    val pc = data.optString("partnerCode", code)
                    if (token.isEmpty()) null else token to pc
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
