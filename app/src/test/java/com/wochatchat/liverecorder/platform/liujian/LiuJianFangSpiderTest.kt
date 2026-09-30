package com.wochatchat.liverecorder.platform.liujian

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LiuJianFangSpider 单测：v.6.cn 房间页 + API（上游 spider.py:2908）。 */
class LiuJianFangSpiderTest {

    private var getCount = 0
    private var postCount = 0

    private inner class FakeClient(
        private val pageText: String = PAGE_HTML,
        private val apiJson: String = LIVE_JSON,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            getCount++
            return HttpResult(200, pageText, url, emptyMap())
        }
        override suspend fun post(url: String, headers: Map<String, String>, body: okhttp3.RequestBody, timeoutSec: Long): HttpResult {
            postCount++
            return HttpResult(200, apiJson, url, emptyMap())
        }
    }

    @Test
    fun parseRoomId_returnsLastSegment() {
        assertEquals("abc123", LiuJianFangSpider.parseRoomId("https://6.cn/abc123"))
        assertEquals("abc123", LiuJianFangSpider.parseRoomId("https://www.6.cn/abc123?from=share"))
        assertEquals("", LiuJianFangSpider.parseRoomId("https://6.cn/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        getCount = 0; postCount = 0
        val spider = LiuJianFangSpider(FakeClient())
        val info = spider.getStreamInfo("https://6.cn/abc123")
        assertEquals("六间房主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://wlive.6rooms.com/httpflv/live001.flv", info.flvUrl)
        assertEquals(info.flvUrl, info.recordUrl)
        assertEquals(1, getCount) // 房间页
        assertEquals(1, postCount) // API
    }

    @Test
    fun getStreamInfo_offline_noFlvtitle() = runTest {
        getCount = 0; postCount = 0
        val spider = LiuJianFangSpider(FakeClient(apiJson = OFFLINE_JSON))
        val info = spider.getStreamInfo("https://6.cn/abc123")
        assertEquals("六间房主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, getCount)
        assertEquals(1, postCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = LiuJianFangSpider(FakeClient())
        val info = spider.getStreamInfo("https://6.cn/")
        assertFalse(info.isLive)
    }

    companion object {
        private const val PAGE_HTML = "rid: 'room999',\n    roomid: 'abc123',"
        private const val LIVE_JSON = """{"content":{"roominfo":{"alias":"六间房主播"},"liveinfo":{"flvtitle":"live001"}}}"""
        private const val OFFLINE_JSON = """{"content":{"roominfo":{"alias":"六间房主播"},"liveinfo":{"flvtitle":""}}}"""
    }
}