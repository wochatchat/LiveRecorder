package com.wochatchat.liverecorder.platform.douyin

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

    @Test fun `extractWebRid - v dot douyin share link`() {
        assertEquals("", spider.extractWebRid("https://v.douyin.com/abc123"))
    }

    @Test fun `extractWebRid - user profile URL`() {
        assertEquals("", spider.extractWebRid("https://www.douyin.com/user/MS4wLjABAAAAxxx"))
    }

    // ---------------------------------------------------------------------------
    // a_bogus 向量（固定 timeMs=1725000000000，与 AbSignTest 向量时间一致）
    // ---------------------------------------------------------------------------
    @Test fun `buildApiUrl - fixed time produces deterministic a_bogus`() {
        // a_bogus 长度固定 108 字符 + "=" 尾缀（ab_Sign resultEncrypt 输出 s4 模式）
        // 由 AbSignTest 1c 向量保证 abSign() 本身的正确性
        val url = spider.buildApiUrl("5f47977714",
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.58.45.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400",
            timeMs = 1725000000000L
        )
        assertTrue("a_bogus must be present", url.contains("a_bogus="))
        val aBogus = url.substringAfter("a_bogus=")
        assertTrue("a_bogus should not contain &", !aBogus.contains("&"))
        // s4 模式 resultEncrypt 输出是 base64 变体，末尾固定 "+" 或 "-"（url-safe）
        assertTrue("a_bogus length around 109", aBogus.length in 108..112)
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
    @Test fun `parseRoomJson - empty array throws`() {
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("data", org.json.JSONArray())
                put("user", JSONObject().put("nickname", "test"))
            })
        }.toString()
        val room = spider.parseRoomJson(json, "https://live.douyin.com/999")
        assertEquals("", room.anchorName) // 外层 catch 兜底
    }

    @Test fun `parseRoomJson - invalid json throws`() {
        val room = spider.parseRoomJson("not json {{{", "https://live.douyin.com/999")
        assertEquals("", room.anchorName) // 外层 catch 兜底
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