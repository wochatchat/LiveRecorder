package com.wochatchat.liverecorder.platform.twitch

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TwitchSpider 单测：URL 解析 + usher URL 构建 + GQL 请求与解析。 */
class TwitchSpiderTest {

    @Test
    fun parseChannelLogin_normal() {
        assertEquals("pokimane", TwitchSpider.parseChannelLogin("https://www.twitch.tv/pokimane"))
        assertEquals("xQc", TwitchSpider.parseChannelLogin("https://twitch.tv/xQc?parent=twitch.tv"))
        assertEquals("forsen", TwitchSpider.parseChannelLogin("https://www.twitch.tv/forsen"))
    }

    @Test
    fun parseChannelLogin_edge() {
        assertEquals("", TwitchSpider.parseChannelLogin("https://twitch.tv/"))
    }

    @Test
    fun buildUsherUrl_format() {
        val url = TwitchSpider().buildUsherUrl("pokimane", "test-token-value", "abcdef1234567890")
        assertTrue(url.startsWith("https://usher.ttvnw.net/api/channel/hls/pokimane.m3u8?"))
        assertTrue(url.contains("sig="))
        assertTrue(url.contains("token="))
        assertTrue(url.contains("allow_source=true"))
    }

    @Test
    fun buildUsherUrl_urlEncoded() {
        val url = TwitchSpider().buildUsherUrl(
            "user", "token=with=special&chars", "sig12345678901234",
        )
        assertTrue(url.contains("sig="))
        assertTrue(url.contains("token="))
    }

    @Test
    fun companionConstants() {
        assertEquals("kimne78kx3ncx6brgo4mv6wki5h1ko", TwitchSpider.CLIENT_ID)
        assertEquals(
            "580ab410bcd0c1ad194224957ae2241e5d252b2c5173d8e0cce9d32d5bb14efe",
            TwitchSpider.CHANNEL_SHELL_SHA256,
        )
    }

    @Test
    fun playbackQuery_notEmpty() {
        val q = TwitchSpider.playbackQuery()
        assertTrue(q.contains("PlaybackAccessToken_Template"))
        assertTrue(q.contains("\$login"))
        assertTrue(q.contains("streamPlaybackAccessToken"))
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val tokenJson = JSONObject()
            .put("data", JSONObject()
                .put("streamPlaybackAccessToken", JSONObject()
                    .put("value", "token").put("signature", "sig")))
        val channelJson = JSONArray()
            .put(JSONObject()
                .put("data", JSONObject()
                    .put("userOrError", JSONObject()
                        .put("login", "pokimane")
                        .put("displayName", "Pokimane"))))
        val spider = TwitchSpider(FakeClient(tokenJson, channelJson))
        val result = spider.getStreamInfo("https://twitch.tv/pokimane")
        assertFalse(result.isLive)
        assertEquals("Pokimane-pokimane", result.anchorName)
    }

    @Test
    fun getStreamInfo_noToken() = runTest {
        val emptyJson = JSONObject().put("data", JSONObject.NULL)
        val spider = TwitchSpider(FakeClient(emptyJson, JSONArray()))
        val result = spider.getStreamInfo("https://twitch.tv/test")
        assertFalse(result.isLive)
        assertEquals("", result.anchorName)
    }

    @Test
    fun getStreamInfo_online() = runTest {
        val tokenJson = JSONObject()
            .put("data", JSONObject()
                .put("streamPlaybackAccessToken", JSONObject()
                    .put("value", "live_token").put("signature", "live_sig")))
        val channelJson = JSONArray()
            .put(JSONObject()
                .put("data", JSONObject()
                    .put("userOrError", JSONObject()
                        .put("login", "xqc")
                        .put("displayName", "xQcOW")
                        .put("stream", JSONObject()))))
        val spider = TwitchSpider(FakeClient(tokenJson, channelJson))
        val result = spider.getStreamInfo("https://twitch.tv/xqc")
        assertTrue(result.isLive)
        assertTrue(result.anchorName.contains("xQc"))
        assertTrue(result.m3u8Url.contains("usher.ttvnw.net"))
        assertTrue(result.m3u8Url.contains("xqc"))
    }

    private class FakeClient(
        private val tokenJson: JSONObject,
        private val channelJson: JSONArray,
    ) : LiveHttpClient() {
        private var callCount = 0
        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: okhttp3.RequestBody,
            timeoutSec: Long,
        ): HttpResult {
            callCount++
            val text = if (callCount == 1) tokenJson.toString() else channelJson.toString()
            return HttpResult(200, text, url, emptyMap())
        }
    }
}
