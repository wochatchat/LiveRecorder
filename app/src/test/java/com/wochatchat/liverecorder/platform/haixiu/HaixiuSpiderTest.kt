package com.wochatchat.liverecorder.platform.haixiu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import com.wochatchat.liverecorder.sign.JsScriptRunner
import com.wochatchat.liverecorder.sign.RhinoJsEngine
import com.wochatchat.liverecorder.sign.scripts.JsScripts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 嗨秀/乐嗨单测：roomId/解码 + haixiu.js 签名烟囱 + 接口端到端（上游 spider.py:2727）。 */
class HaixiuSpiderTest {

    private class FakeClient : LiveHttpClient() {
        var lastUrl: String = ""
        var seenHeaders: Map<String, String> = emptyMap()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            lastUrl = url
            seenHeaders = headers
            return HttpResult(
                200,
                """{"data":{"nickname":"嗨秀主播","live_status":1,
                    "media_url_web":"https://flv.haixiutv.com/live/1.flv"}}""",
                url, emptyMap(),
            )
        }
    }

    private fun spider(client: LiveHttpClient) = HaixiuSpider(client, jsEngine = RhinoJsEngine::eval)

    @Test
    fun parseRoomId() {
        assertEquals("123456", HaixiuSpider.parseRoomId("https://www.haixiutv.com/123456?from=a"))
        assertEquals("789", HaixiuSpider.parseRoomId("https://www.lehaitv.com/789"))
    }

    @Test
    fun doubleUrlDecode() {
        // 上游 urllib.parse.unquote × 2
        assertEquals("+", HaixiuSpider().doubleUrlDecode("%252B"))
    }

    @Test
    fun jsSignSmoke() {
        // haixiu.js 签名链路（crypto-js require 垫片）可执行并产出非空结果
        val out = JsScriptRunner.call(
            RhinoJsEngine::eval,
            """sign({"accessToken":"x","tku":"3000006","c":"10138100100000","_st1":"1727500000000"}, '__cryptojs__')""",
            JsScripts.HAIXIU_JS,
            cryptoJs = JsScripts.CRYPTO_JS,
        )
        assertTrue(out.isNotBlank())
        assertTrue(out != "null" && out != "undefined")
    }

    @Test
    fun unwrapJson() {
        val spider = HaixiuSpider()
        assertEquals("abc", spider.unwrapJson("\"abc\""))
        assertEquals("{\"a\":1}", spider.unwrapJson("{\"a\":1}"))
        assertEquals("raw", spider.unwrapJson("raw"))
    }

    @Test
    fun parseResponse_live() {
        val info = HaixiuSpider().parseResponse(
            """{"data":{"nickname":"嗨秀主播","live_status":1,"media_url_web":"https://f/a.flv"}}""",
        )
        assertTrue(info.isLive)
        assertEquals("嗨秀主播", info.anchorName)
        assertEquals("https://f/a.flv", info.flvUrl)
        assertEquals("https://f/a.flv", info.recordUrl)
    }

    @Test
    fun parseResponse_offline() {
        val info = HaixiuSpider().parseResponse("""{"data":{"nickname":"主播","live_status":0}}""")
        assertFalse(info.isLive)
        assertEquals("主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val client = FakeClient()
        val info = spider(client).getStreamInfo(
            "https://www.haixiutv.com/123456",
            nowMs = 1727500000000L,
        )
        assertTrue(info.isLive)
        assertEquals("嗨秀主播", info.anchorName)
        // 请求参数含 _ajaxData1 与解码后的 accessToken
        assertTrue(client.lastUrl.contains("advanceInfoRoom"))
        assertTrue(client.lastUrl.contains("_ajaxData1="))
        assertTrue(client.lastUrl.contains("accessToken="))
    }
}
