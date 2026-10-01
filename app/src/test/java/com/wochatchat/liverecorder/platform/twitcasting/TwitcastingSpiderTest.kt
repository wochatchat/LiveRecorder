package com.wochatchat.liverecorder.platform.twitcasting

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitcastingSpiderTest {

    private var reqCount = 0

    private inner class FakeClient(
        private val pageHtml: String,
        private val streamJson: String? = null,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return if (url.contains("streamserver.php")) {
                HttpResult(200, streamJson ?: STREAM_JSON, url, emptyMap())
            } else {
                HttpResult(200, pageHtml, url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_returnsAnchorId() {
        assertEquals("broadcaster", TwitcastingSpider.parseRoomId("https://twitcasting.tv/broadcaster"))
        assertEquals("broadcaster", TwitcastingSpider.parseRoomId("https://twitcasting.tv/broadcaster?lang=ja"))
        assertEquals("", TwitcastingSpider.parseRoomId("https://twitcasting.tv/"))
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = TwitcastingSpider(FakeClient(OFFLINE_PAGE))
        val info = spider.getStreamInfo("https://twitcasting.tv/broadcaster")
        assertEquals("Broadcaster-broadcaster-67890", info.anchorName)
        assertFalse(info.isLive)
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = TwitcastingSpider(FakeClient(LIVE_PAGE))
        val info = spider.getStreamInfo("https://twitcasting.tv/broadcaster")
        assertEquals("Broadcaster-broadcaster-12345", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("Stream Title", info.title)
        assertEquals("https://hls.01.02.03.com/playlist.m3u8", info.m3u8Url)
        assertEquals(2, reqCount)
    }

    @Test
    fun getStreamInfo_noRoomId() = runTest {
        val spider = TwitcastingSpider(FakeClient(LIVE_PAGE))
        val info = spider.getStreamInfo("https://twitcasting.tv/")
        assertFalse(info.isLive)
    }

    companion object {
        private const val LIVE_PAGE = """<!DOCTYPE html>
<html><head><title>Broadcaster (@broadcaster)  的直播 - Twit</title></head>
<body data-is-onlive="true" data-view-mode="..." data-movie-id="12345">
<meta name="twitter:title" content="Stream Title">
<meta name="other" content="x">
</body></html>"""

        private const val OFFLINE_PAGE = """<!DOCTYPE html>
<html><head><title>Broadcaster (@broadcaster)  的直播 - Twit</title></head>
<body data-is-onlive="false" data-view-mode="..." data-movie-id="67890"></body></html>"""

        private const val STREAM_JSON = """{"tc-hls":{"streams":{"high":"https://hls.01.02.03.com/playlist.m3u8","medium":"https://hls.01.02.04.com/playlist.m3u8","low":"https://hls.01.02.05.com/playlist.m3u8"}}}"""
    }
}