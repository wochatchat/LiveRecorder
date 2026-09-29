package com.wochatchat.liverecorder.platform.huajiao

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 花椒单测：房间路径 + 用户路径 + substream API（上游 spider.py:2351）。 */
class HuajiaoSpiderTest {

    private val feedInfoJson = """
        {"errmsg":"","data":{"author":{"uid":"12345","nickname":"花椒主播"},
        "feed":{"sn":"abc_sn_value","relateid":"67890","title":"花椒测试直播"},
        "creatime":"2024-01-01 00:00:00"}}
    """.trimIndent()

    private val substreamJson = """
        {"errno":0,"data":{"h264_url":"https://stream.huajiao.com/live/67890.flv"}}
    """.trimIndent()

    private val substreamOfflineJson = """
        {"errno":0,"data":{"h264_url":""}}
    """.trimIndent()

    private val userFeedsJson = """
        {"data":{"feeds":[{"feed":{"sn":"user_sn","relateid":"99999","title":"用户直播"}}]}}
    """.trimIndent()

    private val userProfileHtml = "<title>花椒用户主播的主页</title>"

    private inner class FakeClient(
        private val _substream: String = this@HuajiaoSpiderTest.substreamJson,
        private val _userFeeds: String = this@HuajiaoSpiderTest.userFeedsJson,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            return when {
                url.contains("getFeedInfo") ->
                    HttpResult(200, feedInfoJson, url, emptyMap())
                url.contains("live/substream") ->
                    HttpResult(200, _substream, url, emptyMap())
                url.contains("webh.huajiao.com/User/getUserFeeds") ->
                    HttpResult(200, _userFeeds, url, emptyMap())
                url.contains("/user/") ->
                    HttpResult(200, userProfileHtml, url, emptyMap())
                else -> HttpResult(200, "{}", url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_returnsRoomId() {
        assertEquals("67890", HuajiaoSpider.parseRoomId("https://www.huajiao.com/l/67890"))
        assertEquals("67890", HuajiaoSpider.parseRoomId("https://www.huajiao.com/l/67890?from=abc"))
        assertEquals("123", HuajiaoSpider.parseRoomId("http://huajiao.com/l/123"))
        assertEquals(null, HuajiaoSpider.parseRoomId("https://www.huajiao.com/user/555"))
        assertEquals(null, HuajiaoSpider.parseRoomId("https://www.huajiao.com/"))
    }

    @Test
    fun parseUid_returnsUid() {
        assertEquals("12345", HuajiaoSpider.parseUid("https://www.huajiao.com/user/12345"))
        assertEquals("12345", HuajiaoSpider.parseUid("https://www.huajiao.com/user/12345?from=abc"))
        assertEquals(null, HuajiaoSpider.parseUid("https://www.huajiao.com/l/67890"))
    }
// ── Live tests ─────────────────────────────────────────────────────────────

    @Test
    fun getStreamInfo_roomPath_live() = runTest {
        val spider = HuajiaoSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.huajiao.com/l/67890")
        assertEquals("花椒主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("花椒测试直播", info.title)
        assertEquals("https://stream.huajiao.com/live/67890.flv", info.flvUrl)
        assertEquals("https://stream.huajiao.com/live/67890.flv", info.recordUrl)
    }

    @Test
    fun getStreamInfo_roomPath_offline() = runTest {
        val spider = HuajiaoSpider(FakeClient(_substream = substreamOfflineJson))
        val info = spider.getStreamInfo("https://www.huajiao.com/l/67890")
        assertEquals("花椒主播", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.flvUrl)
        assertEquals("", info.recordUrl)
    }

    @Test
    fun getStreamInfo_userPath_live() = runTest {
        val spider = HuajiaoSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.huajiao.com/user/12345", cookie = "uid=12345")
        assertEquals("花椒用户主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("用户直播", info.title)
        assertEquals("https://stream.huajiao.com/live/67890.flv", info.flvUrl)
    }

    @Test
    fun getStreamInfo_userPath_noCookie_returnsOffline() = runTest {
        val spider = HuajiaoSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.huajiao.com/user/12345", cookie = "")
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_invalidUrl_returnsOffline() = runTest {
        val spider = HuajiaoSpider(FakeClient())
        val info = spider.getStreamInfo("https://www.huajiao.com/")
        assertFalse(info.isLive)
        assertEquals("", info.anchorName)
    }
}