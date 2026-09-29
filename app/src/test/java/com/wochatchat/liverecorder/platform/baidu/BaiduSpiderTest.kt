package com.wochatchat.liverecorder.platform.baidu

import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 百度单测：roomId 解析 + API 端到端。 */
class BaiduSpiderTest {

    private class FakeClient(private val apiResponse: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, apiResponse, url, emptyMap())
    }

    private fun apiJson(status: String, hasUrlList: Boolean): String {
        val data = JSONObject().put("status", status)
            .put("host", JSONObject().put("name", "百度主播"))
        if (status == "0") {
            val video = JSONObject().put("title", "百度直播")
            if (hasUrlList) {
                video.put("url_list", org.json.JSONArray()
                    .put(JSONObject().put("urls", org.json.JSONArray().put(JSONObject().put("hls", "https://hls.bd.com/live/abc.m3u8")))))
            }
            data.put("video", video)
        }
        return JSONObject().put("data", JSONObject().put("key0", data)).toString()
    }

    @Test
    fun parseRoomId() {
        assertEquals("9175031377", BaiduSpider.parseRoomId("https://live.baidu.com/weibo?room_id=9175031377&from=live"))
        assertEquals("123", BaiduSpider.parseRoomId("https://live.baidu.com/live.php?room_id=123&abc=def"))
        assertNull(BaiduSpider.parseRoomId("https://live.baidu.com/"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = BaiduSpider(FakeClient(apiJson("0", true)))
        val info = spider.getStreamInfo("https://live.baidu.com/weibo?room_id=abc&x=1")
        assertTrue(info.isLive)
        assertEquals("百度主播", info.anchorName)
        assertNotNull(info.m3u8Url)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = BaiduSpider(FakeClient(apiJson("1", false)))
        val info = spider.getStreamInfo("https://live.baidu.com/weibo?room_id=abc")
        assertFalse(info.isLive)
    }
}