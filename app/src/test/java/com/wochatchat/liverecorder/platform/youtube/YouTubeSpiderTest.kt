package com.wochatchat.liverecorder.platform.youtube

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTubeSpider 单测：ytInitialPlayerResponse 提取 + isLive 判定。 */
class YouTubeSpiderTest {

    @Test
    fun parseVideoId_shortLink() {
        assertEquals("dQw4w9WgXcQ", YouTubeSpider.parseVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("abc123", YouTubeSpider.parseVideoId("https://youtu.be/abc123"))
    }

    @Test
    fun parseVideoId_watch() {
        assertEquals("dQw4w9WgXcQ", YouTubeSpider.parseVideoId(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun parseVideoId_live() {
        assertEquals("liveIdXYZ",
            YouTubeSpider.parseVideoId("https://www.youtube.com/live/liveIdXYZ"))
    }

    @Test
    fun getStreamInfo_noCookie() = runTest {
        // 无 cookie → ytInitialPlayerResponse 不含 videoDetails
        val html = "<html><body>no player</body></html>"
        val spider = YouTubeSpider(FakeClient(html))
        val result = spider.getStreamInfo("https://www.youtube.com/watch?v=abc")
        assertFalse(result.isLive)
        assertEquals("", result.anchorName)
    }

    @Test
    fun getStreamInfo_notLive() = runTest {
        val html = playerResponseHtml(isLive = false)
        val spider = YouTubeSpider(FakeClient(html))
        val result = spider.getStreamInfo("https://www.youtube.com/watch?v=abc")
        assertFalse(result.isLive)
        assertEquals("TestChannel", result.anchorName)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val html = playerResponseHtml(isLive = true, hlsUrl = "https://manifest.google.com/test/live.m3u8")
        val spider = YouTubeSpider(FakeClient(html))
        val result = spider.getStreamInfo("https://www.youtube.com/watch?v=abc")
        assertTrue(result.isLive)
        assertEquals("TestChannel", result.anchorName)
        assertEquals("https://manifest.google.com/test/live.m3u8", result.m3u8Url)
        assertEquals(result.m3u8Url, result.recordUrl)
    }

    @Test
    fun getStreamInfo_noHlsUrl() = runTest {
        val html = playerResponseHtml(isLive = true, hlsUrl = "")
        val spider = YouTubeSpider(FakeClient(html))
        val result = spider.getStreamInfo("https://www.youtube.com/watch?v=abc")
        assertTrue(result.isLive) // isLive=true 但无 m3u8 → recordUrl 空
        assertEquals("", result.m3u8Url)
        assertEquals("", result.recordUrl)
    }

    private fun playerResponseHtml(isLive: Boolean, hlsUrl: String = ""): String {
        val playerResp = JSONObject()
            .put("videoDetails", JSONObject()
                .put("author", "TestChannel")
                .put("title", "Test Stream")
                .put("isLive", isLive))
        if (hlsUrl.isNotEmpty()) {
            playerResp.put("streamingData", JSONObject().put("hlsManifestUrl", hlsUrl))
        }
        return """<html><script>var ytInitialPlayerResponse = ${playerResp};var meta = document.createElement;</script></html>"""
    }

    private class FakeClient(private val html: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, html, url, emptyMap())
    }
}