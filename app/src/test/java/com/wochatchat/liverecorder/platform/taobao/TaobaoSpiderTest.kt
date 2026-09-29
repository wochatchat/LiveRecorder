package com.wochatchat.liverecorder.platform.taobao

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import com.wochatchat.liverecorder.sign.RhinoJsEngine
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 淘宝单测：参数提取 + JS 签名向量 + 画质选择 + jsonp 端到端（上游 spider.py:3029）。 */
class TaobaoSpiderTest {

    private class FakeClient(private val jsonp: String) : LiveHttpClient() {
        var lastUrl: String = ""
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            lastUrl = url
            return HttpResult(200, jsonp, url, emptyMap())
        }
    }

    private fun spider(client: LiveHttpClient) = TaobaoSpider(client, jsEngine = RhinoJsEngine::eval)

    private fun jsonp(anchor: String, status: String, urls: String) =
        """mtopjsonp1({"ret":["SUCCESS::调用成功"],"data":{"broadCaster":{"accountName":"$anchor"},
            "streamStatus":"$status","title":"淘宝标题","liveUrlList":$urls}})"""

    @Test
    fun jsSignKnownVector() {
        // 上游 taobao-sign.js 内注释的正确 sign 值
        val preSign = "5655b7041ca049730330701082886efd&1719411639403&12574478&" +
            """{"componentKey":"wp_pc_shop_basic_info","params":"{\"memberId\":\"b2b-22133374292418351a\"}"}"""
        val out = TaobaoSpider().run {
            val quoted = JSONObject.quote(preSign)
            val json = com.wochatchat.liverecorder.sign.JsScriptRunner.call(
                RhinoJsEngine::eval, "sign($quoted)", JsScriptsRef,
            )
            // JsScriptRunner 返回 JSON.stringify(sign(...))，解引号
            val arr = org.json.JSONArray("[$json]")
            arr.getString(0)
        }
        assertEquals("05748e8359cd3e6deaab02d15caafc11", out)
    }

    private companion object {
        val JsScriptsRef = com.wochatchat.liverecorder.sign.scripts.JsScripts.TAOBAO_SIGN_JS
    }

    @Test
    fun getParam() {
        assertEquals("123", TaobaoSpider.getParam("https://huodong.m.taobao.com/awp/core/detail.htm?id=123&a=b", "id"))
        assertEquals(null, TaobaoSpider.getParam("https://tb.cn/x", "id"))
    }

    @Test
    fun extractRedirectUrl() {
        assertEquals("https://tb.cn/x?id=9", TaobaoSpider().extractRedirectUrl("var url = 'https://tb.cn/x?id=9';"))
    }

    @Test
    fun selectPlayUrl_quality() {
        val urls = """[
            {"definition":"ud","hlsUrl":"https://ud.m3u8","flvUrl":"https://ud.flv"},
            {"definition":"lld","hlsUrl":"https://lld.m3u8","flvUrl":"https://lld.flv"},
            {"definition":"md","hlsUrl":"https://md.m3u8","flvUrl":"https://md.flv"}]"""
        val list = org.json.JSONArray(urls)
        val spider = TaobaoSpider()
        // OD=0（最高清 ud 在降序后首位）
        assertEquals("https://ud.m3u8" to "https://ud.flv", spider.selectPlayUrl(list, "OD"))
        // HD=2 → md
        assertEquals("https://md.m3u8" to "https://md.flv", spider.selectPlayUrl(list, "HD"))
        // LD=4 越界 → 末位（上游 pad 重复末项语义）
        assertEquals("https://lld.m3u8" to "https://lld.flv", spider.selectPlayUrl(list, "LD"))
    }

    @Test
    fun jsonpToJson() {
        val json = TaobaoSpider().jsonpToJson("""mtopjsonp1({"ret":["SUCCESS::调用成功"],"data":{}});""")
        assertTrue(json != null)
        assertEquals("SUCCESS::调用成功", json!!.optJSONArray("ret")?.optString(0))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val urls = """[{"definition":"ud","hlsUrl":"https://ud.m3u8","flvUrl":"https://ud.flv"}]"""
        val client = FakeClient(jsonp("淘宝主播", "1", urls))
        val cookie = "_m_h5_tk=abc123_1719411639; other=x"
        val info = spider(client).getStreamInfo("https://tb.cn/x?id=123456", cookie = cookie)
        assertTrue(info.isLive)
        assertEquals("淘宝主播", info.anchorName)
        assertEquals("淘宝标题", info.title)
        assertEquals("https://ud.m3u8", info.m3u8Url)
        assertEquals("https://ud.m3u8", info.recordUrl)
        // 请求 URL 带 liveId 与 sign（JS 签名已注入）
        assertTrue(client.lastUrl.contains("liveId"))
        assertTrue(client.lastUrl.contains("sign="))
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val client = FakeClient(jsonp("淘宝主播", "0", "[]"))
        val info = spider(client).getStreamInfo(
            "https://tb.cn/x?id=123456", cookie = "_m_h5_tk=abc123_1719411639;",
        )
        assertFalse(info.isLive)
        assertEquals("淘宝主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_requiresTokenCookie() = runTest {
        val client = FakeClient(jsonp("淘宝主播", "1", "[]"))
        val info = spider(client).getStreamInfo("https://tb.cn/x?id=123", cookie = "nocookie=1")
        assertFalse(info.isLive)
        // 无 _m_h5_tk 时不发起请求
        assertEquals("", client.lastUrl)
    }
}
