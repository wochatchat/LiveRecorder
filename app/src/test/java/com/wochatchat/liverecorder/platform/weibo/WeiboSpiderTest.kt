package com.wochatchat.liverecorder.platform.weibo

import com.wochatchat.liverecorder.net.HttpResult
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 微博单测：showId 解析 + 接口端到端。 */
class WeiboSpiderTest {

    private class FakeClient : LiveHttpClient() {
        var showId: String? = null
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            showId = url.substringAfter("live_id=", "").substringBefore("&")
            return when {
                url.contains("mymblog") -> HttpResult(200, """{"data":{"list":[
                    {"page_info":{"object_type":"live","object_id":"weibo123"}}
                ]}}""", url, emptyMap())
                else -> HttpResult(200, """{"data":{"user_info":{"name":"微博主播"},"item":{"status":1,"desc":"微博直播","stream_info":{"pull":{"live_origin_hls_url":"https://hls/weibo.m3u8","live_origin_flv_url":"https://flv/weibo.flv"}}}}""", url, emptyMap())
            }
        }
    }

    @Test
    fun parseShowId() {
        assertEquals("123456", WeiboSpider.parseShowId("https://weibo.com/show/123456"))
        assertEquals("abc", WeiboSpider.parseShowId("https://weibo.com/show/abc?from=home"))
        assertTrue(WeiboSpider.parseShowId("https://weibo.com/u/123") == null)
    }

    @Test
    fun getStreamInfo_liveViaShow() = runTest {
        val spider = WeiboSpider(FakeClient())
        val info = spider.getStreamInfo("https://weibo.com/show/123456")
        assertTrue(info.isLive)
        assertEquals("微博主播", info.anchorName)
        assertTrue(info.recordUrl.isNotBlank())
    }
}