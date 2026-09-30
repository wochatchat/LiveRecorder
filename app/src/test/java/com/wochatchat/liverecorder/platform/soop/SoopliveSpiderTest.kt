package com.wochatchat.liverecorder.platform.soop

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOOP 单测：api.m / tk 双 API / 国际站 v2 API（上游 spider.py:900-1250）。 */
class SoopliveSpiderTest {

    companion object {
        private const val BJ_URL = "https://play.sooplive.co.kr/oul282/249469582"

        private val liveWatchJson = """
        {"result":1,"data":{"user_nick":"SOOP主播","bj_id":"oul282",
        "broad_no":"249469582","hls_authentication_key":"aidkey123","code":0}}
    """.trimIndent()

        private val offlineWatchJson = """
        {"result":0,"data":{"code":-3001,"user_nick":"SOOP主播"}}
    """.trimIndent()

        private val tkAidJson = """
        {"CHANNEL":{"AID":"aid-token-123","BJNICK":"SOOP主播","BJID":"oul282","BNO":"249469582"}}
    """.trimIndent()

        private val tkInfoJson = """
        {"CHANNEL":{"AID":"x","BJNICK":"SOOP主播","BJID":"oul282","BNO":"249469582"}}
    """.trimIndent()

        private val cdnJson = """
        {"view_url":"https://kr-cdn.sooplive.co.kr/live/249469582/master.m3u8"}
    """.trimIndent()
    }

    /** 按 URL 分发 fixture 的 FakeClient（全局请求序号无需：URL 即可区分）。 */
    private open class FakeClient(
        private val watchJson: String = liveWatchJson.toString(),
    ) : LiveHttpClient() {
        var loginCalled = false

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult = when {
            url.startsWith("http://livestream-manager.sooplive.co.kr/") ->
                HttpResult(200, cdnJson, url, emptyMap())
            else -> HttpResult(404, "not found", url, emptyMap())
        }

        override suspend fun postForm(url: String, headers: Map<String, String>,
                                      form: Map<String, String>, timeoutSec: Long): HttpResult = when {
            url.startsWith("http://api.m.sooplive.co.kr/") ->
                HttpResult(200, watchJson, url, emptyMap())
            url.startsWith("https://live.sooplive.co.kr/afreeca/player_live_api.php") ->
                HttpResult(200, if (form["type"] == "aid") tkAidJson else tkInfoJson, url, emptyMap())
            url.startsWith("https://login.sooplive.co.kr/") -> {
                loginCalled = true
                HttpResult(200, "{}", url,
                    mapOf("AuthTicket" to "T1", "PAuthTicket" to "P1"))
            }
            else -> HttpResult(404, "not found", url, emptyMap())
        }
    }

    private class GlobalClient : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult = when {
            url.startsWith("https://api.sooplive.com/v2/channel/info/") ->
                HttpResult(200, """{"data":{"streamerChannelInfo":{"nickname":"GL主播",
                    "channelId":"gl123"}}}""", url, emptyMap())
            url.startsWith("https://api.sooplive.com/v2/stream/info/") ->
                HttpResult(200, """{"data":{"isStream":true,"title":"GL标题"}}""", url, emptyMap())
            else -> HttpResult(404, "not found", url, emptyMap())
        }
    }

    @Test
    fun parseBjId_shortAndLongUrls() {
        assertEquals("oul282", SoopliveSpider.parseBjId("https://play.sooplive.co.kr/oul282/249469582"))
        assertEquals("oul282", SoopliveSpider.parseBjId("https://www.sooplive.co.kr/station/20519630/oul282"))
        assertEquals("oul282", SoopliveSpider.parseBjId("https://sooplive.com/oul282/1"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = SoopliveSpider(FakeClient())
        val info = spider.getStreamInfo("https://play.sooplive.co.kr/oul282/249469582")
        assertEquals("SOOP主播-oul282", info.anchorName)
        assertTrue(info.isLive)
        assertEquals(
            "https://kr-cdn.sooplive.co.kr/live/249469582/master.m3u8?aid=aidkey123",
            info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = SoopliveSpider(FakeClient(watchJson = offlineWatchJson))
        val info = spider.getStreamInfo("https://play.sooplive.co.kr/oul282/249469582")
        assertEquals("SOOP主播", info.anchorName)
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_ageRestricted_loginFlow() = runTest {
        val client = FakeClient(watchJson = """{"result":0,"data":{"code":-3002}}""")
        val spider = SoopliveSpider(client)
        val info = spider.getStreamInfo(
            "https://play.sooplive.co.kr/oul282/249469582",
            username = "testuser01", password = "longpassword123",
        )
        assertTrue(client.loginCalled)
        assertTrue(info.isLive)
        assertEquals("aid-token-123", Regex("aid=([^?]+)").find(info.m3u8Url)?.groupValues?.get(1))
        // new_cookies 回写语义：登录 cookie 透出
        assertTrue(info.newCookies?.contains("AuthTicket=T1") == true)
    }

    @Test
    fun getStreamInfo_ageRestricted_noCredentials_offline() = runTest {
        val client = FakeClient(watchJson = """{"result":0,"data":{"code":-3002}}""")
        val spider = SoopliveSpider(client)
        val info = spider.getStreamInfo("https://play.sooplive.co.kr/oul282/249469582")
        assertFalse(client.loginCalled)
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_needCookie_pathWithCookie() = runTest {
        // -3004：配置了 cookie → 直接走登录态拉流
        val client = FakeClient(watchJson = """{"result":0,"data":{"code":-3004}}""")
        val spider = SoopliveSpider(client)
        val info = spider.getStreamInfo(
            "https://play.sooplive.co.kr/oul282/249469582", cookie = "AuthTicket=abc",
        )
        assertFalse(client.loginCalled)
        assertTrue(info.isLive)
    }

    @Test
    fun getStreamInfo_globalSite() = runTest {
        val spider = SoopliveSpider(GlobalClient())
        val info = spider.getStreamInfo("https://www.sooplive.com/oul282/249469582")
        assertEquals("GL主播-gl123", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("GL标题", info.title)
        assertEquals("https://global-media.sooplive.com/live/oul282/master.m3u8", info.m3u8Url)
    }
}
