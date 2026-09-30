package com.wochatchat.liverecorder.platform.liujian

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.FormBody
import java.util.regex.Pattern

/**
 * LiuJianFangSpider — Phase 9f：六间房直播爬虫。
 * 对照上游 spider.py:2908 get_6room_stream_url。
 *
 * 主流程（两阶段）：
 * 1. GET https://v.6.cn/{roomId} → HTML → regex 提取 real roomId（rid）
 * 2. POST https://v.6.cn/coop/mobile/index.php?padapi=coop-mobile-inroom.php
 *    → liveinfo.flvtitle / roominfo.alias
 * 3. flvtitle 非空时 isLive=true，flvUrl = https://wlive.6rooms.com/httpflv/{flvtitle}.flv
 */
open class LiuJianFangSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private val RE_RID = Pattern.compile("rid: '(.*?)',\n\\s+roomid")

        private const val UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        fun parseRoomId(url: String): String =
            url.split("?").first().substringAfterLast("/")
    }

    data class LiuJianFangStreamInfo(
        val anchorName: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): LiuJianFangStreamInfo {
        val roomId = parseRoomId(url)
        if (roomId.isBlank()) return LiuJianFangStreamInfo()
        val headers = buildMap {
            put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("Referer", "https://ios.6.cn/?ver=8.0.3&build=4")
            put("User-Agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        // 阶段1：从房间页提取 real rid
        val pageResp = runCatching {
            c.get("https://v.6.cn/$roomId", headers)
        }.getOrNull() ?: return LiuJianFangStreamInfo()
        val rid = RE_RID.toRegex().find(pageResp.text)?.groupValues?.get(1) ?: roomId

        // 阶段2：POST 获取直播信息
        val body = FormBody.Builder()
            .add("av", "3.1")
            .add("encpass", "")
            .add("logiuid", "")
            .add("project", "v6iphone")
            .add("rate", "1")
            .add("rid", "")
            .add("ruid", rid)
            .build()
        val apiResp = runCatching {
            c.post("https://v.6.cn/coop/mobile/index.php?padapi=coop-mobile-inroom.php", headers, body)
        }.getOrNull() ?: return LiuJianFangStreamInfo()
        val json = runCatching { org.json.JSONObject(apiResp.text) }.getOrNull()
            ?: return LiuJianFangStreamInfo()

        val anchorName = json.optJSONObject("content")
            ?.optJSONObject("roominfo")
            ?.optString("alias", "") ?: ""
        val liveInfo = json.optJSONObject("content")?.optJSONObject("liveinfo")
        val flvtitle = liveInfo?.optString("flvtitle", "") ?: ""

        if (flvtitle.isBlank()) {
            return LiuJianFangStreamInfo(anchorName = anchorName)
        }
        val flvUrl = "https://wlive.6rooms.com/httpflv/$flvtitle.flv"
        return LiuJianFangStreamInfo(
            anchorName = anchorName,
            isLive = true,
            flvUrl = flvUrl,
            recordUrl = flvUrl,
        )
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
