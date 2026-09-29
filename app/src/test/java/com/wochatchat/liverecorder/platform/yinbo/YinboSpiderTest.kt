package com.wochatchat.liverecorder.platform.yinbo

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音播单测：wap.ybw1666.com API + 房间页 var config（上游 spider.py:2615）。 */
class YinboSpiderTest {

    companion object {
        private val liveApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"音播主播","live_stat":1,
        "liveID":"yinboliveid"}}}
    """.trimIndent()

        private val offlineApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"音播主播","live_stat":0,
        "liveID":""}}}
    """.trimIndent()

        private val configHtml = """
        <html><head></head><body>
        <script>var config = {"domainpullstream_flv":"https://pull.yinbo.com",
        "domainpullstream_hls":"https://pullm3u8.yinbo.com"}config.webskins.init();</script>
        </body></html>
    """.trimIndent()
    }

        private class FakeClient(
        private val json: String = liveApiJson,
        private val html: String = configHtml,
    ) : LiveHttpClient() {
        private var first = true
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            return if (first) {
                first = false
                HttpResult(200, json, url, emptyMap())
            } else {
                HttpResult(200, html, url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_returnsId() {
        assertEquals("800005143", YinboSpider.parseRoomId("https://www.ybw1666.com/800005143"))
        assertEquals("800005143", YinboSpider.parseRoomId("https://www.ybw1666.com/800005143?promoters=0"))
        assertEquals("123", YinboSpider.parseRoomId("https://wap.ybw1666.com/123"))
    }

    @Test
    fun extractDomains_parsesConfig() {
        val html = """
            <script>var config = {"domainpullstream_flv":"https://flv.cdn.com",
            "domainpullstream_hls":"https://hls.cdn.com"}config.webskins.ready();</script>
        """.trimIndent()
        val (flv, hls) = YinboSpider.extractDomains(html)!!
        assertEquals("https://flv.cdn.com", flv)
        assertEquals("https://hls.cdn.com", hls)
        assertNull(YinboSpider.extractDomains("<html>no config</html>"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = YinboSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.ybw1666.com/800005143")
        assertEquals("音播主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pull.yinbo.com/yinboliveid.flv", info.flvUrl)
        assertEquals("https://pullm3u8.yinbo.com/yinboliveid.m3u8", info.m3u8Url)
        assertEquals("https://pull.yinbo.com/yinboliveid.flv", info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = YinboSpider(FakeClient(json = offlineApiJson))
        val info = spider.getStreamInfo("https://www.ybw1666.com/800005143")
        assertEquals("音播主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.flvUrl)
        assertEquals("", info.m3u8Url)
        assertEquals("", info.recordUrl)
    }
}