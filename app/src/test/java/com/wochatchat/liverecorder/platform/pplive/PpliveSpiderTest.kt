package com.wochatchat.liverecorder.platform.pplive

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PpliveSpider 单测：api.pp.weimipopo.com/live/preview + catshow 变体（上游 spider.py:2872）。 */
class PpliveSpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val apiJson: String = LIVE_JSON,
    ) : LiveHttpClient() {
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: okhttp3.RequestBody,
            timeoutSec: Long,
        ): HttpResult {
            reqCount++
            return HttpResult(200, apiJson, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsAnchorUid() {
        assertEquals("uid123",
            PpliveSpider.parseRoomId("https://m.pp.weimipopo.com/?anchorUid=uid123"))
        assertEquals("uid123",
            PpliveSpider.parseRoomId("https://m.pp.weimipopo.com/?anchorUid=uid123&from=app"))
        assertEquals("",
            PpliveSpider.parseRoomId("https://m.pp.weimipopo.com/?noId=abc"))
        assertEquals("",
            PpliveSpider.parseRoomId("https://m.pp.weimipopo.com/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = PpliveSpider(FakeClient())
        val info = spider.getStreamInfo("https://m.pp.weimipopo.com/?anchorUid=uid123")
        assertEquals("漂漂主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://stream.example.com/pplive.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_offline_livingFalse() = runTest {
        reqCount = 0
        val spider = PpliveSpider(FakeClient(apiJson = OFFLINE_JSON))
        val info = spider.getStreamInfo("https://m.pp.weimipopo.com/?anchorUid=uid123")
        assertEquals("漂漂主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_catshowVariant() = runTest {
        reqCount = 0
        val spider = PpliveSpider(FakeClient(apiJson = CATSHOW_LIVE_JSON))
        val info = spider.getStreamInfo("https://h.catshow168.com/?anchorUid=cat999")
        assertEquals("花猫主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://stream.example.com/catshow.m3u8", info.m3u8Url)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noAnchorUid() = runTest {
        val spider = PpliveSpider(FakeClient())
        val info = spider.getStreamInfo("https://m.pp.weimipopo.com/?noId=abc")
        assertFalse(info.isLive)
    }

    companion object {
        private val LIVE_JSON = """
            {"errno":0,"data":{"name":"漂漂主播","living":true,"pullUrl":"https://stream.example.com/pplive.m3u8"}}
        """.trimIndent()
        private val OFFLINE_JSON = """
            {"errno":0,"data":{"name":"漂漂主播","living":false,"pullUrl":""}}
        """.trimIndent()
        private val CATSHOW_LIVE_JSON = """
            {"errno":0,"data":{"name":"花猫主播","living":true,"pullUrl":"https://stream.example.com/catshow.m3u8"}}
        """.trimIndent()
    }
}
