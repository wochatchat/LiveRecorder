package com.wochatchat.liverecorder.platform.blued

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BluedSpider 单测：app.blued.cn 房间页 decodeURIComponent JSON（上游 spider.py:876）。 */
class BluedSpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val pageHtml: String = LIVE_HTML,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return HttpResult(200, pageHtml, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsLastSegment() {
        assertEquals("abc123", BluedSpider.parseRoomId("https://app.blued.cn/live/abc123"))
        assertEquals("abc123", BluedSpider.parseRoomId("https://app.blued.cn/live/abc123?from=share"))
        assertEquals("", BluedSpider.parseRoomId("https://app.blued.cn/live/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = BluedSpider(FakeClient())
        val info = spider.getStreamInfo("https://app.blued.cn/live/abc123")
        assertEquals("Blued主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://hls.example.com/blued.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = BluedSpider(FakeClient(pageHtml = OFFLINE_HTML))
        val info = spider.getStreamInfo("https://app.blued.cn/live/abc123")
        assertEquals("Blued主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = BluedSpider(FakeClient())
        val info = spider.getStreamInfo("https://app.blued.cn/live/")
        assertFalse(info.isLive)
    }

    companion object {
        // NOTE: decodeURIComponent(...) without quotes (upstream format)
        // {"userInfo":{"name":"Blued主播","onLive":1},"liveInfo":{"liveUrl":"https://hls.example.com/blued.m3u8"}}
        private val LIVE_HTML = """<html><body>decodeURIComponent(%7B%22userInfo%22%3A%7B%22name%22%3A%22Blued%E4%B8%BB%E6%92%AD%22%2C%22onLive%22%3A1%7D%2C%22liveInfo%22%3A%7B%22liveUrl%22%3A%22https%3A%2F%2Fhls.example.com%2Fblued.m3u8%22%7D%7D),window.Promise</body></html>"""
        // {"userInfo":{"name":"Blued主播","onLive":0},"liveInfo":{}}
        private val OFFLINE_HTML = """<html><body>decodeURIComponent(%7B%22userInfo%22%3A%7B%22name%22%3A%22Blued%E4%B8%BB%E6%92%AD%22%2C%22onLive%22%3A0%7D%2C%22liveInfo%22%3A%7B%7D%7D),window.Promise</body></html>"""
    }
}