package com.wochatchat.liverecorder.platform.live17

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live17Spider 单测：wap-api.17app.co user API + lives view API（上游 spider.py:2816）。 */
class Live17SpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val anchorJson: String = ANCHOR_JSON,
        private val liveJson: String = LIVE_JSON,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return HttpResult(200, anchorJson, url, emptyMap())
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: okhttp3.RequestBody,
            timeoutSec: Long,
        ): HttpResult {
            reqCount++
            return HttpResult(200, liveJson, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsLastSegment() {
        assertEquals("abc123", Live17Spider.parseRoomId("https://17.live/live/abc123"))
        assertEquals("abc123",
            Live17Spider.parseRoomId("https://17.live/live/abc123?from=share"))
        assertEquals("", Live17Spider.parseRoomId("https://17.live/live/"))
        assertEquals("", Live17Spider.parseRoomId("https://17.live/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = Live17Spider(FakeClient())
        val info = spider.getStreamInfo("https://17.live/live/abc123")
        assertEquals("17Live主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pull.example.com/17live.flv", info.flvUrl)
        assertEquals(info.flvUrl, info.recordUrl)
        assertEquals(2, reqCount) // user API + lives API
    }

    @Test
    fun getStreamInfo_offline_statusNot2() = runTest {
        reqCount = 0
        val spider = Live17Spider(FakeClient(liveJson = OFFLINE_JSON))
        val info = spider.getStreamInfo("https://17.live/live/abc123")
        assertEquals("17Live主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(2, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = Live17Spider(FakeClient())
        val info = spider.getStreamInfo("https://17.live/live/")
        assertFalse(info.isLive)
    }

    companion object {
        private const val ANCHOR_JSON = """{"displayName":"17Live主播"}"""
        private val LIVE_JSON = """
            {"status":2,"pullURLsInfo":{"rtmpURLs":[{"urlHighQuality":"https://pull.example.com/17live.flv"}]}}
        """.trimIndent()
        private const val OFFLINE_JSON = """{"status":1,"pullURLsInfo":{"rtmpURLs":[]}}"""
    }
}
