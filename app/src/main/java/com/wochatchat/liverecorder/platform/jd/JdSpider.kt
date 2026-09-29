package com.wochatchat.liverecorder.platform.jd

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * 京东直播（spider.py:3108 get_jd_stream_url）。
 * GET 重定向 → finalUrl 含 authorId 或 liveId
 * → POST talent_head_findTalentMsg（authorId→talentName）→ liveId
 * → POST getImmediatePlayToM → videoUrl/h5VideoUrl，recordUrl = m3u8。
 */
open class JdSpider(private val client: LiveHttpClient = LiveHttpClient()) {
    companion object {
        private const val WEB_UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"
        fun isJdUrl(url: String) = url.contains("lives.jd.com/")
    }

    data class JdStreamInfo(
        val anchorName: String = "", val title: String = "", val isLive: Boolean = false,
        val m3u8Url: String = "", val flvUrl: String = "", val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(url: String, proxyAddr: String? = null, cookie: String? = null): JdStreamInfo {
        val c = if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
        val headers = mapOf(
            "User-Agent" to WEB_UA,
            "origin" to "https://lives.jd.com",
            "referer" to "https://lives.jd.com/",
            "x-referer-page" to "https://lives.jd.com/",
        )
        // GET → follow redirects → finalUrl 含 authorId/liveId（上游 redirect_url=True）
        val pageResp = try { c.get(url, headers) } catch (e: Exception) { return JdStreamInfo() }
        val finalUrl = pageResp.finalUrl
        val authorId = Regex("authorId=([^&]+)").find(finalUrl)?.groupValues?.getOrNull(1)

        var anchorName = ""
        var title = ""
        // liveId 优先级：URL 直带 → livingRoomJump.params.id → #/{id}?origin 锚点
        var liveId = Regex("[?&]live[Ii]d=([^&#]+)").find(finalUrl)?.groupValues?.getOrNull(1).orEmpty()

        if (!authorId.isNullOrBlank()) {
            val info = postJson(c, "https://api.m.jd.com/talent_head_findTalentMsg", headers,
                "talent_head_findTalentMsg", "dr_detail",
                """{"authorId":"$authorId","monitorSource":"1","userId":""}""")
            anchorName = info.optJSONObject("result")?.optString("talentName", "") ?: ""
            liveId = info.optJSONObject("result")?.optJSONObject("livingRoomJump")
                ?.optJSONObject("params")?.optString("id", "")?.takeIf { it.isNotBlank() } ?: liveId
            val contents = postJson(c, "https://api.m.jd.com/jdTalentContentList", headers,
                "jdTalentContentList", "dr_detail",
                """{"authorId":"$authorId","type":1,"userId":"","page":1,"offset":"-1","monitorSource":"1","pageSize":1}""")
                .optJSONObject("result")?.optJSONArray("content")
            if (contents != null && contents.length() > 0) {
                title = contents.optJSONObject(0)?.optString("title", "") ?: ""
            }
        } else if (liveId.isBlank()) {
            // 上游备用：#/{liveId}?origin（无 authorId → anchor=jd_{liveId}）
            val m = Regex("#/(.+?)\\?origin").find(finalUrl)
            liveId = m?.groupValues?.getOrNull(1).orEmpty()
            if (liveId.isNotBlank()) anchorName = "jd_$liveId"
        }

        if (liveId.isBlank()) return JdStreamInfo(anchorName = anchorName)

        val playData = try {
            JSONObject(c.postForm(
                "https://api.m.jd.com/client.action", headers,
                mapOf("functionId" to "getImmediatePlayToM", "appid" to "h5-live",
                    "body" to """{"liveId": "$liveId"}"""),
            ).text)
        } catch (e: Exception) { JSONObject() }.optJSONObject("data") ?: JSONObject()
        if (playData.optInt("status", 0) != 1) return JdStreamInfo(anchorName = anchorName)
        return JdStreamInfo(
            anchorName = anchorName,
            title = title,
            isLive = true,
            flvUrl = playData.optString("videoUrl", ""),
            m3u8Url = playData.optString("h5VideoUrl", ""),
            recordUrl = playData.optString("h5VideoUrl", ""),
        )
    }

    /** POST 表单 + 解析 JSON（上游 data=urlencode 语义）。失败返回空对象。 */
    private suspend fun postJson(c: LiveHttpClient, url: String, headers: Map<String, String>, functionId: String, appid: String, body: String): JSONObject = try {
        JSONObject(c.postForm(url, headers, mapOf("functionId" to functionId, "appid" to appid, "body" to body)).text)
    } catch (e: Exception) { JSONObject() }
}