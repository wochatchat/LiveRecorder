package com.wochatchat.liverecorder.platform.douyin

import com.wochatchat.liverecorder.net.LiveHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 抖音 app 路径爬虫测试。
 *
 * fixture 说明：douyin_app_live_raw.json 为 reflow/info 响应结构（data.room + room.owner.nickname），
 * 房间体取自 1d 沙箱实抓的 web fixture（同一房间数据，stream_url 结构与 reflow 响应一致）；
 * 真实端到端抓取对照在 1h 收尾统一验证。a_bogus 真值来自沙箱 Python 原版
 * （固定 timeMs=1725000000000，与 AbSignTest 向量时间一致）。
 */
class DouyinAppSpiderTest {

    private val spider = DouyinAppSpider()

    // -----------------------------------------------------------------
    // 分流
    // -----------------------------------------------------------------

    @Test fun `isWebUrl - live douyin url routes to web path`() {
        assertTrue(spider.isWebUrl("https://live.douyin.com/335354047186"))
        assertTrue(spider.isWebUrl("https://live.douyin.com/5f47977714?show_type=live_cover"))
    }

    @Test fun `isWebUrl - share short link routes to app path`() {
        assertFalse(spider.isWebUrl("https://v.douyin.com/iAbCdEf/"))
        assertFalse(spider.isWebUrl("https://v.douyin.com/iAbCdEf/?from=live.douyin.com/x"))
    }

    // -----------------------------------------------------------------
    // a_bogus 向量（沙箱 Python 原版固定 timeMs=1725000000000 实测输出）
    // -----------------------------------------------------------------

    @Test fun `buildApiUrl - fixed time produces deterministic a_bogus`() {
        val roomId = "7298532089907644709"
        val secUid = "MS4wLjABAAAAexample_sec_user_id_1234567890_-AbCdEfGh"
        val expectedQuery = "verifyFp=verify_hwj52020_7szNlAB7_pxNY_48Vh_ALKF_GA1Uf3yteoOY" +
            "&type_id=0&live_id=1&room_id=$roomId" +
            "&sec_user_id=$secUid&version_code=99.99.99&app_id=1128"
        val expectedABogus =
            "E7mhBmg6mEVNgf6X5V5LfY3q6RZ3Yl7j0HViMD2fjnvU7g39HMYD9exo0XivZ/WjN4/" +
                "kIeYjy4hbO3xprQAjM36UHWwEUdQ2mgWkKl5Q5I0j53iruyRDntmF4vj3SFlm5XNAEOk0y75rKb70Woqe-vIlO62-zo0/9f6="
        val url = spider.buildApiUrl(roomId, secUid, APP_UA, timeMs = 1725000000000L)
        assertEquals("https://webcast.amemv.com/webcast/room/reflow/info/?$expectedQuery&a_bogus=$expectedABogus", url)
    }

    // -----------------------------------------------------------------
    // reflow 响应解析（data.room + owner.nickname，与 web 路径位置不同）
    // -----------------------------------------------------------------

    private val liveRawJson: String
        get() = javaClass.getResource("/douyin_app_live_raw.json")!!.readText()

    @Test fun `parseRoomJson - live room returns anchor from owner and stream urls`() {
        val room = spider.parseRoomJson(liveRawJson)
        assertEquals("小央视频", room.anchorName)
        assertEquals(2, room.status)
        assertTrue(room.isLive)
        assertEquals("央视《新闻频道》正在直播！", room.title)
    }

    @Test fun `parseRoomJson - live room has quality levels with ORIGIN merged`() {
        val room = spider.parseRoomJson(liveRawJson)
        assertTrue(room.flvPullUrl.containsKey("ORIGIN"))
        assertTrue(room.hlsPullUrlMap.containsKey("ORIGIN"))
        assertEquals(5, room.flvPullUrl.size)
        // ORIGIN 档在 map 最前（上游 {**origin, **原map} 合并语义）
        assertEquals("ORIGIN", room.flvPullUrl.keys.first())
    }

    @Test fun `parseRoomJson - no room throws VR not supported`() {
        val json = """{"data": {"user": {"nickname": "x"}}}"""
        try {
            spider.parseRoomJson(json)
            fail("expected RuntimeException")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("VR live is not supported"))
        }
    }

    // -----------------------------------------------------------------
    // unique_id 提取（get_unique_id 兜底路径）
    // -----------------------------------------------------------------

    @Test fun `extractUniqueId - takes last match as upstream`() {
        val html = """{"a":"unique_id":"111","verification_type":1","b":"unique_id":"xiaoyanglaile","verification_type":2"}"""
        assertEquals("xiaoyanglaile", spider.extractUniqueId(html))
    }

    @Test fun `extractUniqueId - no match throws`() {
        try {
            spider.extractUniqueId("""{"foo":1}""")
            fail("expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("Could not find unique_id in the response.", e.message)
        }
    }

    // -----------------------------------------------------------------
    // headers 对齐上游（room.py HEADERS / spider.py app headers）
    // -----------------------------------------------------------------

    @Test fun `mobileHeaders - mobile UA and s_v_web_id cookie`() {
        val h = spider.mobileHeaders()
        assertTrue(h["User-Agent"]!!.contains("SamsungBrowser/14.2"))
        assertTrue(h["Cookie"]!!.startsWith("s_v_web_id=verify_"))
    }

    private companion object {
        // 与 DouyinAppSpider.APP_UA 一致（spider.py app headers 的 Edge UA）
        private const val APP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0"
    }
}
