package com.wochatchat.liverecorder.platform.yy

import com.wochatchat.liverecorder.net.LiveHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern
/*
 * YySpider — Phase 4 第二批：YY 直播爬虫。
 * 对照上游 spider.py:613 get_yy_stream_data + stream.py:329 get_yy_stream_url。
 *
 * 1. GET www.yy.com/{cid} → 正则提取 anchor（nick）+ sid
 * 2. POST stream-manager.yy.com/v3/channel/streams（JSON body，gear=4 ssl=1）
 * 3. GET www.yy.com/live/detail?sid=… → title
 * avp_info_res 存在即开播，stream_line_addr 第一个 CDN 的 flv 即流地址。
 */
open class YySpider(
    private val client: LiveHttpClient = LiveHttpClient(),
) {
    companion object {
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0"
        private const val DETAIL_API = "https://www.yy.com/live/detail"

        /** 上游 main.py:643：URL 含 https://www.yy.com/ 才走 YY 链路。 */
        fun isYyUrl(url: String): Boolean = url.contains("www.yy.com/")

        /** 房间号 = ? 前最后一段（上游入口 url 即房间页）。 */
        fun parseSid(url: String): String? =
            url.split("?").first().substringAfterLast("/").ifEmpty { null }

        /** buildStreamManagerBody：上游 spider.py:630 的固定 JSON body（gear=4 ssl=1）。 */
        private fun buildStreamManagerBody(cid: String, seq: Long, sendTime: Long): String {
            val head = JSONObject().apply {
                put("seq", seq)
                put("appidstr", "0")
                put("bidstr", "121")
                put("cidstr", cid)
                put("sidstr", cid)
                put("uid64", 0)
                put("client_type", 108)
                put("client_ver", "5.17.0")
                put("stream_sys_ver", 1)
                put("app", "yylive_web")
                put("playersdk_ver", "5.17.0")
                put("thundersdk_ver", "0")
                put("streamsdk_ver", "5.17.0")
            }
            val clientAttr = JSONObject().apply {
                put("client", "web")
                put("model", "web0")
                put("cpu", "")
                put("graphics_card", "")
                put("os", "chrome")
                put("osversion", "0")
                put("vsdk_version", "")
                put("app_identify", "")
                put("app_version", "")
                put("business", "")
                put("width", "1920")
                put("height", "1080")
                put("scale", "")
                put("client_type", 8)
                put("h265", 0)
            }
            val avpParam = JSONObject().apply {
                put("version", 1)
                put("client_type", 8)
                put("service_type", 0)
                put("imsi", 0)
                put("send_time", sendTime)
                put("line_seq", -1)
                put("gear", 4)
                put("ssl", 1)
                put("stream_format", 0)
            }
            return JSONObject().apply {
                put("head", head)
                put("client_attribute", clientAttr)
                put("avp_parameter", avpParam)
            }.toString()
        }
    }

    /**
     * YY 流信息（对齐上游 get_yy_stream_data 返回 dict + stream.get_yy_stream_url 选取语义）：
     * - isLive = avp_info_res 存在（上游 stream.py:337）
     * - flvUrl = stream_line_addr 第一个条目的 cdn_info.url
     * - quality 恒 "OD"（上游硬编码）
     */
    data class YyStreamInfo(
        val anchorName: String = "",
        val cid: String = "",
        val avpInfoRes: JSONObject? = null,
        val title: String = "",
    ) {
        val isLive: Boolean get() = avpInfoRes != null
        val quality: String get() = if (isLive) "OD" else ""

        val flvUrl: String
            get() = avpInfoRes
                ?.optJSONObject("stream_line_addr")
                ?.let { sla -> sla.keys().asSequence().firstOrNull()?.let { sla.optJSONObject(it) } }
                ?.optJSONObject("cdn_info")
                ?.optString("url", "")
                ?: ""
    }

    /**
     * 房间信息 + 取流一步到位（上游两步合并，返回抖音同构所需全部字段）：
     * GET 房间页（正则 nick/sid）→ POST stream-manager → GET detail 标题。
     * 页面正则不命中 / 请求异常 → 空结果（上游 trace_error_decorator 兜底语义）。
     */
    open suspend fun getYyStreamInfo(url: String, proxyAddr: String? = null, cookie: String? = null): YyStreamInfo {
        val headers = buildMap {
            put("User-Agent", WEB_UA)
            put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            put("Referer", "https://www.yy.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        // 1. 房间页 → nick + sid（spider.py:621-622）
        val html = try { c.get(url, headers).text } catch (e: Exception) { return YyStreamInfo() }
        val nickMatcher = Pattern.compile("nick: \"(.*?)\",\\n\\s+logo").matcher(html)
        val anchorName = if (nickMatcher.find()) nickMatcher.group(1) else ""
        val sidMatcher = Pattern.compile("sid : \"(.*?)\",\\n\\s+ssid", Pattern.DOTALL).matcher(html)
        val cid = (if (sidMatcher.find()) sidMatcher.group(1) else null)
            ?: parseSid(url)
            ?: return YyStreamInfo(anchorName = anchorName)

        // 2. stream-manager 取流（spider.py:627-644）
        val nowMs = System.currentTimeMillis()
        val body = buildStreamManagerBody(cid, nowMs, nowMs / 1000)
        val smUrl = "https://stream-manager.yy.com/v3/channel/streams" +
            "?uid=0&cid=$cid&sid=$cid&appid=0&sequence=$nowMs&encode=json"
        val avpInfoRes = try {
            val resp = c.post(
                smUrl,
                headers,
                body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()),
            )
            val json = JSONObject(resp.text)
            json.optJSONObject("avp_info_res")
        } catch (e: Exception) {
            null
        }

        // 3. 标题（spider.py:650-653 detail API data.roomName）
        val title = try {
            val detailParams = "uid=&sid=$cid&ssid=$cid&_=$nowMs"
            val detail = JSONObject(c.get("$DETAIL_API?$detailParams", headers).text)
            detail.optJSONObject("data")?.optString("roomName", "") ?: ""
        } catch (e: Exception) {
            ""
        }
        return YyStreamInfo(anchorName = anchorName, cid = cid, avpInfoRes = avpInfoRes, title = title)
    }

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)
}

