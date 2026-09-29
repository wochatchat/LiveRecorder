package com.wochatchat.liverecorder.platform.shopee

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject

/**
 * ShopeeSpider — Phase 9a：Shopee 直播爬虫。
 * 对照上游 spider.py:2943 get_shopee_stream_url。
 *
 * 主流程：
 * - cookie 必须含 _m_h5_tk（无则直接返回未开播，不发请求）
 * - 非 live.shopee URL 跟随重定向取真实 URL（上游 async_req redirect_url=True）
 * - GET /api/v1/shop_page/live/ongoing?uid={uid} → 查是否在播，取 session_id
 * - GET /api/v1/session/{session_id} → status==1 && is_living → flv_url + title
 * record_url = flv（上游 stream.get_shopee_stream_url 语义，flv 优先）。
 */
open class ShopeeSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** URL 模式判断（main.py:960 原文）。 */
        fun isShopeeUrl(url: String): Boolean =
            url.contains("live.shopee") || url.contains("shp.ee")
    }

    data class ShopeeStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): ShopeeStreamInfo {
        if (cookie.isNullOrBlank() || !cookie.contains("_m_h5_tk")) {
            return ShopeeStreamInfo()
        }
        val headers = buildMap {
            put("accept", "application/json, text/plain, */*")
            put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
            put("referer", "https://live.shopee.sg/share?from=live&session=802458&share_user_id=")
            put("User-Agent", WEB_UA)
            put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        // 非 live.shopee URL → 重定向取真实 URL（上游 redirect_url=True）
        val resolvedUrl = if (!url.contains("live.shopee") && !url.contains("uid=")) {
            try {
                c.get(url, headers).finalUrl
            } catch (e: Exception) {
                url
            }
        } else url

        // 提取 host_suffix（spider.py:2969-2972：域名 TLD 作为 shopee 站点后缀）
        val host = resolvedUrl.split("/").getOrNull(2) ?: return ShopeeStreamInfo()
        val hostSuffix = if (resolvedUrl.contains("live.shopee")) {
            host.substringAfterLast(".")
        } else {
            host.substringBefore(".").ifEmpty { host }
        }
        val apiHost = "https://live.shopee.$hostSuffix"

        val uid = extractParam(resolvedUrl, "uid")
        var sessionId = extractParam(resolvedUrl, "session")
        var isLiving = resolvedUrl.contains("live.shopee") && uid != null

        // 有 uid 无 session → 查 ongoing 取 session_id（上游 spider.py:2975-2988）
        if (uid != null && sessionId == null) {
            val ongoingResp = try {
                c.get("$apiHost/api/v1/shop_page/live/ongoing?uid=$uid", headers)
            } catch (e: Exception) {
                null
            }
            val ongoingData = ongoingResp?.let { runCatching { JSONObject(it.text) }.getOrNull() }
                ?.optJSONObject("data")
            val ongoing = ongoingData?.optJSONObject("ongoing_live")
            if (ongoing != null) {
                sessionId = ongoing.optString("session_id", "").ifEmpty { null }
                isLiving = true
            } else {
                // 回放列表兜底（仅填充主播名，不改 is_live）
                val replayResp = try {
                    c.get("$apiHost/api/v1/shop_page/live/replay_list?offset=0&limit=1&uid=$uid", headers)
                } catch (e: Exception) {
                    null
                }
                val replay = replayResp?.let { runCatching { JSONObject(it.text) }.getOrNull() }
                    ?.optJSONObject("data")?.optJSONArray("replay")?.optJSONObject(0)
                if (replay != null) {
                    return ShopeeStreamInfo(anchorName = replay.optString("nick_name", ""))
                }
            }
        }
        if (sessionId == null) return ShopeeStreamInfo()

        // session 详情（上游 spider.py:2991-3000）
        val sessionResp = try {
            c.get("$apiHost/api/v1/session/$sessionId", headers)
        } catch (e: Exception) {
            return ShopeeStreamInfo()
        }
        val sessionObj = runCatching { JSONObject(sessionResp.text) }.getOrNull()
            ?.optJSONObject("data")?.optJSONObject("session")
            ?: return ShopeeStreamInfo()
        val anchorName = sessionObj.optString("nickname", "")
        val liveStatus = sessionObj.optInt("status", 0)
        if (liveStatus != 1 || !isLiving) return ShopeeStreamInfo(anchorName = anchorName)
        val flvUrl = sessionObj.optString("play_url", "")
        if (flvUrl.isEmpty()) return ShopeeStreamInfo(anchorName = anchorName)
        return ShopeeStreamInfo(
            anchorName = anchorName,
            title = sessionObj.optString("title", ""),
            isLive = true,
            flvUrl = flvUrl,
            recordUrl = flvUrl,
        )
    }

    /** 提取 URL 参数值（上游 get_params）。 */
    internal fun extractParam(url: String, name: String): String? {
        val query = url.split("?").getOrNull(1) ?: return null
        return query.split("&").mapNotNull { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == name && kv[1].isNotEmpty()) kv[1] else null
        }.firstOrNull()
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}
