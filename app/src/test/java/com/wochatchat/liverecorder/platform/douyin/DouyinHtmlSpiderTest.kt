package com.wochatchat.liverecorder.platform.douyin

package com.wochatchat.liverecorder.platform.douyin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTML 路径爬虫单测（对照上游 src/spider.py:230 get_douyin_stream_data）。
 * fixture：真实在线房间（小央视频）直播页的最小化还原，保留原始转义结构，
 * 与上游 regex 链路一致（沙箱 2026-09-20 实抓验证）。
 */
class DouyinHtmlSpiderTest {

    private val spider = DouyinHtmlSpider()

    private val rawHtml: String
        get() = javaClass.getResource("/douyin_html_live_raw.txt")!!.readText()

    @Test fun `parseHtml - live room extracts anchor status and merged quality maps`() {
        val room = spider.parseHtml(rawHtml)
        assertEquals("小央视频", room.anchorName)
        assertEquals(2, room.status)
        assertEquals(true, room.isLive)
        // ORIGIN 合并后 5 档：ORIGIN 在首位 + FULL_HD1/HD1/SD1/SD2
        assertEquals(listOf("ORIGIN", "FULL_HD1", "HD1", "SD1", "SD2"), room.hlsPullUrlMap.keys.toList())
        assertEquals(listOf("ORIGIN", "FULL_HD1", "HD1", "SD1", "SD2"), room.flvPullUrl.keys.toList())
        // ORIGIN 带 &codec=h264（真实抓取值，上游 HTML 路径无条件追加 &codec=）
        assertTrue(room.hlsPullUrlMap["ORIGIN"]!!.endsWith("&codec=h264"))
        assertTrue(room.flvPullUrl["ORIGIN"]!!.contains("&codec=h264"))
    }

    @Test fun `parseHtml - ORIGIN urls match real captured values`() {
        val room = spider.parseHtml(rawHtml)
        assertEquals(
            "http://pull-hls-f11.douyinliving.com/media/stream-408346413405832018.m3u8",
            room.hlsPullUrlMap["ORIGIN"]!!.substringBefore("&codec")
        )
        assertEquals(
            "http://pull-flv-f11.douyinliving.com/media/stream-408346413405832018.f",
            room.flvPullUrl["ORIGIN"]!!.substringBefore("&codec").take(64)
        )
    }

    // parseHtml 不做兜底（catch 在 fetch 层），对齐上游：异常抛给外层
    @Test(expected = RuntimeException::class)
    fun `parseHtml - garbage html throws`() {
        spider.parseHtml("<html><body>no state blob</body></html>")
    }

    @Test fun `requestHeaders - uses firefox UA and hardcoded cookie`() {
        val headers = spider.requestHeaders()
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0",
            headers["User-Agent"]
        )
        assertEquals("https://live.douyin.com/", headers["Referer"])
        assertTrue(headers["Cookie"]!!.startsWith("ttwid="))
    }
}