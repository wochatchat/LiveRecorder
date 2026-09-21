package com.wochatchat.liverecorder.push

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** HttpPusher 单测（Phase 2-2f）：请求体构造 + MockWebServer 收包验证。 */
class HttpPusherTest {

    private lateinit var server: MockWebServer
    private lateinit var pusher: HttpPusher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        pusher = HttpPusher()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- 请求体构造 ----

    @Test
    fun ntfyBodyTopicAndFields() {
        val body = pusher.ntfyBody("https://ntfy.sh/mytopic", "标题", "内容", actionUrl = "")
        val json = JSONObject(body)
        assertEquals("mytopic", json.getString("topic"))
        assertEquals("标题", json.getString("title"))
        assertEquals("内容", json.getString("message"))
        assertEquals("partying_face", json.getJSONArray("tags").getString(0))
        assertEquals(3, json.getInt("priority"))
        assertEquals(0, json.getJSONArray("actions").length())
        assertEquals(false, json.getBoolean("markdown"))
    }

    @Test
    fun ntfyBodyWithActionUrl() {
        val json = JSONObject(
            pusher.ntfyBody("https://ntfy.sh/live", "t", "m", actionUrl = "https://live.douyin.com/123")
        )
        val action = json.getJSONArray("actions").getJSONObject(0)
        assertEquals("view", action.getString("action"))
        assertEquals("view live", action.getString("label"))
        assertEquals("https://live.douyin.com/123", action.getString("url"))
    }

    @Test
    fun barkBodyFieldsAlignUpstream() {
        val json = JSONObject(pusher.barkBody("https://api.day.app/key", "标题", "内容"))
        assertEquals("标题", json.getString("title"))
        assertEquals("内容", json.getString("body"))
        assertEquals("active", json.getString("level"))
        assertEquals(1, json.getInt("badge"))
        assertEquals(1, json.getInt("isArchive"))
    }

    // ---- push 端到端（MockWebServer 收包，2f 验收） ----

    @Test
    fun ntfyPushSuccessPostsToTopicPath() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"abc"}"""))
        val api = server.url("/mytopic").toString().removeSuffix("/")
        val failed = pusher.push(
            PushConfig(enabled = true, type = "ntfy", apis = listOf(api)),
            Event.LIVE, "小央视频", "2026-09-21 12:00:00", liveUrl = "https://live.douyin.com/123",
        )
        assertTrue(failed.isEmpty())
        val recorded = server.takeRequest()
        assertEquals("/mytopic", recorded.path)
        assertEquals("POST", recorded.method)
        val sent = JSONObject(recorded.body.readUtf8())
        assertEquals("mytopic", sent.getString("topic"))
        assertEquals("直播间状态更新通知", sent.getString("title"))
        assertEquals("直播间状态更新：小央视频 正在直播中，时间：2026-09-21 12:00:00", sent.getString("message"))
    }

    @Test
    fun ntfyErrorResponseReportsFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":"topic invalid"}"""))
        val api = server.url("/bad").toString().removeSuffix("/")
        val failed = pusher.push(
            PushConfig(enabled = true, type = "ntfy", apis = listOf(api)),
            Event.LIVE, "a", "t", liveUrl = "",
        )
        assertEquals(listOf(api), failed)
    }

    @Test
    fun ntfyNon200IsFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val api = server.url("/t").toString().removeSuffix("/")
        val failed = pusher.push(
            PushConfig(enabled = true, type = "ntfy", apis = listOf(api)),
            Event.LIVE, "a", "t", liveUrl = "",
        )
        assertEquals(listOf(api), failed)
    }

    @Test
    fun barkPushSuccessAndFailure() = runBlocking {
        val api = server.url("/barkkey").toString().removeSuffix("/")
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"success"}"""))
        val ok = pusher.push(
            PushConfig(enabled = true, type = "bark", apis = listOf(api)),
            Event.LIVE, "a", "t", liveUrl = "",
        )
        assertTrue(ok.isEmpty())
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val sent = JSONObject(recorded.body.readUtf8())
        assertTrue(sent.getString("body").contains("正在直播中"))

        server.enqueue(MockResponse().setBody("""{"code":400,"message":"bad"}"""))
        val failed = pusher.push(
            PushConfig(enabled = true, type = "bark", apis = listOf(api)),
            Event.OFFLINE, "a", "t", liveUrl = "",
        )
        assertEquals(listOf(api), failed)
    }

    @Test
    fun multiApisReportOnlyFailedOnes() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"a"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val okApi = server.url("/good").toString().removeSuffix("/")
        val badApi = server.url("/bad").toString().removeSuffix("/")
        val failed = pusher.push(
            PushConfig(enabled = true, type = "ntfy", apis = listOf(okApi, badApi)),
            Event.LIVE, "a", "t", liveUrl = "",
        )
        assertEquals(listOf(badApi), failed)
    }

    @Test
    fun pushConfigValidity() {
        assertFalse(PushConfig().isValid)
        assertTrue(PushConfig(enabled = true, type = "ntfy", apis = listOf("https://ntfy.sh/t")).isValid)
    }
}
