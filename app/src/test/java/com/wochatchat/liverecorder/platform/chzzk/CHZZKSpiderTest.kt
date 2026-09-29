package com.wochatchat.liverecorder.platform.chzzk

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CHZZKSpider 单测：URL 解析 + 房间信息 API + 开播/未开播分发。 */
class CHZZKSpiderTest {

    @Test
    fun parseRoomId_normal() {
        assertEquals(
            "458f6ec20b034f49e0fc6d03921646d2",
            CHZZKSpider.parseRoomId("https://chzzk.naver.com/live/458f6ec20b034f49e0fc6d03921646d2"),
        )
        assertEquals(
            "abc123",
            CHZZKSpider.parseRoomId("https://chzzk.naver.com/live/abc123?from=live"),
        )
    }

    @Test
    fun parseRoomId_edge() {
        assertEquals("", CHZZKSpider.parseRoomId("https://chzzk.naver.com/live/"))
    }

    @Test
    fun companionUrl() {
        assertTrue(CHZZKSpider.isCHZZKUrl("https://chzzk.naver.com/live/abc123"))
        assertFalse(CHZZKSpider.isCHZZKUrl("https://www.youtube.com/watch?v=xxx"))
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val json = JSONObject()
            .put("content", JSONObject()
                .put("channel", JSONObject().put("channelName", "CHZZK主播"))
                .put("status", "CLOSED"))
        val spider = CHZZKSpider(FakeClient(json.toString()))
        val result = spider.getStreamInfo("https://chzzk.naver.com/live/abc123")
        assertFalse(result.isLive)
        assertEquals("CHZZK主播", result.anchorName)
    }

    @Test
    fun getStreamInfo_online() = runTest {
        // livePlaybackJson 是内嵌 JSON 字符串
        val playbackJson = JSONObject()
            .put("media", org.json.JSONArray().put(
                JSONObject().put("path", "https://.channel.wiseapp.net/live/test.m3u8"),
            ))
        val json = JSONObject()
            .put("content", JSONObject()
                .put("channel", JSONObject().put("channelName", "CHZZK主播"))
                .put("status", "OPEN")
                .put("livePlaybackJson", playbackJson.toString()))
        val spider = CHZZKSpider(FakeClient(json.toString()))
        val result = spider.getStreamInfo("https://chzzk.naver.com/live/abc123")
        assertTrue(result.isLive)
        assertEquals("CHZZK主播", result.anchorName)
        assertTrue(result.m3u8Url.contains("test.m3u8"))
        assertEquals(result.m3u8Url, result.recordUrl)
    }

    @Test
    fun getStreamInfo_invalidJson() = runTest {
        val spider = CHZZKSpider(FakeClient("{invalid json}"))
        val result = spider.getStreamInfo("https://chzzk.naver.com/live/abc")
        assertFalse(result.isLive)
    }

    private class FakeClient(private val response: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, response, url, emptyMap())
    }
}