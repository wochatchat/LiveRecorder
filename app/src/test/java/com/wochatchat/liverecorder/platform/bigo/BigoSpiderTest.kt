package com.wochatchat.liverecorder.platform.bigo

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BigoSpiderTest {

    @Test
    fun isBigoUrl_recognizes() {
        assertTrue(BigoSpider.isBigoUrl("https://www.bigo.tv/600024469"))
        assertTrue(BigoSpider.isBigoUrl("https://slink.bigovideo.tv/x/abc?e=1&h=123"))
        assertFalse(BigoSpider.isBigoUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun parseRoomId_bigoUrl() {
        assertEquals("123", BigoSpider.parseRoomId("https://www.bigo.tv/600024469?entry=x&h=123"))
        assertEquals("600024469", BigoSpider.parseRoomId("https://www.bigo.tv/600024469"))
        assertNull(BigoSpider.parseRoomId("https://slink.bigovideo.tv/abc"))
    }

    @Test
    fun webUrlToRoomId_extractsH() {
        assertEquals("987654", BigoSpider.webUrlToRoomId("https://www.bigo.tv/s/123?x=1&amp;h=987654"))
        assertNull(BigoSpider.webUrlToRoomId("https://www.bigo.tv/s/123"))
    }

    @Test
    fun parseAnchorFromHtml_twoPatterns() {
        assertEquals("小明", BigoSpider.parseAnchorFromHtml("<title>欢迎来到小明的直播间</title>"))
        assertEquals(
            "Anna",
            BigoSpider.parseAnchorFromHtml(
                "<meta data-hid=\"og:title\" property=\"og:title\" content=\"Anna - BIGO LIVE\">",
            ),
        )
        assertEquals("", BigoSpider.parseAnchorFromHtml("<html></html>"))
    }

    // ---- 端到端 fixture ----

    private class FakeBigoClient(
        private val alive: Int = 1,
        private val nick: String = "Bigo主播",
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, "<title>欢迎来到回退名的直播间</title>", url, emptyMap())
        override suspend fun postForm(
            url: String, headers: Map<String, String>, form: Map<String, String>, timeoutSec: Long,
        ): HttpResult = HttpResult(200, if (alive == 1) {
            """{"code":0,"data":{"nick_name":"$nick","alive":1,"roomTopic":"T1","hls_src":"https://hls.bigo.tv/a.m3u8"}}"""
        } else {
            """{"code":0,"data":{"nick_name":"$nick","alive":0,"roomTopic":"","hls_src":""}}"""
        }, url, emptyMap())
    }

    @Test
    fun getBigoStreamInfo_live() = runTest {
        val info = BigoSpider(FakeBigoClient(alive = 1, nick = "Bigo主播"))
            .getBigoStreamInfo("https://www.bigo.tv/600024469")
        assertTrue(info.isLive)
        assertEquals("Bigo主播", info.anchorName)
        assertEquals("T1", info.title)
        assertEquals("https://hls.bigo.tv/a.m3u8", info.m3u8Url)
        assertEquals("https://hls.bigo.tv/a.m3u8", info.recordUrl)
    }

    @Test
    fun getBigoStreamInfo_offline_withAnchor() = runTest {
        val info = BigoSpider(FakeBigoClient(alive = 0, nick = "未播主播"))
            .getBigoStreamInfo("https://www.bigo.tv/600024469")
        assertFalse(info.isLive)
        assertEquals("未播主播", info.anchorName)
    }
