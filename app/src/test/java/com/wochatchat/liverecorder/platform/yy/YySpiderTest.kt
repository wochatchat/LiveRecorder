package com.wochatchat.liverecorder.platform.yy

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YySpiderTest {

    @Test
    fun isYyUrl_recognizesYy() {
        assertTrue(YySpider.isYyUrl("https://www.yy.com/123456"))
        assertFalse(YySpider.isYyUrl("https://m.yy.com/123"))
        assertFalse(YySpider.isYyUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun parseSid_basic() {
        assertEquals("1355280876", YySpider.parseSid("https://www.yy.com/1355280876"))
        assertEquals("1355280876", YySpider.parseSid("https://www.yy.com/1355280876?from=abc"))
        assertNull(YySpider.parseSid("https://www.yy.com/"))
    }

    // ---- 端到端 fixture（open LiveHttpClient 子类按 URL 分发）----

    private class FakeYyClient(
        private val isLive: Boolean = true,
        private val detailTitle: String = "精彩直播",
        private val anchor: String = "测试主播",
    ) : LiveHttpClient() {
        private val liveHtml = "nick: \"$anchor\",\n    logo: 'x'\nsid : \"54880976\",\n    ssid: '54880976'"
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, if ("live/detail" in url) {
                """{"code":0,"data":{"roomName":"$detailTitle"}}"""
            } else liveHtml, url, emptyMap())
        override suspend fun post(
            url: String, headers: Map<String, String>, body: okhttp3.RequestBody, timeoutSec: Long,
        ): HttpResult = HttpResult(200, if (isLive) {
            """{"avp_info_res":{"stream_line_addr":{"cdn_1":{"cdn_info":{"url":"https://ks-flv-web.yy.com/live/a.flv"}}}}}"""
        } else {
            """{"channel_stream_info":{"streams":[]}}"""
        }, url, emptyMap())
    }

    // ---- 在播 / 未播 ----

    @Test
    fun getYyStreamInfo_live() = runTest {
        val info = YySpider(FakeYyClient(isLive = true)).getYyStreamInfo("https://www.yy.com/54880976")
        assertTrue(info.isLive)
        assertEquals("测试主播", info.anchorName)
        assertEquals("精彩直播", info.title)
        assertEquals("OD", info.quality)
        assertEquals("https://ks-flv-web.yy.com/live/a.flv", info.flvUrl)
    }

    @Test
    fun getYyStreamInfo_offline() = runTest {
        val info = YySpider(FakeYyClient(isLive = false)).getYyStreamInfo("https://www.yy.com/54880976")
        assertFalse(info.isLive)
        assertEquals("", info.flvUrl)
        assertEquals("", info.quality)
    }

    @Test
    fun getYyStreamInfo_regexFallbackToPathSid() = runTest {
        // 页面正则不命中 → 回落 parseSid 取 URL 段
        val badHtmlClient = object : LiveHttpClient() {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
                HttpResult(200, "<html>no markers</html>", url, emptyMap())
            override suspend fun post(
                url: String, headers: Map<String, String>, body: okhttp3.RequestBody, timeoutSec: Long,
            ): HttpResult = HttpResult(200, """{"channel_stream_info":{"streams":[]}}""", url, emptyMap())
        }
        val info = YySpider(badHtmlClient).getYyStreamInfo("https://www.yy.com/987654")
        assertFalse(info.isLive)
        assertEquals("987654", info.cid)
    }
}
