package com.wochatchat.liverecorder.platform.qiandurebo

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QiandureboSpider 单测：qiandurebo.com 房间页（上游 spider.py:1220）。 */
class QiandureboSpiderTest {

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
        assertEquals("abc123", QiandureboSpider.parseRoomId("https://qiandurebo.com/live/abc123"))
        assertEquals("abc123",
            QiandureboSpider.parseRoomId("https://qiandurebo.com/live/abc123?from=share"))
        assertEquals("", QiandureboSpider.parseRoomId("https://qiandurebo.com/live/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = QiandureboSpider(FakeClient())
        val info = spider.getStreamInfo("https://qiandurebo.com/live/abc123")
        assertEquals("千度主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pull.example.com/live.flv", info.flvUrl)
        assertEquals(info.flvUrl, info.recordUrl)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = QiandureboSpider(FakeClient(pageHtml = OFFLINE_HTML))
        val info = spider.getStreamInfo("https://qiandurebo.com/live/abc123")
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        reqCount = 0
        val spider = QiandureboSpider(FakeClient())
        val info = spider.getStreamInfo("https://qiandurebo.com/live/")
        assertFalse(info.isLive)
    }

    companion object {
        private val LIVE_HTML = """
            var user = {
            "zb_nickname": "千度主播",
            "play_url": "https://pull.example.com/live.flv"
            }
            user.play_url
        """.trimIndent()

        private val OFFLINE_HTML = """
            <div class="common-text-center" style="display:block">未开播</div>
        """.trimIndent()
    }
}