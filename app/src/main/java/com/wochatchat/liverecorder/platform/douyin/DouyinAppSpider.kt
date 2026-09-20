package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.AbSign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** 上游 room.py UnsupportedUrlError：重定向后既非 reflow 链接时的分流信号。 */
internal class DouyinUnsupportedUrlException(message: String) : Exception(message)

/**
 * 抖音 app 路径爬虫（对照上游 src/spider.py:145 get_douyin_app_stream_data）。
 *
 * 链路（分享短链场景）：
 * 1. URL 含 live.douyin.com/（? 前部分）→ 直接委托 web 路径（DouyinWebSpider）
 * 2. 否则短链解析：GET 跟随重定向 → 最终 URL 含 reflow/ → 提取 room_id + sec_user_id
 *    → webcast.amemv.com/webcast/room/reflow/info/（a_bogus 签名，UA=Edge）
 * 3. 重定向后非 reflow（如主播主页链接）→ get_unique_id 取抖音号 → HTML 路径兜底
 *    （DouyinHtmlSpider，对照上游 spider.py:230 get_douyin_stream_data；其自身失败再回落 app→web 链）
 *
 * 失败语义与上游一致：整体 catch 返回 [DouyinWebRoom.empty]。
 * app 路径返回的 room_data 与 web 路径同构，复用 [DouyinWebRoom]。
 */
class DouyinAppSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val cookie: String? = null,
    private val webSpider: DouyinWebSpider = DouyinWebSpider(client, cookie),
    private val htmlSpider: DouyinHtmlSpider = DouyinHtmlSpider(client, cookie),
) {

    suspend fun fetch(url: String): DouyinWebRoom = withContext(Dispatchers.IO) {
        try {
            // 上游：url.split('?')[0].split('live.douyin.com/') 命中 → 直接走 web 路径
            if (isWebUrl(url)) {
                return@withContext webSpider.fetch(url)
            }
            try {
                val (roomId, secUid) = getSecUserId(url)
                fetchAppRoom(roomId, secUid)
            } catch (e: DouyinUnsupportedUrlException) {
                // 上游：get_unique_id → get_douyin_stream_data(live.douyin.com/<unique_id>)
                // （spider.py:230 HTML 路径，1f 实现）；其自身失败再回落 app→web 链
                val uniqueId = getUniqueId(url)
                htmlSpider.fetch("https://live.douyin.com/$uniqueId")
            }
        } catch (e: Exception) {
            DouyinWebRoom.empty("Douyin app data fetch error, because ${e.message}.")
        }
    }

    // ------------------------------------------------------------------
    // 分流
    // ------------------------------------------------------------------

    /** 上游：url.split('?')[0].split('live.douyin.com/') 命中 → web 路径。 */
    internal fun isWebUrl(url: String): Boolean = url.substringBefore('?').contains("live.douyin.com/")

    // ------------------------------------------------------------------
    // room.py:52 get_sec_user_id —— 短链解析出 room_id + sec_user_id
    // ------------------------------------------------------------------

    /** 正常路径：重定向到 reflow/ 房间页，从 query 取 sec_user_id、路径尾段取 room_id。 */
    internal suspend fun getSecUserId(url: String): Pair<String, String> {
        val resp = client.get(url, headers = mobileHeaders())
        val redirectUrl = resp.finalUrl
        if (!redirectUrl.contains("reflow/")) {
            throw DouyinUnsupportedUrlException("The redirect URL does not contain 'reflow/'.")
        }
        val secUserId = Regex("sec_user_id=([\\w_\\-]+)&").find(redirectUrl)
            ?.groupValues?.get(1)
            ?: throw RuntimeException("Could not find sec_user_id in the URL.")
        val roomId = redirectUrl.substringBefore('?').substringAfterLast('/')
        return roomId to secUserId
    }

    // ------------------------------------------------------------------
    // room.py:78 get_unique_id —— 主页链接兜底，取抖音号走 web 路径
    // ------------------------------------------------------------------

    internal suspend fun getUniqueId(url: String): String {
        val resp = client.get(url, headers = mobileHeaders())
        if (resp.finalUrl.contains("reflow/")) {
            throw DouyinUnsupportedUrlException("Unsupported URL")
        }
        val secUserId = resp.finalUrl.substringBefore('?').substringAfterLast('/')
        val page = client.get("https://www.iesdouyin.com/share/user/$secUserId", headers = uniqueIdHeaders())
        return extractUniqueId(page.text)
    }

    /** 上游正则：unique_id":"xxx","verification_type，取最后一个匹配。 */
    internal fun extractUniqueId(html: String): String {
        val matches = Regex("""unique_id":"(.*?)","verification_type""")
            .findAll(html).map { it.groupValues[1] }.toList()
        return matches.lastOrNull() ?: throw RuntimeException("Could not find unique_id in the response.")
    }

    // ------------------------------------------------------------------
    // reflow/info 接口（a_bogus 签名）
    // ------------------------------------------------------------------

    /** 构建 reflow/info 完整 URL（含 a_bogus）。上游：urlencode(params) → ab_sign(query, ua)。 */
    internal fun buildApiUrl(roomId: String, secUserId: String, userAgent: String, timeMs: Long): String {
        val query = APP_PARAMS.entries.joinToString("&") { (k, v) ->
            val value = when (k) {
                "room_id" -> roomId
                "sec_user_id" -> secUserId
                else -> v
            }
            encode(k) + "=" + encode(value)
        }
        val api = "$API_BASE?$query"
        return "$api&a_bogus=" + AbSign.abSign(query, userAgent, timeMs)
    }

    private suspend fun fetchAppRoom(
        roomId: String,
        secUid: String,
        timeMs: Long = System.currentTimeMillis(),
    ): DouyinWebRoom {
        val ua = APP_UA
        val api = buildApiUrl(roomId, secUid, ua, timeMs)
        val resp = client.get(api, headers = appHeaders(ua))
        if (resp.text.isEmpty()) {
            throw RuntimeException("it triggered risk control")
        }
        return parseRoomJson(resp.text)
    }

    /**
     * 解析 reflow/info 响应 JSON。
     * 上游：data['data']，无 room → "VR live is not supported"；
     * anchor_name 取 room.owner.nickname（web 路径取 data.user.nickname，位置不同）。
     */
    internal fun parseRoomJson(json: String): DouyinWebRoom {
        val data = JSONObject(json).getJSONObject("data")
        if (!data.has("room") || data.isNull("room")) {
            throw RuntimeException("VR live is not supported")
        }
        val room = data.getJSONObject("room")
        val anchorName = room.getJSONObject("owner").getString("nickname")
        val status = room.optInt("status", 4)
        val title = room.optString("title", "")
        if (status != 2) return DouyinWebRoom(anchorName, status, title, emptyMap(), emptyMap())

        // 上游 room_data2['stream_url'] 缺失 → KeyError → 外层 catch 兜空结果
        val streamUrl = room.getJSONObject("stream_url")
        val (flvMap, hlsMap) = DouyinRoomStreams.parseQualityMaps(streamUrl)
        return DouyinWebRoom(anchorName, status, title, flvMap, hlsMap)
    }

    // ------------------------------------------------------------------
    // headers
    // ------------------------------------------------------------------

    private fun appHeaders(ua: String): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to ACCEPT_LANGUAGE,
        "Referer" to "https://live.douyin.com/",
        "Cookie" to (cookie ?: DEFAULT_APP_COOKIE),
    )

    /** room.py HEADERS：短链解析用移动端 UA + s_v_web_id。 */
    internal fun mobileHeaders(): Map<String, String> = mapOf(
        "User-Agent" to MOBILE_UA,
        "Accept-Language" to MOBILE_ACCEPT_LANGUAGE,
        "Cookie" to MOBILE_COOKIE,
    )

    /** room.py get_unique_id：二跳请求替换的 ttwid cookie。 */
    private fun uniqueIdHeaders(): Map<String, String> =
        mobileHeaders() + ("Cookie" to UNIQUE_ID_COOKIE)

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        private const val API_BASE = "https://webcast.amemv.com/webcast/room/reflow/info/"

        private val APP_PARAMS = linkedMapOf(
            "verifyFp" to "verify_hwj52020_7szNlAB7_pxNY_48Vh_ALKF_GA1Uf3yteoOY",
            "type_id" to "0",
            "live_id" to "1",
            "room_id" to "",
            "sec_user_id" to "",
            "version_code" to "99.99.99",
            "app_id" to "1128",
        )

        private const val APP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"

        /** 上游硬编码完整 Cookie，失效时替换（docs/04-risks.md）。 */
        private const val DEFAULT_APP_COOKIE = "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511; my_rd=1; passport_csrf_token=3ab34460fa656183fccfb904b16ff742; passport_csrf_token_default=3ab34460fa656183fccfb904b16ff742; d_ticket=9f562383ac0547d0b561904513229d76c9c21; n_mh=hvnJEQ4Q5eiH74-84kTFUyv4VK8xtSrpRZG1AhCeFNI; store-region=cn-fj; store-region-src=uid; LOGIN_STATUS=1; __security_server_data_status=1; FORCE_LOGIN=%7B%22videoConsumedRemainSeconds%22%3A180%7D; pwa2=%223%7C0%7C3%7C0%22; download_guide=%223%2F20230729%2F0%22; volume_info=%7B%22isUserMute%22%3Afalse%2C%22isMute%22%3Afalse%2C%22volume%22%3A0.6%7D; strategyABtestKey=%221690824679.923%22; stream_recommend_feed_params=%22%7B%5C%22cookie_enabled%5C%22%3Atrue%2C%5C%22screen_width%5C%22%3A1536%2C%5C%22screen_height%5C%22%3A864%2C%5C%22browser_online%5C%22%3Atrue%2C%5C%22cpu_core_num%5C%22%3A8%2C%5C%22device_memory%5C%22%3A8%2C%5C%22downlink%5C%22%3A10%2C%5C%22effective_type%5C%22%3A%5C%224g%5C%22%2C%5C%22round_trip_time%5C%22%3A150%7D%22; VIDEO_FILTER_MEMO_SELECT=%7B%22expireTime%22%3A1691443863751%2C%22type%22%3Anull%7D; home_can_add_dy_2_desktop=%221%22; __live_version__=%221.1.1.2169%22; device_web_cpu_core=8; device_web_memory_size=8; xgplayer_user_id=346045893336; csrf_session_id=2e00356b5cd8544d17a0e66484946f28; odin_tt=724eb4dd23bc6ffaed9a1571ac4c757ef597768a70c75fef695b95845b7ffcd8b1524278c2ac31c2587996d058e03414595f0a4e856c53bd0d5e5f56dc6d82e24004dc77773e6b83ced6f80f1bb70627; __ac_nonce=064caded4009deafd8b89; __ac_signature=_02B4Z6wo00f01HLUuwwAAIDBh6tRkVLvBQBy9L-AAHiHf7; ttcid=2e9619ebbb8449eaa3d5a42d8ce88ec835; webcast_leading_last_show_time=1691016922379; webcast_leading_total_show_times=1; webcast_local_quality=sd; live_can_add_dy_2_desktop=%221%22; msToken=1JDHnVPw_9yTvzIrwb7cQj8dCMNOoesXbA_IooV8cezcOdpe4pzusZE7NB7tZn9TBXPr0ylxmv-KMs5rqbNUBHP4P7VBFUu0ZAht_BEylqrLpzgt3y5ne_38hXDOX8o=; msToken=jV_yeN1IQKUd9PlNtpL7k5vthGKcHo0dEh_QPUQhr8G3cuYv-Jbb4NnIxGDmhVOkZOCSihNpA2kvYtHiTW25XNNX_yrsv5FN8O6zm3qmCIXcEe0LywLn7oBO2gITEeg=; tt_scid=mYfqpfbDjqXrIGJuQ7q-DlQJfUSG51qG.KUdzztuGP83OjuVLXnQHjsz-BRHRJu4e986"

        private const val ACCEPT_LANGUAGE =
            "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 11; SAMSUNG SM-G973U) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "SamsungBrowser/14.2 Chrome/87.0.4280.141 Mobile Safari/537.36"
        private const val MOBILE_ACCEPT_LANGUAGE =
            "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2"

        /** room.py HEADERS Cookie：s_v_web_id。 */
        private const val MOBILE_COOKIE = "s_v_web_id=verify_lk07kv74_QZYCUApD_xhiB_405x_Ax51_GYO9bUIyZQVf"

        /** room.py get_unique_id 二跳 cookie（另一组 ttwid）。 */
        private const val UNIQUE_ID_COOKIE = "ttwid=1%7C4ejCkU2bKY76IySQENJwvGhg1IQZrgGEupSyTKKfuyk%7C1740470403%7Cbc9ad2ee341f1a162f9e27f4641778030d1ae91e31f9df6553a8f2efa3bdb7b4; __ac_nonce=0683e59f3009cc48fbab0; __ac_signature=_02B4Z6wo00f01mG6waQAAIDB9JUCzFb6.TZhmsUAAPBf34; __ac_referer=__ac_blank"
    }
}
