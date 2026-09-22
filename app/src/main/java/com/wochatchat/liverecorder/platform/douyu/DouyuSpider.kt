/*
 * DouyuSpider — Phase 3d：斗鱼爬虫（对照 spider.py get_douyu_info_data / get_douyu_stream_data）。
 *
 * get_douyu_info_data（spider.py:548）：
 *   ① 解析 URL 中的 rid；② 抓 m.douyu.com/{rid} 的 vike_pageContext JSON 提取真实 rid；
 *   ③ 抓 www.douyu.com/betard/{rid} 得主播昵称/开播状态/标题。
 *
 * get_douyu_stream_data（spider.py:583）：
 *   ① get_token_js 抓 m.douyu.com/{rid}（含 ub98484234 脚本）→ 签名参数；
 *   ② POST www.douyu.com/lapi/live/getH5Play/{rid}（body 含 v/did/tt/sign/ver/rid/rate）；
 *   ③ 解析返回流 URL（支持 flv/hls）。
 *
 * 签名注入：eval: (String)->String（生产 QuickJsEngine::eval，单测 RhinoJsEngine::eval）。
 */
package com.wochatchat.liverecorder.platform.douyu

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/** 斗鱼爬虫：房间信息查询 + 流地址获取 */
class DouyuSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val jsEngine: (String) -> String = { code -> com.wochatchat.liverecorder.sign.QuickJsEngine.eval(code) },
) {
    companion object {
        private val RE_RID_PARAM = Regex("""rid=(.*?)(?=&|$)""")
        private val RE_RID_PATH = Regex("""douyu\.com/(\d+)""")
        private const val BETARD_API = "https://www.douyu.com/betard/%s"
        private const val H5PLAY_API = "https://www.douyu.com/lapi/live/getH5Play/%s"
        private const val M_ROOM_URL = "https://m.douyu.com/%s"
        private val M_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
            "Referer" to "https://m.douyu.com/",
        )
        private const val VIDEO_LOOP = 0  // 非循环播放（录播材料）
        private const val LIVE_SHOW_STATUS = 1  // show_status=1 才算开播

        /** 提取 URL 中的房间号（上游优先级：rid= 参数 → douyu.com/ 纯数字路径段） */
        fun parseRidFromUrl(url: String): String? {
            RE_RID_PARAM.find(url)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }?.let { return it }
            return RE_RID_PATH.find(url)?.groupValues?.get(1)
        }
    }

    /**
     * 获取房间信息（对照 spider.py get_douyu_info_data）。
     * @param url 斗鱼房间 URL（支持 https://www.douyu.com/xxx 或含 rid= 参数）
     * @return DouyuInfo（主播昵称 / 开播状态 / 标题 / 房间号）
     * @throws IllegalStateException 网络请求或解析失败
     */
    suspend fun getDouyuInfo(url: String): DouyuInfo {
        val rid = parseRidFromUrl(url)
            ?: throw IllegalStateException("douyu: cannot parse rid from url: $url")

        // ① 抓 m.douyu.com 获取真实 rid（vike_pageContext 内嵌 JSON 含 room_id）
        val mHtml = client.get(mRoomUrl(rid), headers = M_HEADERS).text
        val actualRid = extractActualRid(mHtml, rid)

        // ② 抓 betard 接口获取房间信息
        val json = client.get(betardUrl(actualRid), headers = M_HEADERS).text
        return parseBetardInfo(json)
    }

    /**
     * 获取直播流信息（对照 spider.py get_douyu_stream_data）。
     * @param rid 房间号（需已通过 getDouyuInfo 获取真实 rid）
     * @param rate 画质档（0=蓝光、3=超清、2=高清、-1=默认）
     * @param did 设备 ID（默认走 DouyuSign.DEFAULT_DID）
     * @param t10 时间戳（默认当前时间十位字符串）
     */
    suspend fun getDouyuStreamData(
        rid: String,
        rate: String = "-1",
        did: String = DouyuSign.DEFAULT_DID,
        t10: String = (System.currentTimeMillis() / 1000).toString(),
    ): DouyuStreamInfo {
        // ① 抓 m.douyu.com 用于签名提取
        val mHtml = client.get(mRoomUrl(rid), headers = M_HEADERS).text
        val actualRid = extractActualRid(mHtml, rid)

        // ② 生成签名参数
        val paramsList = DouyuSign.getTokenParams(jsEngine, mHtml, actualRid, did, t10)
        require(paramsList.size == 4) { "douyu: expected 4 token params, got ${paramsList.size}" }
        val (v, _, tt, sign) = paramsList

        // ③ POST 获取流 URL（上游 form-encoded，不是 json）
        val formData = mapOf(
            "v" to v,
            "did" to did,
            "tt" to tt,
            "sign" to sign,
            "ver" to "22011191",
            "rid" to actualRid,
            "rate" to rate,
        )
        val json = client.postForm(h5playUrl(actualRid), M_HEADERS, formData).text
        return parseH5PlayResponse(json, actualRid)
    }

    // ---- internal ----

    private fun mRoomUrl(rid: String) = M_ROOM_URL.format(rid)
    private fun betardUrl(rid: String) = BETARD_API.format(rid)
    private fun h5playUrl(rid: String) = H5PLAY_API.format(rid)

    /**
     * 从 m.douyu.com HTML 的 <script id="vike_pageContext"> 提取真实 room_id。
     * 上游 fallback 流程：rid 在 URL 里是假的时走此路径。
     */
    internal fun extractActualRid(mHtml: String, fallbackRid: String): String {
        val m = Regex("""<script id="vike_pageContext" type="application/json">([^<]+)</script>""")
            .find(mHtml)?.groupValues?.get(1)
            ?: return fallbackRid
        return try {
            val obj = JSONObject(m)
            obj.getJSONObject("pageProps")
                .getJSONObject("room")
                .getJSONObject("roomInfo")
                .getJSONObject("roomInfo")
                .getInt("rid")
                .toString()
        } catch (e: Exception) {
            fallbackRid
        }
    }

    /** 解析 betard 接口返回的 DouyuInfo */
    internal fun parseBetardInfo(jsonStr: String): DouyuInfo {
        val j = JSONObject(jsonStr)
        val room = j.getJSONObject("room")
        val isLive = room.optInt("videoLoop") == VIDEO_LOOP &&
                room.optInt("show_status") == LIVE_SHOW_STATUS
        val anchorName = room.optString("nickname", "未知主播")
        val title = if (isLive) room.optString("roomName", "").replace("&nbsp;", " ") else null
        val roomId = room.optString("room_id", null)
        return DouyuInfo(
            anchorName = anchorName,
            isLive = isLive,
            title = title,
            roomId = roomId,
        )
    }

    /** 解析 getH5Play 返回流信息（支持 flv/hls） */
    internal fun parseH5PlayResponse(jsonStr: String, rid: String): DouyuStreamInfo {
        val j = JSONObject(jsonStr)
        val room = j.optJSONObject("data") ?: j
        val anchorName = room.optString("nickname", "未知主播")
        // 优先 hls 再 flv
        val streamUrl = room.optString("hls_url", null)
            ?: room.optString("rtmp_url", null)
            ?.let { "$it/${room.optString("rtmp_live", "")}" }
            ?: room.optString("flv_url", null)
        // FLV 直下 URL（上游 get_douyu_stream_url 组合 rtmp_url/rtmp_live 作为 flv_url/record_url）
        val flvUrl = room.optString("rtmp_url", null)?.let { rtmp ->
            val live = room.optString("rtmp_live", null)
            if (live.isNullOrBlank()) rtmp else "$rtmp/$live"
        }
        val qualityLabel = room.optString("rate", null)?.let {
            when (it) {
                "0" -> "蓝光"
                "3" -> "超清"
                "2" -> "高清"
                "1" -> "标清"
                else -> it
            }
        }
        return DouyuStreamInfo(
            roomId = rid,
            anchorName = anchorName,
            streamUrl = streamUrl,
            flvUrl = flvUrl,
            qualityLabel = qualityLabel,
            rawJson = jsonStr,
        )
    }
}
