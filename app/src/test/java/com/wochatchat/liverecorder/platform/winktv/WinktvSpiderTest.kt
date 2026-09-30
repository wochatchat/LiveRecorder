package com.wochatchat.liverecorder.platform.winktv

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WinkTV 单测：api.winktv.co.kr 双 POST（上游 spider.py:1335-1405）。 */
class WinktvSpiderTest {

    companion object {
        private const val LIVE_URL = "https://www.winktv.co.kr/live/wink123"

        private val liveBjJson = """
        {"bjInfo":{"id":"wk123","nick":"眨眼主播"},"media":{"live":"ok"}}
    """.trimIndent()

        private val offlineBjJson = """
        {"bjInfo":{"id":"wk123","nick":"眨眼主播"}}
    """.trimIndent()

        private val livePlayJson = """
        {"PlayList":{"hls":[{"url":"https://wink.m3u8/wink123/master.m3u8"}]}}
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
        assertEquals("wink123", WinktvSpider.parseUserId(LIVE_URL))
        assertEquals("wink123", WinktvSpider.parseUserId("$LIVE_URL?pwd=secret"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = WinktvSpider(FakeClient())
        val info = spider.getStreamInfo(LIVE_URL)
        assertEquals("眨眼主播-wk123", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://wink.m3u8/wink123/master.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = WinktvSpider(FakeClient(bjJson = offlineBjJson))
        val info = spider.getStreamInfo(LIVE_URL)
        assertEquals("眨眼主播-wk123", info.anchorName)
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_banned() = runTest {
        // 403 = 网络被封禁（上游 ConnectionError；安卓端防御返回未开播）
        val client = object : LiveHttpClient() {
            override suspend fun postForm(url: String, headers: Map<String, String>,
                                          form: Map<String, String>, timeoutSec: Long): HttpResult =
                HttpResult(403, "403: Forbidden", url, emptyMap())
        }
        val spider = WinktvSpider(client)
        val info = spider.getStreamInfo(LIVE_URL)
        assertFalse(info.isLive)
    }
}