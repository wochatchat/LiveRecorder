package com.wochatchat.liverecorder.platform.liuxing

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流星单测：wap.7u66.com API（上游 spider.py:2400）。 */
class LiuxingSpiderTest {

    companion object {
        private val liveApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"流星主播","live_stat":1,
        "idx":"txidx","liveId1":"txliveid123"}}}
    """.trimIndent()

        private val offlineApiJson = """
        {"errno":0,"data":{"roomInfo":{"nickname":"流星主播","live_stat":0,
        "idx":"","liveId1":""}}}
    """.trimIndent()
    }

    private class FakeClient(private val json: String = liveApiJson) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            return HttpResult(200, json, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsId() {
        assertEquals("198189", LiuxingSpider.parseRoomId("https://www.7u66.com/198189"))
        assertEquals("198189", LiuxingSpider.parseRoomId("https://www.7u66.com/198189?promoters=0"))
        assertEquals("123", LiuxingSpider.parseRoomId("https://wap.7u66.com/123"))
        assertEquals("", LiuxingSpider.parseRoomId("https://www.7u66.com/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = LiuxingSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.7u66.com/198189")
        assertEquals("流星主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://txpull1.5see.com/live/txidx/txliveid123.flv", info.flvUrl)
        assertEquals("https://txpull1.5see.com/live/txidx/txliveid123.flv", info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = LiuxingSpider(FakeClient(json = offlineApiJson))
        val info = spider.getStreamInfo("https://www.7u66.com/198189")
        assertEquals("流星主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.flvUrl)
        assertEquals("", info.recordUrl)
    }
}