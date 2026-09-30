package com.wochatchat.liverecorder.platform.flextv

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FlexTV 单测：__NEXT_DATA__ JSON + stream API（上游 spider.py:1472-1546）。 */
class FlextvSpiderTest {

    companion object {
        private const val CHANNEL_ID = "flex123"
        private const val LIVE_URL = "https://www.ttinglive.com/channels/$CHANNEL_ID/live"

        // __NEXT_DATA__ JSON 包含 channel（开播态）
        private val liveNextData = """
        {"props":{"pageProps":{"channel":{
            "owner":{"nickname":"Flex主播","loginId":"flex123"},
            "title":"Flex直播"}}}}
    """.trimIndent()

        // __NEXT_DATA__ 含 message = 需登录
        private val loginNeedNextData = """
        {"props":{"pageProps":{"channel":{
            "message":"로그인후 이용이 가능합니다. 로그인해주세요."}}}}
    """.trimIndent()

        private val liveStreamJson = """
        {"sources":[{"url":"https://flex.m3u8/flex123/stream.m3u8"}]}
    """.trimIndent()

        private val offlineNextData = """
        {"props":{"pageProps":{"channel":{
            "message":"直播间未开播","offline":true}}}}
    """.trimIndent()

        private val LIVE_HTML = """<!DOCTYPE html>
        <html><head><title>Flex直播</title></head>
        <script id="__NEXT_DATA__" type="application/json">$liveNextData</script></body></html>"""

        private val LOGIN_NEED_HTML = """
        <html><head><title>需登录</title></head>
        <script id="__NEXT_DATA__" type="application/json">$loginNeedNextData</script></body></html>"""

        private const val OFFLINE_HTML = """<!DOCTYPE html>
        <html><head>
        <meta name="twitter:title" content="Flex离线主播的直播间"/>
        </head></html>"""
    }

    private class FakeClient(
        private val liveJson: String = liveStreamJson,
        private val pageHtml: String = LIVE_HTML,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            return when {
                url.startsWith("https://www.ttinglive.com/api/channels/") ->
                    HttpResult(200, liveJson, url, emptyMap())
                else -> HttpResult(200, pageHtml, url, emptyMap())
            }
        }

        override suspend fun post(url: String, headers: Map<String, String>,
                                  body: okhttp3.RequestBody, timeoutSec: Long): HttpResult =
            HttpResult(200, """{"flx_oauth_access":"token123456"}""", url,
                mapOf("flx_oauth_access" to "token123456"))
    }

    @Test
    fun parseUserId_basic() {
        assertEquals("flex123", FlextvSpider.parseUserId(LIVE_URL))
        assertEquals("flex123", FlextvSpider.parseUserId("$LIVE_URL?from=app"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = FlextvSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.ttinglive.com/channels/flex123/live")
        assertEquals("Flex主播-flex123", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://flex.m3u8/flex123/stream.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun getStreamInfo_loginNeeded_withCredentials() = runTest {
        val client = FakeClient(pageHtml = LOGIN_NEED_HTML)
        val spider = FlextvSpider(client)
        val info = spider.getStreamInfo(
            "https://www.ttinglive.com/channels/flex123/live",
            username = "testuser", password = "longpass123",
        )
        assertTrue(info.isLive)
        assertEquals("https://flex.m3u8/flex123/stream.m3u8", info.m3u8Url)
    }

    @Test
    fun getStreamInfo_loginNeeded_noCredentials_offline() = runTest {
        val client = FakeClient(pageHtml = LOGIN_NEED_HTML)
        val spider = FlextvSpider(client)
        val info = spider.getStreamInfo("https://www.ttinglive.com/channels/flex123/live")
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = FlextvSpider(FakeClient(pageHtml = OFFLINE_HTML))
        val info = spider.getStreamInfo("https://www.ttinglive.com/channels/flex123/live")
        assertFalse(info.isLive)
        assertEquals("Flex离线主播", info.anchorName)
    }
}