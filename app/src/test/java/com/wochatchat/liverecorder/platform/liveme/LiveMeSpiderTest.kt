package com.wochatchat.liverecorder.platform.liveme

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

/** LiveMe 单测：roomId/og:url + liveme.js 签名烟囱 + 接口端到端（上游 spider.py:2209）。 */
class LiveMeSpiderTest {

    private class FakeClient : LiveHttpClient() {
        var lastPostUrl: String = ""
        var seenForm: Map<String, String> = emptyMap()
        var seenHeaders: Map<String, String> = emptyMap()

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(
                200,
                """<meta property="og:url" content="https://www.liveme.com/live/17284844223282059697/index.html">""",
                url, emptyMap(),
            )

        override suspend fun postForm(
            url: String, headers: Map<String, String>, form: Map<String, String>, timeoutSec: Long,
        ): HttpResult {
            lastPostUrl = url
            seenForm = form
            seenHeaders = headers
            return HttpResult(
                200,
                """{"data":{"video_info":{"uname":"LiveMe主播","status":"0",
                    "hlsvideosource":"https://h/a.m3u8","videosource":"https://f/a.flv"}}}""",
                url, emptyMap(),
            )
        }
    }

    private fun spider(client: LiveHttpClient) = LiveMeSpider(client, jsEngine = RhinoJsEngine::eval)

    @Test
    fun parseRoomId() {
        assertEquals("123", LiveMeSpider.parseRoomId("https://www.liveme.com/live/123/index.html"))
        assertEquals("456", LiveMeSpider.parseRoomId("https://www.liveme.com/live/456"))
    }

    @Test
    fun extractOgUrl() {
        assertEquals(
            "https://www.liveme.com/live/999/index.html",
            LiveMeSpider().extractOgUrl(
                """<meta property="og:url" content="https://www.liveme.com/live/999/index.html">""",
            ),
        )
    }

    @Test
    fun jsSignSmoke() {
        // liveme.js sign 产出 JSON：lm_s_sign（32 位 MD5）+ 业务参数
        val out = JsScriptRunner.call(
            RhinoJsEngine::eval,
            "sign('17284844223282059697', '__cryptojs__')",
            JsScripts.LIVEME_JS,
            cryptoJs = JsScripts.CRYPTO_JS,
        )
        val obj = org.json.JSONObject(out)
        assertEquals(32, obj.getString("lm_s_sign").length)
        assertTrue(obj.getString("lm_s_sign").all { it.isDigit() || it in 'a'..'f' })
        assertEquals("liveme", obj.getString("alias"))
    }

    @Test
    fun parseResponse_live() {
        val info = LiveMeSpider().parseResponse(
            """{"data":{"video_info":{"uname":"LM主播","status":"0",
                "hlsvideosource":"https://h/a.m3u8","videosource":"https://f/a.flv"}}}""",
        )
        assertTrue(info.isLive)
        assertEquals("LM主播", info.anchorName)
        assertEquals("https://f/a.flv", info.flvUrl)
        assertEquals("https://h/a.m3u8", info.recordUrl) // 上游 m3u8 优先
    }

    @Test
    fun parseResponse_offline() {
        val info = LiveMeSpider().parseResponse(
            """{"data":{"video_info":{"uname":"LM主播","status":"2"}}}""",
        )
        assertFalse(info.isLive)
        assertEquals("LM主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val client = FakeClient()
        val info = spider(client).getStreamInfo("https://www.liveme.com/live/17284844223282059697/index.html")
        assertTrue(info.isLive)
        assertEquals("LM主播", info.anchorName)
        // POST 参数含 videoid 与签名头 lm-s-sign
        assertTrue(client.seenForm.containsKey("videoid"))
        assertTrue(client.seenForm.containsKey("lm_s_id"))
        assertTrue(client.seenHeaders.containsKey("lm-s-sign"))
        assertTrue(client.lastPostUrl.startsWith("https://live.liveme.com/live/queryinfosimple?"))
    }
}
