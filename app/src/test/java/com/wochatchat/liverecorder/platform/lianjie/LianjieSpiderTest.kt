package com.wochatchat.liverecorder.platform.lianjie

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LianjieSpider 单测：api.lailianjie.com（上游 spider.py:3278）。 */
class LianjieSpiderTest {

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
    fun parseRoomId_returnsSegment() {
        assertEquals("room123", LianjieSpider.parseRoomId("https://show.lailianjie.com/room123"))
        assertEquals("room123",
            LianjieSpider.parseRoomId("https://show.lailianjie.com/room123?from=share"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = LianjieSpider(FakeClient())
        val info = spider.getStreamInfo("https://show.lailianjie.com/room123")
        assertEquals("连接主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pull.example.com/live.m3u8?token=abc", info.m3u8Url)
        assertEquals("https://pull.example.com/live.flv?token=abc", info.flvUrl)
        assertEquals(info.flvUrl, info.recordUrl)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = LianjieSpider(FakeClient(apiJson = OFFLINE_JSON))
        val info = spider.getStreamInfo("https://show.lailianjie.com/room123")
        assertEquals("连接主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        reqCount = 0
        val spider = LianjieSpider(FakeClient())
        val info = spider.getStreamInfo("https://show.lailianjie.com/")
        assertFalse(info.isLive)
    }

    companion object {
        private const val LIVE_JSON = """{"data":{"nickname":"连接主播","isonline":1,"videoUrl":"webrtc://pull.example.com/live?token=abc"}}"""
        private const val OFFLINE_JSON = """{"data":{"nickname":"连接主播","isonline":0,"videoUrl":""}}"""
    }
}