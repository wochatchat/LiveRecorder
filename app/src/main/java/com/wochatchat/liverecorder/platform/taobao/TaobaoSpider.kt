/*
 * TaobaoSpider — 8a：淘宝直播（对照 spider.py:3029 get_taobao_stream_url）。
 *
 * 依赖：cookie 必须含 _m_h5_tk（上游缺 token 时签名流程无法进行）；签名走
 * taobao-sign.js（自包含，无 crypto-js 依赖，QuickJS 直接执行）。
 *
 * 流程（spider.py:3029-3106）：
 *   ① URL ?id= 取 liveId；缺失则抓页面 var url='...' 重定向提取；
 *   ② 两次尝试：t13 + pre_sign(_m_h5_tk 前段&t13&appKey&data) → JS sign；
 *   ③ GET h5api.m.taobao.com mtop jsonp → ret 成功则解析；
 *   ④ streamStatus=='1' → liveUrlList 按 definition 优先级降序（lld→ud），
 *      画质索引取 QUALITY_MAPPING（stream.py:26），record_url = m3u8Url
 *      （上游 stream.get_stream_url url_type='all' 语义）。
 *
 * 差异说明：上游失败分支会从响应 Set-Cookie 续 _m_h5_tk 后重签；安卓端 OkHttp
 * per-request cookie 不可控，简化为同参两次重试，token 过期由用户更新 cookie。
 */
package com.wochatchat.liverecorder.platform.taobao

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.JsScriptRunner
import com.wochatchat.liverecorder.sign.scripts.JsScripts
import org.json.JSONArray
import org.json.JSONObject

open class TaobaoSpider(
    private val client: LiveHttpClient = LiveHttpClient(),
    private val jsEngine: (String) -> String = { code -> com.wochatchat.liverecorder.sign.QuickJsEngine.eval(code) },
) {

    companion object {
        private const val APP_KEY = "12574478"
        private const val API_BASE = "https://h5api.m.taobao.com/h5/mtop.mediaplatform.live.livedetail/4.0/"

        /** 上游 stream.py:26 QUALITY_MAPPING。 */
        val QUALITY_INDEX = mapOf("OD" to 0, "BD" to 0, "UHD" to 1, "HD" to 2, "SD" to 3, "LD" to 4)

        /** 上游 definition 优先级（spider.py:3052）。 */
        val DEFINITION_PRIORITY = mapOf("lld" to 0, "ld" to 1, "md" to 2, "hd" to 3, "ud" to 4)

        fun isTaobaoUrl(url: String): Boolean = url.contains("tb.cn")

        /** URL 查询参数（上游 get_params：parse_qs 取首个）。 */
        fun getParam(url: String, key: String): String? =
            url.substringAfter("?", "").split("&")
                .firstOrNull { it.substringBefore("=") == key }
                ?.substringAfter("=")?.takeIf { it.isNotBlank() }
    }

    data class TaobaoStreamInfo(
        val anchorName: String = "",
        val title: String = "",
        val isLive: Boolean = false,
        val m3u8Url: String = "",
        val flvUrl: String = "",
        val recordUrl: String = "",
        val quality: String = "",
    )

    open suspend fun getStreamInfo(
        url: String,
        quality: String? = null,
        proxyAddr: String? = null,
        cookie: String? = null,
    ): TaobaoStreamInfo {
        // 上游：cookie 缺 _m_h5_tk 时打印错误退出（签名无法计算）
        if ("_m_h5_tk" !in (cookie ?: "")) return TaobaoStreamInfo()
        val headers = mapOf(
            "Referer" to "https://huodong.m.taobao.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0",
            "Cookie" to (cookie ?: ""),
        )
        val c = clientOrProxy(proxyAddr)

        var liveId = getParam(url, "id")
        if (liveId.isNullOrBlank()) {
            val html = try { c.get(url, headers).text } catch (e: Exception) { return TaobaoStreamInfo() }
            val redirect = extractRedirectUrl(html) ?: return TaobaoStreamInfo()
            liveId = getParam(redirect, "id") ?: return TaobaoStreamInfo()
        }

        val mH5Tk = Regex("""_m_h5_tk=(.*?);""").find(cookie ?: "")?.groupValues?.get(1) ?: ""
        val dataJson = """{"liveId":"$liveId","creatorId":null}"""
        repeat(2) {
            val t13 = System.currentTimeMillis()
            val preSign = "${mH5Tk.split("_").firstOrNull() ?: ""}&$t13&$APP_KEY&$dataJson"
            val signJson = JsScriptRunner.call(jsEngine, "sign(${jsonQuote(preSign)})", JsScripts.TAOBAO_SIGN_JS)
            val params = linkedMapOf(
                "jsv" to "2.7.0",
                "appKey" to APP_KEY,
                "t" to t13.toString(),
                "sign" to jsonUnquote(signJson),
                "AntiFlood" to "true",
                "AntiCreep" to "true",
                "api" to "mtop.mediaplatform.live.livedetail",
                "v" to "4.0",
                "preventFallback" to "true",
                "type" to "jsonp",
                "dataType" to "jsonp",
                "callback" to "mtopjsonp1",
                "data" to dataJson,
            )
            val query = params.entries.joinToString("&") { (k, v) ->
                "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
            }
            val jsonp = try { c.get("$API_BASE?$query", headers).text } catch (e: Exception) { return TaobaoStreamInfo() }
            val json = jsonpToJson(jsonp) ?: return TaobaoStreamInfo()
            val ret = json.optJSONArray("ret")?.optString(0) ?: ""
            if (ret == "SUCCESS::调用成功") {
                val data = json.optJSONObject("data") ?: return TaobaoStreamInfo()
                return parseLive(data, quality)
            }
            // 失败：同参重试一次（上游会续 token 重签，安卓端简化）
        }
        return TaobaoStreamInfo()
    }

    // ---- internal ----

    private fun clientOrProxy(proxyAddr: String?): LiveHttpClient =
        if (proxyAddr.isNullOrBlank()) client else LiveHttpClient(proxyAddr)

    private fun jsonQuote(s: String): String = JSONObject.quote(s)

    private fun jsonUnquote(s: String): String {
        val arr = try { JSONArray("[$s]") } catch (e: Exception) { return s }
        return arr.optString(0, s)
    }

    /** 页面重定向变量提取（上游 `var url = '...';`）。 */
    internal fun extractRedirectUrl(html: String): String? =
        Regex("""var url = '(.*?)';""").find(html)?.groupValues?.get(1)

    /** JSONP → JSON（上游 utils.jsonp_to_json：(\w+)\((.*)\);?$）。 */
    internal fun jsonpToJson(jsonp: String): JSONObject? {
        val m = Regex("""(\w+)\((.*)\);?$""", RegexOption.DOT_MATCHES_ALL).find(jsonp) ?: return null
        return try { JSONObject(m.groupValues[2]) } catch (e: Exception) { null }
    }

    internal fun parseLive(data: JSONObject, quality: String?): TaobaoStreamInfo {
        val anchorName = data.optJSONObject("broadCaster")?.optString("accountName", "") ?: ""
        if (data.optString("streamStatus", "") != "1") {
            return TaobaoStreamInfo(anchorName = anchorName)
        }
        val (m3u8, flv) = selectPlayUrl(data.optJSONArray("liveUrlList") ?: JSONArray(), quality)
        return TaobaoStreamInfo(
            anchorName = anchorName,
            title = data.optString("title", ""),
            isLive = true,
            m3u8Url = m3u8,
            flvUrl = flv,
            recordUrl = m3u8, // 上游 url_type='all'：record_url = m3u8_url
            quality = quality ?: "OD",
        )
    }

    /** 上游排序 + 画质索引选择（stream.py get_stream_url：列表 pad 到 5 后按索引取，返回 (m3u8, flv)）。 */
    internal fun selectPlayUrl(list: JSONArray, quality: String?): Pair<String, String> {
        val items = (0 until list.length()).map { list.optJSONObject(it) ?: JSONObject() }
            .sortedByDescending { item ->
                val def = item.optString("definition", "").ifEmpty { item.optString("newDefinition", "") }
                DEFINITION_PRIORITY[def] ?: -1
            }
        if (items.isEmpty()) return "" to ""
        // 上游 while len<5 append last：索引最大 4；等价于对原列表取 min(idx, size-1)
        val idx = (QUALITY_INDEX[quality?.uppercase() ?: "OD"] ?: 0).coerceAtMost(items.size - 1)
        val item = items[idx]
        return item.optString("hlsUrl", "") to item.optString("flvUrl", "")
    }
}
