/*
 * LiveMeSpider — 8a：LiveMe 直播（对照 spider.py:2209 get_liveme_stream_url）。
 *
 * 上游要求代理/海外网络（main.py:845 非 proxy 环境直接报错；安卓端沿用海外
 * 直连语义，代理由 monitor 层传入）。
 *
 * 流程（spider.py:2209-2246）：
 *   ① URL 无 index.html 时抓页面 og:url 换真实地址；roomId = 去尾部 /index.html 后末段；
 *   ② liveme.js sign(roomId) → JSON（lm_s_sign/tongdun_black_box/os + 业务参数）；
 *   ③ lm_s_sign 入 lm-s-sign 头，tongdun_black_box/os 剥离成 query，其余为 form body；
 *   ④ POST live.liveme.com/live/queryinfosimple；video_info.status=="0" 开播。
 */
package com.wochatchat.liverecorder.platform.liveme

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.JsScriptRunner
import com.wochatchat.liverecorder.sign.scripts.JsScripts
import org.json.JSONObject

open class LiveMeSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val jsEngine: (String) -> String = { code -> com.wochatchat.liverecorder.sign.QuickJsEngine.eval(code) },
) {

    companion object {
        private const val UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        fun isLiveMeUrl(url: String): Boolean = url.contains("liveme.com")

        /** roomId = url.split("/index.html")[0].rsplit('/', 1)[-1]（上游同语义）。 */
        fun parseRoomId(url: String): String =
            url.split("/index.html").first().trimEnd('/').substringAfterLast("/")
    }

    data class LiveMeStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): LiveMeStreamInfo {
        val headers = buildMap {
            put("origin", "https://www.liveme.com")
            put("referer", "https://www.liveme.com")
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }
        val c = clientOrProxy(proxyAddr)

        var pageUrl = url
        if ("index.html" !in url) {
            val html = try { c.get(url, headers).text } catch (e: Exception) { return LiveMeStreamInfo() }
            pageUrl = extractOgUrl(html) ?: return LiveMeStreamInfo()
        }
        val roomId = pageUrl.split("/index.html").first().trimEnd('/').substringAfterLast("/")

        // sign 结果：{alias, tongdun_black_box, os, lm_s_id, lm_s_ts, lm_s_str, lm_s_ver,
        //             h5, _time, thirdchannel, videoid, area, vali, lm_s_sign}
        val signJson = JsScriptRunner.call(
            jsEngine,
            "sign(${jsonQuote(roomId)}, '__cryptojs__')",
            JsScripts.LIVEME_JS,
            cryptoJs = JsScripts.CRYPTO_JS,
        )
        val signObj = try { org.json.JSONObject(signJson) } catch (e: Exception) { return LiveMeStreamInfo() }
        val lmSSign = signObj.optString("lm_s_sign", "")
        if (lmSSign.isBlank()) return LiveMeStreamInfo()
        val tongdun = signObj.optString("tongdun_black_box", "")
        val os = signObj.optString("os", "web")

        val bodyParams = buildMap {
            signObj.keys().forEach { key ->
                if (key != "lm_s_sign" && key != "tongdun_black_box" && key != "os") {
                    put(key, signObj.opt(key)?.toString() ?: "")
                }
            }
        }
        val apiParams = "alias=liveme&tongdun_black_box=${enc(tongdun)}&os=${enc(os)}"
        val api = "https://live.liveme.com/live/queryinfosimple?$apiParams"
        val resp = try {
            c.postForm(api, headers + mapOf("lm-s-sign" to lmSSign), form = bodyParams)
        } catch (e: Exception) { return LiveMeStreamInfo() }
        return parseResponse(resp.text)
    }

    // ---- internal ----

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    private fun jsonQuote(s: String): String = org.json.JSONObject.quote(s)

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** og:url meta 提取（上游 re.search og:url）。 */
    internal fun extractOgUrl(html: String): String? =
        Regex("""<meta property="og:url" content="(.*?)">""").find(html)?.groupValues?.get(1)

    internal fun parseResponse(jsonStr: String): LiveMeStreamInfo {
        val json = try { org.json.JSONObject(jsonStr) } catch (e: Exception) { return LiveMeStreamInfo() }
        val stream = json.optJSONObject("data")?.optJSONObject("video_info")
            ?: return LiveMeStreamInfo()
        val anchorName = stream.optString("uname", "")
        // 上游 live_status == "0"（字符串）为开播
        if (stream.optString("status", "") != "0") return LiveMeStreamInfo(anchorName = anchorName)
        val m3u8 = stream.optString("hlsvideosource", "")
        val flv = stream.optString("videosource", "")
        return LiveMeStreamInfo(
            anchorName = anchorName,
            isLive = true,
            m3u8Url = m3u8,
            flvUrl = flv,
            recordUrl = m3u8.ifEmpty { flv },
        )
    }
}
