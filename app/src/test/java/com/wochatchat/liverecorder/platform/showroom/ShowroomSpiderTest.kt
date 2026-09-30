package com.wochatchat.liverecorder.platform.showroom

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowroomSpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val pageHtml: String,
        private val liveInfoJson: String,
        private val streamJson: String,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return when {
                url.contains("live_info") -> HttpResult(200, liveInfoJson, url, emptyMap())
                url.contains("streaming_url") -> HttpResult(200, streamJson, url, emptyMap())
                else -> HttpResult(200, pageHtml, url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_fromQueryParam() {
        assertEquals("99999",
            ShowroomSpider.parseRoomId("https://www.showroom-live.com/room/profile?room_id=99999"))
        assertEquals("abc123",
            ShowroomSpider.parseRoomId("https://www.showroom-live.com/room/profile?room_id=abc123&from=share"))
    }

    @Test
    fun parseRoomId_fromPath() {
        assertEquals("abc123", ShowroomSpider.parseRoomId("https://www.showroom-live.com/abc123"))
        assertEquals("", ShowroomSpider.parseRoomId("https://www.showroom-live.com/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = ShowroomSpider(FakeClient(PAGE, LIVE_INFO, STREAM))
        val info = spider.getStreamInfo("https://www.showroom-live.com/room/profile?room_id=99999")
        assertEquals("ShowRoom主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://r.showroom-live.com/live/abc.m3u8", info.m3u8Url)
        assertEquals("http://r.showroom-live.com/live/abc.m3u8", info.recordUrl)
        assertEquals(2, reqCount)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = ShowroomSpider(FakeClient(PAGE, OFFLINE_INFO, STREAM))
        val info = spider.getStreamInfo("https://www.showroom-live.com/room/profile?room_id=99999")
        assertEquals("ShowRoom主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        reqCount = 0
        val spider = ShowroomSpider(FakeClient(NO_ID_PAGE, LIVE_INFO, STREAM))
        val info = spider.getStreamInfo("https://www.showroom-live.com/")
        assertFalse(info.isLive)
    }

    companion object {
        private const val PAGE = "<a href=\"/room/profile?room_id=12345\">room</a>"
        private const val NO_ID_PAGE = "<div>no room id here</div>"
        private const val LIVE_INFO = "{\"room_name\":\"ShowRoom主播\",\"live_status\":2}"
        private const val OFFLINE_INFO = "{\"room_name\":\"ShowRoom主播\",\"live_status\":1}"
        private const val STREAM = "{\"streaming_url_list\":[{\"type\":\"hls_all\",\"url\":\"https://r.showroom-live.com/live/abc.m3u8\"}]}"
    }
}