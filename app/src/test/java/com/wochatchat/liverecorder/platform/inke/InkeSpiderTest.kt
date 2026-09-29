package com.wochatchat.liverecorder.platform.inke

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 映客单测：busi.inke.cn API（上游 spider.py:2582）。 */
class InkeSpiderTest {

    companion object {
        private val liveApiJson = """
        {"errno":0,"data":{"media_info":{"nick":"映客主播"},"status":1,
        "live_addr":[{"hls_stream_addr":"https://inke.cn/stream/live.m3u8",
        "stream_addr":"https://inke.cn/stream/live.flv"}]}}
    """.trimIndent()

        private val offlineApiJson = """
        {"errno":0,"data":{"media_info":{"nick":"映客主播"},"status":0,"live_addr":[]}}
    """.trimIndent()
    }

    private class FakeClient(private val json: String = liveApiJson) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            return HttpResult(200, json, url, emptyMap())
        }
    }

    @Test
    fun extractParam_returnsValues() {
        val url = "https://www.inke.cn/?uid=12345&id=67890&extra=foo"
        assertEquals("12345", InkeSpider.extractQuery(url, "uid"))
        assertEquals("67890", InkeSpider.extractQuery(url, "id"))
        assertNull(InkeSpider.extractQuery(url, "missing"))
        assertNull(InkeSpider.extractQuery("https://www.inke.cn/", "uid"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = InkeSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.inke.cn/?uid=12345&id=67890")
        assertEquals("映客主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://inke.cn/stream/live.m3u8", info.m3u8Url)
        assertEquals("https://inke.cn/stream/live.flv", info.flvUrl)
        assertEquals("https://inke.cn/stream/live.m3u8", info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = InkeSpider(FakeClient(json = offlineApiJson))
        val info = spider.getStreamInfo("https://www.inke.cn/?uid=12345&id=67890")
        assertEquals("映客主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.m3u8Url)
        assertEquals("", info.flvUrl)
        assertEquals("", info.recordUrl)
    }

    @Test
    fun getStreamInfo_missingUid_returnsOffline() = runTest {
        val spider = InkeSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.inke.cn/")
        assertFalse(info.isLive)
    }
}