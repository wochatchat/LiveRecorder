/*
 * BilibiliSpiderTest — Phase 4 第一批：B 站爬虫单元测试。
 *
 * 覆盖：parseRoomId / VIDEO_QUALITY_OPTIONS / getRoomInfo（端到端）/
 *      getStreamData（durl gotcha 优先 + 末位回落）。
 */
package com.wochatchat.liverecorder.platform.bilibili

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliSpiderTest {

    private val spider = BilibiliSpider()

    // ---- 1. 静态 ----

    @Test
    fun parseRoomId() {
        assertEquals("26066074", BilibiliSpider.parseRoomId("https://live.bilibili.com/26066074"))
        assertEquals("26066074", BilibiliSpider.parseRoomId("https://live.bilibili.com/26066074?extra=abc"))
        assertNull(BilibiliSpider.parseRoomId("https://live.bilibili.com/"))
    }

    @Test
    fun qualityOptions_map() {
        assertEquals(0, BilibiliSpider.VIDEO_QUALITY_OPTIONS["10000"])
        assertEquals(1, BilibiliSpider.VIDEO_QUALITY_OPTIONS["400"])
        assertEquals(4, BilibiliSpider.VIDEO_QUALITY_OPTIONS["80"])
    }

    // ---- 2. 端到端 fixture（open LiveHttpClient 子类按 URL 分发）----

    private class BiliE2EClient(
        private val roomInit: String = """{"code":0,"data":{"uid":123,"live_status":1}}""",
        private val master: String = """{"code":0,"data":{"info":{"uname":"测试主播"}}}""",
        private val h5: String = """{"code":0,"data":{"room_info":{"title":"测试标题"}}}""",
        private val play: String = """{"code":0,"data":{"durl":[{"url":"https://d1--cn-gotcha.bilibili.com/x.flv"}]}}""",
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, when {
                "room_init" in url -> roomInit
                "Master/info" in url -> master
                "getH5InfoByRoom" in url -> h5
                else -> play
            }, url, emptyMap())
    }

    // ---- 3. getRoomInfo ----

    @Test
    fun getRoomInfo_live() = runTest {
        val info = BilibiliSpider(BiliE2EClient()).getRoomInfo("https://live.bilibili.com/26066074")
        assertTrue(info.isLive)
        assertEquals("测试主播", info.anchorName)
        assertEquals("测试标题", info.title)
    }

    @Test
    fun getRoomInfo_offline() = runTest {
        val client = BiliE2EClient(roomInit = """{"code":0,"data":{"uid":123,"live_status":0}}""")
        val info = BilibiliSpider(client).getRoomInfo("https://live.bilibili.com/26066074")
        assertFalse(info.isLive)
        assertEquals("测试主播", info.anchorName)
    }

    // ---- 3. getStreamData（durl 选择）----

    @Test
    fun getStreamData_gotchaSelected() = runTest {
        val client = BiliE2EClient(
            play = """{"code":0,"data":{"durl":[
                {"order":0,"url":"https://other.bilibili.com/a.flv"},
                {"order":1,"url":"https://d1--cn-gotcha.bilibili.com/b.flv"}]}}""",
        )
        val url = BilibiliSpider(client).getStreamData("https://live.bilibili.com/26066074", "10000")
        assertEquals("https://d1--cn-gotcha.bilibili.com/b.flv", url)
    }

    @Test
    fun getStreamData_lastUrlFallback() = runTest {
        val client = BiliE2EClient(
            play = """{"code":0,"data":{"durl":[{"order":0,"url":"https://a.invalid/1.flv"},
                {"order":1,"url":"https://b.invalid/last.flv"}]}}""",
        )
        val url = BilibiliSpider(client).getStreamData("https://live.bilibili.com/26066074", "10000")
        assertEquals("https://b.invalid/last.flv", url)
    }
}
