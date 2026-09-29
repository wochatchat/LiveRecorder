package com.wochatchat.liverecorder.platform.netease

import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 网易CC单测：画质索引/JSON 提取静态断言 + FakeClient 端到端（上游 fixture 结构）。 */
class NeteaseCcSpiderTest {

    private fun nextDataHtml(live: JSONObject): String =
        """<html><script id="__NEXT_DATA__" type="application/json" crossorigin="anonymous">${JSONObject()
            .put("props", JSONObject().put("pageProps", JSONObject().put("roomInfoInitData",
                JSONObject().put("nickname", "房间主").put("live", live))))
        }</script></body></html>"""

    private fun liveJson(status: Int, withQuickplay: Boolean = true): JSONObject {
        val resolution = JSONObject()
            .put("blueray", JSONObject().put("cdn", JSONObject().put("ws-tct.douyucdn.cn", "http://flv/blueray.flv")))
            .put("high", JSONObject().put("cdn", JSONObject().put("ws", "http://flv/high.flv")))
        val quickplay = if (withQuickplay) JSONObject().put("resolution", resolution) else null
        val live = JSONObject().put("status", status).put("nickname", "CC主播")
            .put("title", "CC测试标题").put("sharefile", "http://m3u8.example/a.m3u8")
        quickplay?.let { live.put("quickplay", it) }
        return live
    }

    private class FakeClient(private val html: String) : LiveHttpClient() {
        var lastUrl = ""
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, html, url, emptyMap()).also { lastUrl = url }
    }

    @Test
    fun qualityIndexMapping() {
        assertEquals(0, NeteaseCcSpider.qualityIndex("OD"))
        assertEquals(0, NeteaseCcSpider.qualityIndex(null))
        assertEquals(1, NeteaseCcSpider.qualityIndex("HD"))
        assertEquals(2, NeteaseCcSpider.qualityIndex("SD"))
    }

    @Test
    fun selectFlv_byQualityIndex() {
        val qp = JSONObject().put("resolution", JSONObject()
            .put("blueray", JSONObject().put("cdn", JSONObject().put("c1", "http://f/blue.flv")))
            .put("high", JSONObject().put("cdn", JSONObject().put("c2", "http://flv/high.flv"))))
        val spider = NeteaseCcSpider()
        assertEquals("http://flv/high.flv", spider.selectFlv(qp, "HD"))
        assertEquals("http://f/blue.flv", spider.selectFlv(qp, "OD"))
        assertNull(spider.selectFlv(null, "OD"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = NeteaseCcSpider(FakeClient(nextDataHtml(liveJson(1))))
        val info = spider.getStreamInfo("https://cc.163.com/123456")
        assertTrue(info.isLive)
        assertEquals("CC主播", info.anchorName)
        assertEquals("CC测试标题", info.title)
        assertTrue(info.m3u8Url.isNotBlank())
        assertTrue(info.recordUrl.isNotBlank())
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = NeteaseCcSpider(FakeClient(nextDataHtml(liveJson(0))))
        val info = spider.getStreamInfo("http://cc.163.com/123")
        assertFalse(info.isLive)
        assertEquals("CC主播", info.anchorName)
    }

    @Test
    fun extractNextData_missing() {
        assertNull(NeteaseCcSpider.extractNextData("<html>no data</html>"))
    }
}