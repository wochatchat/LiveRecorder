/*
 * HaixiuSpider — 8a：嗨秀直播 + 乐嗨直播（对照 spider.py:2727 get_haixiu_stream_url）。
 *
 * 上游同一函数按域名分流：haixiutv.com → service.haixiutv.com + 固定 accessToken A；
 * lehaitv.com → service.lehaitv.com（origin/referer 换乐嗨）+ accessToken B。
 * 签名走 haixiu.js（依赖 crypto-js，JsScriptRunner require 垫片执行）。
 *
 * 流程（spider.py:2727-2765）：
 *   ① roomId = URL 末段；② params（accessToken 固定值/tku/c/_st1=ms）→ JS sign → _ajaxData1；
 *   ③ accessToken 双重 unquote 后入参；④ GET advanceInfoRoom；
 *   ⑤ live_status==1 → media_url_web(flv)。
 */
package com.wochatchat.liverecorder.platform.haixiu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.JsScriptRunner
import com.wochatchat.liverecorder.sign.scripts.JsScripts
import org.json.JSONObject

open class HaixiuSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val jsEngine: (String) -> String = { code -> com.wochatchat.liverecorder.sign.QuickJsEngine.eval(code) },
) {

    companion object {
        private const val UA = "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))"

        /** 上游固定 accessToken（spider.py:2735-2738，URL 双重编码态）。 */
        const val ACCESS_TOKEN_HAIXIU = "pLXSC%252FXJ0asc1I21tVL5FYZhNJn2Zg6d7m94umCnpgL%252BuVm31GQvyw%253D%253D"
        const val ACCESS_TOKEN_LEHAI = "s7FUbTJ%252BjILrR7kicJUg8qr025ZVjd07DAnUQd8c7g%252Fo4OH9pdSX6w%253D%253D"

        fun isHaixiuUrl(url: String): Boolean = url.contains("haixiutv.com") || url.contains("lehaitv.com")

        /** roomId = split('?')[0].rsplit('/', 1)[-1]（上游同语义）。 */
        fun parseRoomId(url: String): String = url.split("?").first().trimEnd('/').substringAfterLast("/")
    }

    data class HaixiuStreamInfo(
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
        nowMs: Long = System.currentTimeMillis(),
    ): HaixiuStreamInfo {
        val isHaixiu = url.contains("haixiutv.com")
        val accessToken = if (isHaixiu) ACCESS_TOKEN_HAIXIU else ACCESS_TOKEN_LEHAI
        val headers = buildMap {
            put("origin", if (isHaixiu) "https://www.haixiutv.com" else "https://www.lehaitv.com")
            put("referer", if (isHaixiu) "https://www.haixiutv.com/" else "https://www.lehaitv.com")
            put("user-agent", UA)
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }

        val roomId = parseRoomId(url)
        val params = linkedMapOf(
            "accessToken" to accessToken,
            "tku" to "3000006",
            "c" to "10138100100000",
            "_st1" to nowMs.toString(),
        )
        // 上游 sign(params, crypto-js 路径) → _ajaxData1。
        // execjs 对 JS 字符串返回原值；这里 JSON.stringify 后需解包一层字符串引号
        val ajaxDataRaw = JsScriptRunner.call(
            jsEngine,
            "sign(${jsonParams(params)}, '__cryptojs__')",
            JsScripts.HAIXIU_JS,
            cryptoJs = JsScripts.CRYPTO_JS,
        )
        val ajaxData = unwrapJson(ajaxDataRaw)

        val finalParams = buildMap {
            putAll(params)
            put("accessToken", doubleUrlDecode(accessToken)) // 上游 urllib.parse.unquote × 2
            put("_ajaxData1", ajaxData)
            put("_", nowMs.toString())
        }
        val host = if (isHaixiu) "service.haixiutv.com" else "service.lehaitv.com"
        val query = finalParams.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val api = "https://$host/v2/room/$roomId/media/advanceInfoRoom?$query"
        val c = clientOrProxy(proxyAddr)
        val json = try { c.get(api, headers).text } catch (e: Exception) { return HaixiuStreamInfo() }
        return parseResponse(json)
    }

    // ---- internal ----

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    private fun jsonParams(params: Map<String, String>): String =
        JSONObject(params).toString()

    /** urllib.parse.unquote 应用两次（%25 → % → 原字符）。 */
    internal fun doubleUrlDecode(s: String): String =
        java.net.URLDecoder.decode(java.net.URLDecoder.decode(s, "UTF-8"), "UTF-8")

    /** JSON 文本 → 原值（字符串解引号，对象保持紧凑 JSON，与 execjs 字符串语义对齐）。 */
    internal fun unwrapJson(json: String): String = try {
        val arr = org.json.JSONArray("[$json]")
        when (val v = arr.get(0)) {
            is String -> v
            is org.json.JSONObject -> v.toString()
            else -> json
        }
    } catch (e: Exception) {
        json
    }

    internal fun parseResponse(jsonStr: String): HaixiuStreamInfo {
        val json = try { JSONObject(jsonStr) } catch (e: Exception) { return HaixiuStreamInfo() }
        val data = json.optJSONObject("data") ?: return HaixiuStreamInfo()
        val anchorName = data.optString("nickname", "")
        if (data.optInt("live_status", 0) != 1) return HaixiuStreamInfo(anchorName = anchorName)
        val flv = data.optString("media_url_web", "")
        return HaixiuStreamInfo(anchorName = anchorName, isLive = true, flvUrl = flv, recordUrl = flv)
    }
}
