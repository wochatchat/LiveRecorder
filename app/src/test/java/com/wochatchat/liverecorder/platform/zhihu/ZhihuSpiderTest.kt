package com.wochatchat.liverecorder.platform.zhihu

import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 知乎单测：webId 解析 + js-initialData 端到端。 */
class ZhihuSpiderTest {

    private fun theaterHtml(webId: String, status: Int, anchor: String = "知乎主播", title: String = "知乎直播标题"): String {
        val theater = JSONObject().put(webId, JSONObject()
            .put("actor", JSONObject().put("name", anchor))
            .put("theme", title)
            .put("drama", JSONObject()
                .put("status", status)
                .put("playInfo", JSONObject().put("hlsUrl", "http://hls/stream.m3u8").put("playUrl", "http://flv/stream.flv"))))
        return """<html><script id="js-initialData" type="text/json">${JSONObject()
            .put("initialState", JSONObject().put("theater", JSONObject().put("theaters", theater)))}</script></html>"""
    }

    private class FakeClient(private val html: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, html, url, emptyMap())
    }

    @Test
    fun parseWebId_extractsLastSegment() {
        assertEquals("98765", ZhihuSpider.parseWebId("https://www.zhihu.com/live/98765?utm=x"))
        assertEquals("abc", ZhihuSpider.parseWebId("https://www.zhihu.com/live/abc"))
        assertEquals("live", ZhihuSpider.parseWebId("https://x.zhihu.com/live"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = ZhihuSpider(FakeClient(theaterHtml("98765", 1)))
        val info = spider.getStreamInfo("https://www.zhihu.com/live/98765")
        assertTrue(info.isLive)
        assertEquals("知乎主播", info.anchorName)
        assertEquals("知乎直播标题", info.title)
        assertTrue(info.recordUrl.isNotBlank())
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = ZhihuSpider(FakeClient(theaterHtml("98765", 0, "离线主播")))
        val info = spider.getStreamInfo("https://www.zhihu.com/live/98765")
        assertFalse(info.isLive)
        assertEquals("离线主播", info.anchorName)
    }
}