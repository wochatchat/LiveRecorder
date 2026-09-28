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
        // 上游语义：POST 到 server（去掉 topic 段），topic 只出现在 body 里
        assertEquals("/", recorded.path)
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
    fun pushConfigValidity() {
        assertFalse(PushConfig().isValid)
        assertTrue(PushConfig(enabled = true, type = "ntfy", apis = listOf("https://ntfy.sh/t")).isValid)
    }

    // 7b R38：推送明细模板
    @Test
    fun buildTitle_defaultAndCustom() {
        // 空 → 默认标题（上游 main.py:328 strip() or 默认）
        assertEquals("直播间状态更新通知", pusher.buildTitle(PushConfig()))
        assertEquals("直播间状态更新通知", pusher.buildTitle(PushConfig(title = "   ")))
        // 非空 → trim 后使用
        assertEquals("我的标题", pusher.buildTitle(PushConfig(title = " 我的标题 ")))
    }

    @Test
    fun buildContent_defaultContainsPrefixAndPlaceholders() {
        // 默认文案对齐上游 main.py:1101/1083 字面量 + 占位符替换
        val live = pusher.buildContent(PushConfig(), Event.LIVE, "测试主播", "12:00:00")
        assertEquals("直播间状态更新：测试主播 正在直播中，时间：12:00:00", live)
        val offline = pusher.buildContent(PushConfig(), Event.OFFLINE, "测试主播", "12:00:00")
        assertEquals("直播间状态更新：测试主播 直播已结束！时间：12:00:00", offline)
    }

    @Test
    fun buildContent_customTemplatePlaceholders() {
        val config = PushConfig(
            liveMessage = "[直播间名称] 开播啦\\n[时间]",
            offlineMessage = "[直播间名称] 下播 [时间]",
        )
        assertEquals("小明 开播啦\n08:30:00", pusher.buildContent(config, Event.LIVE, "小明", "08:30:00"))
        assertEquals("小明 下播 08:30:00", pusher.buildContent(config, Event.OFFLINE, "小明", "08:30:00"))
    }

    @Test
    fun buildContent_emptyConfigFallsBackToDefault() {
        // 空 liveMessage/offlineMessage → 默认文案（行为与 7b 前一致）
        val live = pusher.buildContent(PushConfig(), Event.LIVE, "主播A", "09:00:00")
        assertEquals("直播间状态更新：主播A 正在直播中，时间：09:00:00", live)
    }

    @Test
    fun barkBody_customLevelAndSound() {
        val json = JSONObject(
            pusher.barkBody("https://api.day.app/key", "t", "m", level = "timeSensitive", sound = "bell")
        )
        assertEquals("timeSensitive", json.getString("level"))
        assertEquals("bell", json.getString("sound"))
    }

    @Test
    fun barkBody_blankLevelFallsBackToActive() {
        val json = JSONObject(pusher.barkBody("k", "t", "m", level = "", sound = ""))
        assertEquals("active", json.getString("level"))
        assertEquals("", json.getString("sound"))
    }

    @Test
    fun ntfyBody_customTagsAndPriority() {
        // tags 多个，priority 高
        val body = pusher.ntfyBody(
            api = "https://ntfy.sh/mytopic",
            title = "t", message = "m", actionUrl = "",
            tags = listOf("eyes", "bell"),
            priority = 5,
        )
        val json = JSONObject(body)
        assertEquals(JSONArray(listOf("eyes", "bell")).toString(), json.getJSONArray("tags").toString())
        assertEquals(5, json.getInt("priority"))
    }

    @Test
    fun ntfyBody_priorityOutOfRangeFallsBackToDefault() {
        val body = pusher.ntfyBody(
            "https://ntfy.sh/t", "t", "m", actionUrl = "",
            priority = 99,
        )
        val json = JSONObject(body)
        assertEquals(3, json.getInt("priority")) // 默认
    }

    @Test
    fun ntfyBody_priorityZeroFallsBackToDefault() {
        val json = JSONObject(pusher.ntfyBody("https://ntfy.sh/t", "t", "m", actionUrl = "", priority = 0))
        assertEquals(3, json.getInt("priority"))
    }

    @Test
    fun parseTags_withCommaAndChineseComma() {
        assertEquals(listOf("eyes", "bell", "fire"), pusher.parseTags("eyes，bell,fire"))
        assertEquals(listOf("star"), pusher.parseTags("star"))
        assertEquals(listOf("partying_face"), pusher.parseTags(""))
        assertEquals(listOf("partying_face"), pusher.parseTags("  "))
        assertEquals(listOf("partying_face"), pusher.parseTags(" , "))
    }

    @Test
    fun coercePriority_validAndInvalid() {
        assertEquals(1, pusher.coercePriority(1))
        assertEquals(3, pusher.coercePriority(3))
        assertEquals(5, pusher.coercePriority(5))
        assertEquals(3, pusher.coercePriority(0))
        assertEquals(3, pusher.coercePriority(-1))
        assertEquals(3, pusher.coercePriority(99))
    }
}

