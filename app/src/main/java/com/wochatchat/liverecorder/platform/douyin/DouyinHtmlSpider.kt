package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 抖音 HTML 路径爬虫（对照上游 src/spider.py:230 get_douyin_stream_data）。
 *
 * 链路：GET 直播页 HTML（Firefox UA + 硬编码 Cookie）→ 正则提取嵌入 state JSON 块
 * → 清洗（去反斜杠 + u0026 还原）→ roomStore 截断解析 roomInfo.room
 * → 开播时从 common 脚本块提取 ORIGIN 原画档合并进画质表。
 *
 * 用途：app 路径短链兜底（DouyinAppSpider UnsupportedUrl 分支：get_unique_id → HTML 路径）。
 * 失败语义与上游一致：整体 catch → [DouyinWebRoom.empty]（上游 except 兜 {'anchor_name': ""}）。
 * Cookie（ttwid 等）做成可替换常量，失效时用户可自行更新（docs/04-risks.md）。
 */
class DouyinHtmlSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val cookie: String? = null,
) {

    suspend fun fetch(url: String): DouyinWebRoom = withContext(Dispatchers.IO) {
        try {
            val resp = client.get(url, headers = requestHeaders())
            if (!resp.isSuccess || resp.text.isEmpty()) {
                throw RuntimeException("HTML fetch failed: code=${resp.code}")
            }
            parseHtml(resp.text)
        } catch (e: Exception) {
            DouyinWebRoom.empty("Douyin HTML data fetch error, because ${e.message}.")
        }
    }

    internal fun requestHeaders(): Map<String, String> = mapOf(
        "User-Agent" to HTML_UA,
        "Accept-Language" to
            "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2",
        "Referer" to "https://live.douyin.com/",
        "Cookie" to (cookie ?: HTML_COOKIE),
    )

    /** 解析入口（网络无关）。任何解析失败向上抛，由 [fetch] 兜空结果（同上游）。 */
    internal fun parseHtml(html: String): DouyinWebRoom {
        // 1. 提取 state JSON 块（两种页面格式，同上游两个 re.search）
        val blob = REGEX_STATE.find(html)?.groupValues?.get(1)
            ?: REGEX_STATE_ALT.find(html)?.groupValues?.get(1)
            ?: throw RuntimeException("state blob not found in HTML")

        // 2. 清洗（上游：replace('\\','').replace('u0026','&')）
        val cleaned = blob.replace("\\", "").replace("u0026", "&")

        // 3. roomStore → roomInfo.room（上游 split(',"has_commerce_goods"')[0] + '}}}'）
        val roomStore = REGEX_ROOM_STORE.find(cleaned)?.groupValues?.get(1)
            ?: throw RuntimeException("roomStore not found in state blob")
        val anchorName = REGEX_NICKNAME.find(roomStore)?.groupValues?.get(1) ?: ""
        val roomJson = roomStore.substringBefore(",\"has_commerce_goods\"") + "}}}"
        val room = JSONObject(roomJson).getJSONObject("roomInfo").getJSONObject("room")
        val status = room.optInt("status", 4)
        val title = room.optString("title", "")

        // 上游：status==4 直接返回（不再取画质表）
        if (status == 4) return DouyinWebRoom(anchorName, status, title, emptyMap(), emptyMap())

        val streamUrl = room.getJSONObject("stream_url")
        val orientation = streamUrl.optInt("stream_orientation")
        val originMain = extractOriginMain(html, orientation)

        val (flvMap, hlsMap) = plainQualityMaps(roomJson)
        if (originMain != null) {
            // 上游 HTML 路径：&codec= 无条件追加（codec 空串也加），{**origin, **原表} = ORIGIN 优先
            val mergedFlv = linkedMapOf("ORIGIN" to originMain.flv + "&codec=" + originMain.vCodec)
            val mergedHls = linkedMapOf<String, String>("ORIGIN" to originMain.hls + "&codec=" + originMain.vCodec)
            mergedFlv.putAll(flvMap)
            mergedHls.putAll(hlsMap)
            return DouyinWebRoom(anchorName, status, title, mergedFlv, mergedHls)
        }
        return DouyinWebRoom(anchorName, status, title, flvMap, hlsMap)
    }

    /**
     * ORIGIN 原画档提取（上游 match_json_str2 / match_json_str3 两路）：
     * - common 脚本块存在 → 按 stream_orientation 选块（1→[0]，否则 [1]，越界上游 IndexError 兜底）
     * - 不存在 → 全页清洗后 `"origin":{"main":...,"dash"` 回落正则
     *
     * 不用 JSONObject 解析：fixture 里 nested sdk_params JSON 做了 HTML 层转义
     * （`\"` → `\\\"`），全量 `}"` → `}` 替换会误删嵌套 string value 中的闭合 `}`，
     * 导致 org.json（严格）解析失败。Python json 库更宽容，故直接正则提取目标字段，
     * 同时对齐上游语义（只取 flv/hls/sdk_params.VCodec）。
     */
    private fun extractOriginMain(html: String, orientation: Int): OriginMain? {
        val blocks = REGEX_ORIGIN_BLOCKS.findAll(html).map { it.groupValues[1] }.toList()
        if (blocks.isNotEmpty()) {
            val idx = if (orientation == 1) 0 else 1
            if (idx >= blocks.size) throw IndexOutOfBoundsException("origin block index $idx")
            val cleaned = blocks[idx].replace("\\", "").replace("u0026", "&")
            val main = REGEX_ORIGIN_MAIN.find(cleaned) ?: return null
            return parseMainInner(main.groupValues[1])
        }
        val cleanedHtml = html.replace("\\", "").replace("u0026", "&")
        val m3 = REGEX_ORIGIN_FALLBACK.find(cleanedHtml) ?: return null
        // 回落路径：group1 即 main 对象内容（上游 json.loads(group1 + '}')），直接解析字段
        return parseMainInner(m3.groupValues[1] + "}")
    }

    /** 从 main 对象内容（`{"flv":...,"hls":...,"sdk_params":...}`）提取三字段。 */
    private fun parseMainInner(inner: String): OriginMain? {
        val flv = REGEX_FLVAL.find(inner)?.groupValues?.get(1) ?: return null
        val hls = REGEX_HLSVAL.find(inner)?.groupValues?.get(1) ?: return null
        // VCodec 只在 origin.main.sdk_params 里取（块内其他画质档也有 VCodec，必须限定 main 子块）
        val vCodec = REGEX_SDK_VCODEC.find(inner)?.groupValues?.get(1) ?: ""
        return OriginMain(flv, hls, vCodec)
    }

    /** origin.main 三个字段的容器（减少 intermediate JSONObject 依赖）。 */
    private data class OriginMain(val flv: String, val hls: String, val vCodec: String)

    /**
     * HTML 路径专用：直接读 stream_url 下的 flv_pull_url / hls_pull_url_map。
     * 上游 HTML 路径不解析 stream_data（ORIGIN 来自脚本块，非 stream_data），
     * 与 web/app API 路径的 DouyinRoomStreams.parseQualityMaps 语义不同。
     */
    /**
     * HTML 路径专用：直接读 stream_url 下的 flv_pull_url / hls_pull_url_map。
     * 上游 HTML 路径不解析 stream_data（ORIGIN 来自脚本块，非 stream_data），
     * 与 web/app API 路径的 DouyinRoomStreams.parseQualityMaps 语义不同。
     *
     * 用正则按 JSON 文档序提取（org.json JSONObject 走 HashMap，keys() 无序，
     * 丢掉上游 Python dict 的插入序 → ORIGIN 后的画质档序错乱）。
     */
    private fun plainQualityMaps(roomJson: String): Pair<Map<String, String>, Map<String, String>> {
        fun plain(key: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            val mapBody = Regex(""""$key":\{([^{}]*)\}""").find(roomJson)?.groupValues?.get(1)
                ?: return out
            for (m in REGEX_MAP_PAIR.findAll(mapBody)) {
                out[m.groupValues[1]] = m.groupValues[2]
            }
            return out
        }
        return plain("flv_pull_url") to plain("hls_pull_url_map")
    }

    private companion object {
        // state JSON 块（正则与上游逐字符一致，真实直播页已验证命中）
        private val REGEX_STATE = Regex("""(\{\\"state\\":.*?)]\\n"]\)""")
        private val REGEX_STATE_ALT = Regex("""(\{\\"common\\":.*?)]\\n"]\)</script><div hidden""")
        private val REGEX_ROOM_STORE = Regex(""""roomStore":(.*?),"linkmicStore"""")

        // nickname 截取（上游 DOTALL）
        private val REGEX_NICKNAME = Regex(""""nickname":"(.*?)","avatar_thumb""", RegexOption.DOT_MATCHES_ALL)

        // ORIGIN 所在 common 脚本块（开引号在组外，同上游 findall）
        // 注意：raw string 内容以 `=` 结尾时闭合引号必须恰好 3 个——多写一个 `"` 会被
        // Kotlin 的"最大引号串"规则吞进正则（nonce=" ≠ 上游 nonce=），导致 0 命中
        private val REGEX_ORIGIN_BLOCKS = Regex(""""(\{\\"common\\":.*?)"]\)</script><script nonce=""")

        // 全页清洗后的 ORIGIN 回落正则（上游 match_json_str3）
        private val REGEX_ORIGIN_FALLBACK = Regex(""""origin":\{"main":(.*?),"dash""", RegexOption.DOT_MATCHES_ALL)

        // 解析 blocks[idx] 清洗后字符串（绕 org.json 严格解析）：提取 origin.main 子块
        // upstream json.loads 宽容，这里用正则对齐语义，只取 flv/hls/sdk_params.VCodec
        private val REGEX_ORIGIN_MAIN = Regex(""""origin":\{"main":(\{.*?\})"?,?\}""", RegexOption.DOT_MATCHES_ALL)
        private val REGEX_FLVAL = Regex(""""flv":"([^"]+)"""")
        private val REGEX_HLSVAL = Regex(""""hls":"([^"]+)"""")
        private val REGEX_SDK_VCODEC = Regex(""""VCodec":"([^"]+)"""")

        private val REGEX_SDK_VCODEC = Regex(""""VCodec":"([^"]+)"""")
        // 画质表键值对（"KEY":"url"，URL 无引号，安全）；文档序保序
        private val REGEX_MAP_PAIR = Regex(""""([^"]+)":"([^"]+)"""")

        // 上游 get_douyin_stream_data 专用：PC Firefox UA + 长 Cookie
        private const val HTML_COOKIE =
            "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511; my_rd=1; passport_csrf_token=3ab34460fa656183fccfb904b16ff742; passport_csrf_token_default=3ab34460fa656183fccfb904b16ff742; d_ticket=9f562383ac0547d0b561904513229d76c9c21; n_mh=hvnJEQ4Q5eiH74-84kTFUyv4VK8xtSrpRZG1AhCeFNI; store-region=cn-fj; store-region-src=uid; LOGIN_STATUS=1; __security_server_data_status=1; FORCE_LOGIN=%7B%22videoConsumedRemainSeconds%22%3A180%7D; pwa2=%223%7C0%7C3%7C0%22; download_guide=%223%2F20230729%2F0%22; volume_info=%7B%22isUserMute%22%3Afalse%2C%22isMute%22%3Afalse%2C%22volume%22%3A0.6%7D; strategyABtestKey=%221690824679.923%22; stream_recommend_feed_params=%22%7B%5C%22cookie_enabled%5C%22%3Atrue%2C%5C%22screen_width%5C%22%3A1536%2C%5C%22screen_height%5C%22%3A864%2C%5C%22browser_online%5C%22%3Atrue%2C%5C%22cpu_core_num%5C%22%3A8%2C%5C%22device_memory%5C%22%3A8%2C%5C%22downlink%5C%22%3A10%2C%5C%22effective_type%5C%22%3A%5C%224g%5C%22%2C%5C%22round_trip_time%5C%22%3A150%7D%22; VIDEO_FILTER_MEMO_SELECT=%7B%22expireTime%22%3A1691443863751%2C%22type%22%3Anull%7D; home_can_add_dy_2_desktop=%221%22; __live_version__=%221.1.1.2169%22; device_web_cpu_core=8; device_web_memory_size=8; xgplayer_user_id=346045893336; csrf_session_id=2e00356b5cd8544d17a0e66484946f28; odin_tt=724eb4dd23bc6ffaed9a1571ac4c757ef597768a70c75fef695b95845b7ffcd8b1524278c2ac31c2587996d058e03414595f0a4e856c53bd0d5e5f56dc6d82e24004dc77773e6b83ced6f80f1bb70627; __ac_nonce=064caded4009deafd8b89; __ac_signature=_02B4Z6wo00f01HLUuwwAAIDBh6tRkVLvBQBy9L-AAHiHf7; ttcid=2e9619ebbb8449eaa3d5a42d8ce88ec835; webcast_leading_last_show_time=1691016922379; webcast_leading_total_show_times=1; webcast_local_quality=sd; live_can_add_dy_2_desktop=%221%22; msToken=1JDHnVPw_9yTvzIrwb7cQj8dCMNOoesXbA_IooV8cezcOdpe4pzusZE7NB7tZn9TBXPr0ylxmv-KMs5rqbNUBHP4P7VBFUu0ZAht_BEylqrLpzgt3y5ne_38hXDOX8o=; msToken=jV_yeN1IQKUd9PlNtpL7k5vthGKcHo0dEh_QPUQhr8G3cuYv-Jbb4NnIxGDmhVOkZOCSihNpA2kvYtHiTW25XNNX_yrsv5FN8O6zm3qmCIXcEe0LywLn7oBO2gITEeg=; tt_scid=mYfqpfbDjqXrIGJuQ7q-DlQJfUSG51qG.KUdzztuGP83OjuVLXnQHjsz-BRHRJu4e986"

        private const val HTML_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    }
}