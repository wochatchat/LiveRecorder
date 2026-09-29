package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinWebSpiderTest {

    private val spider = DouyinWebSpider()

    // ---------------------------------------------------------------------------
    // fixture: 真实在线房间（status=2，live_core_sdk_data 路径）
    // Python 原版在沙箱用固定 cookie / 固定 timeMs 抓取，存于 test/resources
    // ---------------------------------------------------------------------------
    private val liveRawJson: String
        get() = javaClass.getResource("/douyin_web_live_raw.json")!!.readText()

    private val offlineRawJson: String
        get() = javaClass.getResource("/douyin_web_offline_raw.json")!!.readText()

    @Test fun `extractWebRid - normal URL`() {
        assertEquals("33535", spider.extractWebRid("https://live.douyin.com/33535"))
    }

    @Test fun `extractWebRid - URL with query`() {
        assertEquals("5f47977714", spider.extractWebRid("https://live.douyin.com/5f47977714?show_type=live_cover"))
    }

    // v.douyin.com / user 链接由调用方路由到 app 路径（main.py 分流），
    // extractWebRid 本身语义与上游 Python split 一致：无 live.douyin.com/ 分隔符时返回原串
    @Test fun `extractWebRid - v douyin share link returns whole url as upstream`() {
        assertEquals("https://v.douyin.com/abc123", spider.extractWebRid("https://v.douyin.com/abc123"))
    }

    @Test fun `extractWebRid - user profile URL returns whole url as upstream`() {
        assertEquals(
            "https://www.douyin.com/user/MS4wLjABAAAAxxx",
            spider.extractWebRid("https://www.douyin.com/user/MS4wLjABAAAAxxx")
        )
    }

    // ---------------------------------------------------------------------------
    // a_bogus 向量（固定 timeMs=1725000000000，与 AbSignTest 向量时间一致）
    // ---------------------------------------------------------------------------
    @Test fun `buildApiUrl - fixed time produces deterministic a_bogus`() {
        // 沙箱 Python 原版 ab_sign 固定 timeMs=1725000000000 输出（web_rid=547977714661）
        val expectedABogus =
            "E7mhBmg6mEVNgf6X5V5LfY3q6-Z3YIhj0HViMD2fyVvw7g39HMYD9exo0XivZ/WjN4/" +
                "kIeYjy4hbO3xprQAjM36UHWwEUdQ2mgWkKl5Q5I0j53iruyRDntmF4vj3SFlm5XNAEOk0y75rKb70Woqe-vIlO62-zo0/9R8="
        val url = spider.buildApiUrl("547977714661", LiveHttpClient.DEFAULT_UA, timeMs = 1725000000000L)
        val expectedQuery = "aid=6383&app_name=douyin_web&live_id=1&device_platform=web" +
            "&language=zh-CN&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome" +
            "&browser_version=116.0.0.0&web_rid=547977714661&msToken="
        assertEquals(
            "https://live.douyin.com/webcast/room/web/enter/?$expectedQuery&a_bogus=$expectedABogus",
            url
        )
    }

    // ---------------------------------------------------------------------------
    // 在线房间解析（status=2，live_core_sdk_data 路径，ORIGIN 已合并）
    // ---------------------------------------------------------------------------
    @Test fun `parseRoomJson - live room returns isLive and stream urls`() {
        val room = spider.parseRoomJson(liveRawJson, "https://live.douyin.com/5f47977714")
        assertEquals("小央视频", room.anchorName)
        assertEquals(2, room.status)
        assertTrue(room.isLive)
        assertEquals("央视《新闻频道》正在直播！", room.title)
    }

    @Test fun `parseRoomJson - live room has five quality levels with ORIGIN`() {
        val room = spider.parseRoomJson(liveRawJson, "https://live.douyin.com/5f47977714")
        assertTrue("flvPullUrl has ORIGIN", room.flvPullUrl.containsKey("ORIGIN"))
        assertTrue("hlsPullUrlMap has ORIGIN", room.hlsPullUrlMap.containsKey("ORIGIN"))
        // 上游: ['FULL_HD1', 'HD1', 'ORIGIN', 'SD1', 'SD2']
        assertTrue("flv has FULL_HD1", room.flvPullUrl.containsKey("FULL_HD1"))
        assertTrue("flv has HD1", room.flvPullUrl.containsKey("HD1"))
        assertTrue("flv has SD1", room.flvPullUrl.containsKey("SD1"))
        assertTrue("flv has SD2", room.flvPullUrl.containsKey("SD2"))
        assertEquals(5, room.flvPullUrl.size)
        assertEquals(5, room.hlsPullUrlMap.size)
    }

    @Test fun `parseRoomJson - live room stream URLs are non-empty`() {
        val room = spider.parseRoomJson(liveRawJson, "https://live.douyin.com/5f47977714")
        room.flvPullUrl.values.forEach { url ->
            assertTrue("flv URL should be non-empty", url.isNotEmpty())
            assertTrue("flv URL should end with flv", url.endsWith(".flv") || url.contains("flv?") || url.contains("codec="))
        }
        room.hlsPullUrlMap.values.forEach { url ->
            assertTrue("hls URL should be non-empty", url.isNotEmpty())
        }
    }

    @Test fun `parseRoomJson - live room ORIGIN url contains codec param`() {
        val room = spider.parseRoomJson(liveRawJson, "https://live.douyin.com/5f47977714")
        val originFlv = room.flvPullUrl["ORIGIN"]
        assertNotNull("ORIGIN flv should exist", originFlv)
        assertTrue("ORIGIN flv should contain codec=", originFlv!!.contains("codec="))
    }

    // ---------------------------------------------------------------------------
    // 离线房间解析（status=4，无 stream_url）
    // ---------------------------------------------------------------------------
    @Test fun `parseRoomJson - offline room status is 4 not live`() {
        val room = spider.parseRoomJson(offlineRawJson, "https://live.douyin.com/33535")
        assertEquals("享趣玩北京导游大勇", room.anchorName)
        assertEquals(4, room.status)
        assertFalse(room.isLive)
        assertEquals("大勇带您聊京城", room.title)
    }

    @Test fun `parseRoomJson - offline room has empty stream maps`() {
        val room = spider.parseRoomJson(offlineRawJson, "https://live.douyin.com/33535")
        assertTrue(room.flvPullUrl.isEmpty())
        assertTrue(room.hlsPullUrlMap.isEmpty())
    }

    // ---------------------------------------------------------------------------
    // 异常兜底（对齐上游语义）
    // ---------------------------------------------------------------------------
    // parseRoomJson 不做兜底（catch 在 fetch 层），对齐上游：异常抛给外层
    @Test(expected = RuntimeException::class)
    fun `parseRoomJson - empty data array throws`() {
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("data", org.json.JSONArray())
                put("user", JSONObject().put("nickname", "test"))
            })
        }.toString()
        spider.parseRoomJson(json, "https://live.douyin.com/999")
    }

    @Test(expected = org.json.JSONException::class)
    fun `parseRoomJson - invalid json throws`() {
        spider.parseRoomJson("not json {{{", "https://live.douyin.com/999")
    }

    // ---------------------------------------------------------------------------
    // DouyinWebRoom 伴生对象
    // ---------------------------------------------------------------------------
    @Test fun `empty - anchorName is empty string`() {
        val room = DouyinWebRoom.empty()
        assertEquals("", room.anchorName)
        assertFalse(room.isLive)
        assertTrue(room.fetchError == null)
    }

    @Test fun `empty with error - error is preserved`() {
        val room = DouyinWebRoom.empty("test error")
        assertEquals("", room.anchorName)
        assertEquals("test error", room.fetchError)
    }
}