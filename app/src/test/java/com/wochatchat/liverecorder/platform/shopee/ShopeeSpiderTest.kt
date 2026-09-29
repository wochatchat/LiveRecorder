package com.wochatchat.liverecorder.platform.shopee

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ShopeeSpider 单测：cookie 校验 + URL 参数提取 + session 解析。 */
class ShopeeSpiderTest {

    @Test
    fun companionUrl() {
        assertTrue(ShopeeSpider.isShopeeUrl("https://live.shopee.sg/share?session=123&uid=456"))
        assertTrue(ShopeeSpider.isShopeeUrl("https://shp.ee/abc123"))
        assertFalse(ShopeeSpider.isShopeeUrl("https://www.youtube.com/watch?v=xxx"))
    }

    @Test
    fun extractParam() {
        val spider = ShopeeSpider()
        assertEquals("123", spider.extractParam("https://x.com/?uid=123&session=456", "uid"))
        assertEquals("456", spider.extractParam("https://x.com/?uid=123&session=456", "session"))
        assertNull(spider.extractParam("https://x.com/", "uid"))
        assertNull(spider.extractParam("https://x.com/?uid=", "uid"))
    }

    @Test
    fun getStreamInfo_noCookie() = runTest {
        val spider = ShopeeSpider(FakeClient("{}"))
        val result = spider.getStreamInfo("https://live.shopee.sg/share?session=123", cookie = null)
        assertFalse(result.isLive)
        assertEquals("", result.anchorName)
    }

    @Test
    fun getStreamInfo_cookieNoTk() = runTest {
        val spider = ShopeeSpider(FakeClient("{}"))
        val result = spider.getStreamInfo(
            "https://live.shopee.sg/share?session=123",
            cookie = "other=value",
        )
        assertFalse(result.isLive)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val sessionJson = JSONObject()
            .put("data", JSONObject()
                .put("session", JSONObject()
                    .put("nickname", "Shopee主播")
                    .put("status", 0)))
        val spider = ShopeeSpider(FakeClient(sessionJson.toString()))
        val result = spider.getStreamInfo(
            "https://live.shopee.sg/share?session=123&uid=456",
            cookie = "_m_h5_tk=abc_123; _m_h5_tk_enc=def",
        )
        assertFalse(result.isLive)
        assertEquals("Shopee主播", result.anchorName)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val sessionJson = JSONObject()
            .put("data", JSONObject()
                .put("session", JSONObject()
                    .put("nickname", "Shopee主播")
                    .put("status", 1)
                    .put("title", "促销直播")
                    .put("play_url", "https://flv.shopee.sg/live/abc.flv")))
        val spider = ShopeeSpider(FakeClient(sessionJson.toString()))
        val result = spider.getStreamInfo(
            "https://live.shopee.sg/share?session=123&uid=456",
            cookie = "_m_h5_tk=abc_123; _m_h5_tk_enc=def",
        )
        assertTrue(result.isLive)
        assertEquals("Shopee主播", result.anchorName)
        assertEquals("促销直播", result.title)
        assertEquals("https://flv.shopee.sg/live/abc.flv", result.flvUrl)
        assertEquals(result.flvUrl, result.recordUrl)
    }

    private class FakeClient(private val response: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, response, url, emptyMap())
    }
}