package com.wochatchat.liverecorder.platform.langlive

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LangliveSpider 单测：api.lang.live/langweb/v1/room/liveinfo（上游 spider.py:2846）。 */
class LangliveSpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val apiJson: String = LIVE_JSON,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return HttpResult(200, apiJson, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsLastSegment() {
        assertEquals("456def", LangliveSpider.parseRoomId("https://www.lang.live/room/456def"))
        assertEquals("456def",
            LangliveSpider.parseRoomId("https://www.lang.live/room/456def?from=app"))
        assertEquals("", LangliveSpider.parseRoomId("https://www.lang.live/room/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = LangliveSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.lang.live/room/456def")
        assertEquals("浪Live主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://stream.example.com/langlive.m3u8", info.m3u8Url)
        assertEquals("https://flv.example.com/langlive.flv", info.flvUrl)
        assertEquals(info.m3u8Url, info.recordUrl)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_offline_status0() = runTest {
        reqCount = 0
        val spider = LangliveSpider(FakeClient(apiJson = OFFLINE_JSON))
        val info = spider.getStreamInfo("https://www.lang.live/room/456def")
        assertEquals("浪Live主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = LangliveSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.lang.live/room/")
        assertFalse(info.isLive)
    }

    companion object {
        private val LIVE_JSON = """
            {"errno":0,"data":{"live_info":{"nickname":"浪Live主播","live_status":1,
              "liveurl":"https://flv.example.com/langlive.flv",
              "liveurl_hls":"https://stream.example.com/langlive.m3u8"}}}
        """.trimIndent()
        private val OFFLINE_JSON = """
            {"errno":0,"data":{"live_info":{"nickname":"浪Live主播","live_status":0,"liveurl":"","liveurl_hls":""}}}
        """.trimIndent()
    }
}
