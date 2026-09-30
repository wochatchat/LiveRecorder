package com.wochatchat.liverecorder.platform.maoerfm

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 猫耳FM单测：fm.missevan.com API（上游 spider.py:1303）。 */
class MaoerfmSpiderTest {

    companion object {
        // 开播 fixture：info.room.status.broadcasting = 1，有 channel.hls_pull_url / flv_pull_url
        private val liveApiJson = """
        {"info":{"creator":{"username":"猫耳主播"},"room":{"name":"猫耳直播",
        "status":{"broadcasting":1},
        "channel":{"hls_pull_url":"https://hls.missevan.com/stream/abc.m3u8",
        "flv_pull_url":"https://flv.missevan.com/stream/abc.flv"}}}}
    """.trimIndent()

        // 未开播 fixture：broadcasting = 0，无 channel
        private val offlineApiJson = """
        {"info":{"creator":{"username":"猫耳离线"},"room":{"name":"",
        "status":{"broadcasting":0}}}}
    """.trimIndent()

        // 仅 creator，无 room（异常情况）
        private val noRoomApiJson = """
        {"info":{"creator":{"username":"猫耳游客"}}}
    """.trimIndent()
    }

    private class FakeClient(private val json: String = liveApiJson) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, json, url, emptyMap())
    }

    @Test
    fun parseRoomId_returnsId() {
        assertEquals("868895007", MaoerfmSpider.parseRoomId("https://fm.missevan.com/live/868895007"))
        assertEquals("868895007", MaoerfmSpider.parseRoomId("https://fm.missevan.com/live/868895007?from=123"))
        assertEquals("abc123", MaoerfmSpider.parseRoomId("https://fm.missevan.com/live/abc123/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = MaoerfmSpider(FakeClient())
        val info = spider.getStreamInfo("https://fm.missevan.com/live/868895007")
        assertEquals("猫耳主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("猫耳直播", info.title)
        assertEquals("https://hls.missevan.com/stream/abc.m3u8", info.m3u8Url)
        assertEquals("https://flv.missevan.com/stream/abc.flv", info.flvUrl)
        assertEquals("https://flv.missevan.com/stream/abc.flv", info.recordUrl)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = MaoerfmSpider(FakeClient(json = offlineApiJson))
        val info = spider.getStreamInfo("https://fm.missevan.com/live/868895007")
        assertEquals("猫耳离线", info.anchorName)
        assertFalse(info.isLive)
        assertEquals("", info.m3u8Url)
        assertEquals("", info.flvUrl)
        assertEquals("", info.recordUrl)
    }

    @Test
    fun getStreamInfo_noRoom() = runTest {
        val spider = MaoerfmSpider(FakeClient(json = noRoomApiJson))
        val info = spider.getStreamInfo("https://fm.missevan.com/live/868895007")
        assertEquals("猫耳游客", info.anchorName)
        assertFalse(info.isLive)
    }
}