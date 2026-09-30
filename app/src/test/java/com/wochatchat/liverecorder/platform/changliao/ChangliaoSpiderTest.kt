package com.wochatchat.liverecorder.platform.changliao

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 畅聊单测：wap.tlclw.com API + 房间页 var config（上游 spider.py:2541）。 */
class ChangliaoSpiderTest {

    companion object {
        private val liveApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"畅聊主播","live_stat":1,
        "liveID":"changliaolid"}}}
    """.trimIndent()

        private val offlineApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"畅聊主播","live_stat":0,"liveID":""}}}
    """.trimIndent()

        private val configHtml = """
        <html><body>
        <script>var config = {"domainpullstream_flv":"https://pull.cltlw.com",
        "domainpullstream_hls":"https://pullm3u8.cltlw.com"}config.webskins.init();</script>
        </body></html>
    """.trimIndent()
    }

    private class FakeClient(
        private val json: String = liveApiJson,
        private val html: String = configHtml,
    ) : LiveHttpClient() {
        private var first = true
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            if (first) { first = false; HttpResult(200, json, url, emptyMap()) }
            else HttpResult(200, html, url, emptyMap())
    }

    @Test
    fun parseRoomId_returnsId() {
        assertEquals("15777", ChangliaoSpider.parseRoomId("https://live.tlclw.com/15777"))
        assertEquals("15777", ChangliaoSpider.parseRoomId("https://live.tlclw.com/15777?promoters=0"))
        assertEquals("123", ChangliaoSpider.parseRoomId("https://wap.tlclw.com/123"))
    }

    @Test
    fun extractDomains_parsesConfig() {
        val html = """
            <script>var config = {"domainpullstream_flv":"https://flv.cdn.com",
            "domainpullstream_hls":"https://hls.cdn.com"}config.webskins.ready();</script>
        """.trimIndent()
        val (flv, hls) = ChangliaoSpider.extractDomains(html)!!
        assertEquals("https://flv.cdn.com", flv)
        assertEquals("https://hls.cdn.com", hls)
        assertTrue(ChangliaoSpider.extractDomains("<html>no config</html>") == null)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = ChangliaoSpider(FakeClient())
        val info = spider.getStreamInfo("https://live.tlclw.com/15777")
        assertEquals("畅聊主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pullm3u8.cltlw.com/changliaolid.m3u8", info.m3u8Url)
        assertEquals("https://pull.cltlw.com/changliaolid.flv", info.flvUrl)
        assertEquals("https://pull.cltlw.com/changliaolid.flv", info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = ChangliaoSpider(FakeClient(json = offlineApiJson))
        val info = spider.getStreamInfo("https://live.tlclw.com/15777")
        assertEquals("畅聊主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.m3u8Url)
        assertEquals("", info.flvUrl)
        assertEquals("", info.recordUrl)
    }
}