package com.wochatchat.liverecorder.platform.acfun

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AcfunSpider 单测：URL 解析 + videoPlayRes 解析 + 端到端分发。 */
class AcfunSpiderTest {

    @Test
    fun parseAuthorId_normal() {
        assertEquals("12345", AcfunSpider.parseAuthorId("https://live.acfun.cn/live/12345"))
        assertEquals("67890", AcfunSpider.parseAuthorId("https://m.acfun.cn/live/67890?from=app"))
    }

    @Test
    fun parseAuthorId_edge() {
        assertEquals("", AcfunSpider.parseAuthorId("https://live.acfun.cn/live/"))
    }

    @Test
    fun generateDid_format() {
        val did = AcfunSpider.generateDid()
        assertTrue(did.startsWith("web_"))
        assertEquals("web_".length + 16, did.length)
        // 随机性：两次生成不同
        val did2 = AcfunSpider.generateDid()
        assertTrue(did != did2)
    }

    @Test
    fun parsePlayUrlList_valid() {
        val spider = AcfunSpider()
        val vpr = JSONObject()
            .put("liveAdaptiveManifest", org.json.JSONArray().put(
                JSONObject().put("adaptationSet", JSONObject()
                    .put("representation", org.json.JSONArray()
                        .put(JSONObject().put("bitrate", 4000).put("url", "https://flv/high.flv"))
                        .put(JSONObject().put("bitrate", 1000).put("url", "https://flv/low.flv"))))))
        assertEquals("https://flv/high.flv", spider.parsePlayUrlList(vpr.toString()))
    }

    @Test
    fun parsePlayUrlList_empty() {
        val spider = AcfunSpider()
        assertNull(spider.parsePlayUrlList(""))
        assertNull(spider.parsePlayUrlList("{invalid}"))
        assertNull(spider.parsePlayUrlList(JSONObject().toString()))
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        // profile 无 liveId → 未开播
        val userJson = JSONObject()
            .put("profile", JSONObject().put("name", "AcFun主播"))
        val spider = AcfunSpider(FakeClient(listOf(userJson.toString())))
        val result = spider.getStreamInfo("https://live.acfun.cn/live/12345")
        assertFalse(result.isLive)
        assertEquals("AcFun主播", result.anchorName)
    }

    @Test
    fun getStreamInfo_online() = runTest {
        val vpr = JSONObject()
            .put("liveAdaptiveManifest", org.json.JSONArray().put(
                JSONObject().put("adaptationSet", JSONObject()
                    .put("representation", org.json.JSONArray()
                        .put(JSONObject().put("bitrate", 4000).put("url", "https://flv/high.m3u8"))))))
        val userJson = JSONObject()
            .put("profile", JSONObject().put("name", "AcFun主播").put("liveId", 12345))
        val visitorJson = JSONObject()
            .put("userId", "99999999")
            .put("did", "web_test123456789012")
            .put("acfun.api.visitor_st", "visitor_token")
        val startPlayJson = JSONObject()
            .put("data", JSONObject()
                .put("caption", "AcFun 直播标题")
                .put("videoPlayRes", vpr.toString()))
        val spider = AcfunSpider(FakeClient(listOf(
            userJson.toString(), visitorJson.toString(), startPlayJson.toString(),
        )))
        val result = spider.getStreamInfo("https://live.acfun.cn/live/12345")
        assertTrue(result.isLive)
        assertEquals("AcFun主播", result.anchorName)
        assertEquals("AcFun 直播标题", result.title)
        assertEquals("https://flv/high.m3u8", result.recordUrl)
    }

    private class FakeClient(private val responses: List<String>) : LiveHttpClient() {
        private var idx = 0
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            val text = responses.getOrNull(idx) ?: ""
            idx++
            return HttpResult(200, text, url, emptyMap())
        }
        override suspend fun postForm(
            url: String,
            headers: Map<String, String>,
            form: Map<String, String>,
            timeoutSec: Long,
        ): HttpResult {
            val text = responses.getOrNull(idx) ?: ""
            idx++
            return HttpResult(200, text, url, emptyMap())
        }
    }
}