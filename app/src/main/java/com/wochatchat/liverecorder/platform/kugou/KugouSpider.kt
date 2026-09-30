package com.wochatchat.liverecorder.platform.kugou

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * KugouSpider — Phase 9d：酷狗直播爬虫。
 * 对照上游 spider.py:2054 get_kugou_stream_url。
 *
 * 主流程（两 API）：
 * 1. GET service2.fanxing.kugou.com/roomcen/room/web/cdn/getEnterRoomInfo?roomId={room_id}
 *    → nickName（主播名）+ liveType（-1 未播；≠-1 开播）
 * 2. 开播时 GET fx1.service.kugou.com/video/pc/live/pull/mutiline/streamaddr?...（含时间戳参数）
 *    → stream_data.lines[-1].streamProfiles[0].httpsFlv[0]（HTTPS FLV）
 */
open class KugouSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_ROOMID = Pattern.compile("roomId=(\\d+)")

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"

        fun parseRoomId(url: String): String {
            // 上游：URL 含 roomId= 时用正则提取（fanxing 分享链），否则取路径末段
            val m = RE_ROOMID.matcher(url)
            if (m.find()) return m.group(1)!!
            return url.split("?").first().substringAfterLast("/")
        }
    }

    data class KugouStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): KugouStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return KugouStreamInfo()
        val headers = buildMap {
            put("User-Agent", UA)
            put("Accept", "application/json")
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            put("Referer", "https://fanxing2.kugou.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)
        val infoUrl = "https://service2.fanxing.kugou.com/roomcen/room/web/cdn/getEnterRoomInfo?roomId=$roomId"
        val infoResp = runCatching {
            c.get(infoUrl, headers)
        }.getOrNull() ?: return KugouStreamInfo()
        val infoRoot = runCatching { JSONObject(infoResp.text) }.getOrNull()
            ?: return KugouStreamInfo()
        val normalRoom = infoRoot.optJSONObject("data")?.optJSONObject("normalRoomInfo")
            ?: return KugouStreamInfo()
        val anchorName = normalRoom.optString("nickName", "")
        if (anchorName.isBlank()) return KugouStreamInfo() // 不支持的频道（上游 raise）
        val liveType = infoRoot.optJSONObject("data")?.optInt("liveType", -1) ?: -1
        if (liveType == -1) {
            return KugouStreamInfo(anchorName = anchorName)
        }
        // 开播：请求流地址
        val ts = System.currentTimeMillis()
        val params = mapOf(
            "std_rid" to roomId,
            "std_plat" to "7",
            "std_kid" to "0",
            "streamType" to "1-2-4-5-8",
            "ua" to "fx-flash",
            "targetLiveTypes" to "1-5-6",
            "version" to "1000",
            "supportEncryptMode" to "1",
            "appid" to "1010",
            "_" to ts.toString(),
        )
        val streamUrl = "https://fx1.service.kugou.com/video/pc/live/pull/mutiline/streamaddr?" +
            params.entries.joinToString("&") {
                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
        val streamResp = runCatching {
            c.get(streamUrl, headers)
        }.getOrNull() ?: return KugouStreamInfo(anchorName = anchorName)
        val streamRoot = runCatching { JSONObject(streamResp.text) }.getOrNull()
            ?: return KugouStreamInfo(anchorName = anchorName)
        val lines = streamRoot.optJSONObject("data")?.optJSONArray("lines") ?: return KugouStreamInfo(anchorName = anchorName)
        if (lines.length() == 0) return KugouStreamInfo(anchorName = anchorName)
        val lastLine = lines.optJSONObject(lines.length() - 1) ?: return KugouStreamInfo(anchorName = anchorName)
        val profiles = lastLine.optJSONArray("streamProfiles") ?: return KugouStreamInfo(anchorName = anchorName)
        if (profiles.length() == 0) return KugouStreamInfo(anchorName = anchorName)
        val firstProfile = profiles.optJSONObject(0) ?: return KugouStreamInfo(anchorName = anchorName)
        val flvUrl = runCatching {
            val arr = firstProfile.optJSONArray("httpsFlv")
            if (arr != null && arr.length() > 0) arr.getString(0) else ""
        }.getOrDefault("")
        if (flvUrl.isBlank()) return KugouStreamInfo(anchorName = anchorName)
        return KugouStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = flvUrl,
            recordUrl = flvUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}