package com.wochatchat.liverecorder.platform.pandatv

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PandaTV 单测：api.pandalive.co.kr 双 POST（上游 spider.py:1251）。 */
class PandatvSpiderTest {

    companion object {
        private const val LIVE_URL = "https://www.pandalive.co.kr/live/panda123"

        private val liveBjJson = """
        {"bjInfo":{"id":"pd123","nick":"熊猫主播"},"media":{"hls":"live"}}
    """.trimIndent()

        private val offlineBjJson = """
        {"bjInfo":{"id":"pd123","nick":"熊猫主播"}}
    """.trimIndent()

        private val livePlayJson = """
        {"PlayList":{"hls":[{"url":"https://panda.m3u8/panda123/live.m3u8"}]}}
    """.trimIndent()
    }

    private class FakeClient(
        private val bjJson: String = liveBjJson,
        private val playJson: String = livePlayJson,
    ) : LiveHttpClient() {
        override suspend fun postForm(url: String, headers: Map<String, String>,
                                      form: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, if (url.contains("member/bj")) bjJson else playJson, url, emptyMap())
    }

    @Test
    fun parseUserId_basic() {
        assertEquals("panda123", PandatvSpider.parseUserId(LIVE_URL))
        assertEquals("panda123", PandatvSpider.parseUserId("$LIVE_URL?pwd=1234"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = PandatvSpider(FakeClient())
        val info = spider.getStreamInfo(LIVE_URL)
        assertEquals("熊猫主播-pd123", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://panda.m3u8/panda123/live.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = PandatvSpider(FakeClient(bjJson = offlineBjJson))
        val info = spider.getStreamInfo(LIVE_URL)
        assertEquals("熊猫主播-pd123", info.anchorName)
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_roomNotFound() = runTest {
        val spider = PandatvSpider(FakeClient(bjJson = "{}"))
        val info = spider.getStreamInfo(LIVE_URL)
        assertFalse(info.isLive)
    }
}