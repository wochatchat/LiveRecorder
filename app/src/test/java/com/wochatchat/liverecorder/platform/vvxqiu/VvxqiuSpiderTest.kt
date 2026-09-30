package com.wochatchat.liverecorder.platform.vvxqiu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** VV星球单测：h5p.vvxqiu.com captain/banner API + m3u8 探测（上游 spider.py:2776）。 */
class VvxqiuSpiderTest {

    companion object {
        // 主播 API 开播 fixture：anchorName 有值，m3u8 有效
        private val captainBannerJson = """
        {"errno":0,"data":{"anchorName":"VV星球主播"}}
    """.trimIndent()

        // 主播 API 未返回 anchorName（备用路径）
        private val captainBannerEmptyJson = """
        {"errno":0,"data":{"anchorName":""}}
    """.trimIndent()

        private val memberBannerJson = """
        {"errno":0,"data":{"memberVO":{"memberName":"VV备用主播"}}}
    """.trimIndent()

        private const val VALID_M3U8 = "#EXTM3U\n#EXT-X-STREAM-INF\ntest"
        private const val NOT_FOUND_M3U8 = "Not Found"
    }

    private var reqCount = 0

    private inner class FakeClient(
        private val bannerJson: String = captainBannerJson,
        private val memberJson: String = memberBannerJson,
        private val m3u8Content: String = VALID_M3U8,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return when {
                url.contains("captain/banner") -> HttpResult(200, bannerJson, url, emptyMap())
                url.contains("halloween2023") -> HttpResult(200, memberJson, url, emptyMap())
                url.contains("wasaixiu.com") -> HttpResult(200, m3u8Content, url, emptyMap())
                else -> HttpResult(404, "unknown", url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_returnsRoomId() {
        assertEquals("123456", VvxqiuSpider.parseRoomId("https://www.vvxqiu.com/?roomId=123456"))
        assertEquals("123456",
            VvxqiuSpider.parseRoomId("https://vvxqiu.com/?roomId=123456&from=share"))
        assertEquals("", VvxqiuSpider.parseRoomId("https://vvxqiu.com/?noId=123"))
        assertEquals("", VvxqiuSpider.parseRoomId("https://vvxqiu.com/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = VvxqiuSpider(FakeClient())
        val info = spider.getStreamInfo("https://vvxqiu.com/?roomId=123456")
        assertEquals("VV星球主播", info.anchorName)
        assertTrue(info.isLive)
        val expected = "https://liveplay-pro.wasaixiu.com/live/1400442770_123456_3456_single.m3u8"
        assertEquals(expected, info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
        assertEquals(2, reqCount) // captain banner + m3u8 probe
    }

    @Test
    fun getStreamInfo_fallbackMemberApi() = runTest {
        reqCount = 0
        val spider = VvxqiuSpider(FakeClient(bannerJson = captainBannerEmptyJson))
        val info = spider.getStreamInfo("https://vvxqiu.com/?roomId=123456")
        assertEquals("VV备用主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals(3, reqCount) // captain + member + m3u8
    }

    @Test
    fun getStreamInfo_offline_notFound() = runTest {
        reqCount = 0
        val spider = VvxqiuSpider(FakeClient(m3u8Content = NOT_FOUND_M3U8))
        val info = spider.getStreamInfo("https://vvxqiu.com/?roomId=123456")
        assertEquals("VV星球主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(2, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = VvxqiuSpider(FakeClient())
        val info = spider.getStreamInfo("https://vvxqiu.com/?noId=abc")
        assertFalse(info.isLive)
        assertEquals("", info.anchorName)
    }
}