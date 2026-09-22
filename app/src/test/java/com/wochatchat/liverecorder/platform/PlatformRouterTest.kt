/*
 * PlatformRouterTest — Phase 3e：平台分发（斗鱼接入 Phase 2 调度）。
 *
 * 沿用 DouyuSpiderTest 模式：DouyuTestClient（open LiveHttpClient 子类）按 URL 前缀分发 fixture。
 * 覆盖：
 *   1. isDouyuUrl 路由判定
 *   2. 斗鱼未开播 → isLive=false 透传，不请求流
 *   3. 斗鱼开播 → FLV 录制源（rtmp_url/rtmp_live）+ 画质 rate 映射
 */
package com.wochatchat.liverecorder.platform

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.sign.RhinoJsEngine
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class PlatformRouterTest {

    private fun resource(name: String): String {
        val url = javaClass.classLoader!!.getResource(name)
            ?: throw IllegalStateException("test resource not found: $name")
        return File(url.toURI()).readText()
    }

    private fun liveBetard(): String = JSONObject(resource("douyu_betard_offline_raw.json")).apply {
        getJSONObject("room").apply {
            put("show_status", 1)
            put("roomName", "测试直播")
        }
    }.toString()

    /** 记录 postForm 收到的 rate 参数，便于断言画质映射。 */
    private class RouterTestClient(
        private val mHtml: String,
        private val betardJson: String,
        private val h5playJson: String,
    ) : LiveHttpClient() {
        val postedRate = AtomicReference<String?>(null)

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult = when {
            url.startsWith("https://m.douyu.com/") -> HttpResult(200, mHtml, url, emptyMap())
            url.startsWith("https://www.douyu.com/betard/") -> HttpResult(200, betardJson, url, emptyMap())
            else -> HttpResult(404, "not found", url, emptyMap())
        }

        override suspend fun postForm(url: String, headers: Map<String, String>,
                                      form: Map<String, String>, timeoutSec: Long): HttpResult {
            postedRate.set(form["rate"])
            return HttpResult(200, h5playJson, url, emptyMap())
        }
    }

    private fun routerWith(client: LiveHttpClient): PlatformRouter = PlatformRouter(
        douyinSpider = com.wochatchat.liverecorder.platform.douyin.DouyinSpider(),
        douyuSpider = com.wochatchat.liverecorder.platform.douyu.DouyuSpider(
            client, jsEngine = { code -> com.wochatchat.liverecorder.sign.RhinoJsEngine.eval(code) },
        ),
    )

    private fun liveH5play(rate: String) = """{"data":{"nickname":"测试主播","rate":"$rate",
        "rtmp_url":"https://flv.douyucdn.cn/live","rtmp_live":"631134abc.flv",
        "hls_url":"https://txy.live.douyucdn.cn/live/631134.m3u8"}}"""
    // ---- 1. 路由判定 ----

    @Test
    fun isDouyuUrl() {
        assertTrue(PlatformRouter.isDouyuUrl("https://www.douyu.com/631134"))
        assertTrue(PlatformRouter.isDouyuUrl("https://m.douyu.com/631134?rid=631134"))
        assertFalse(PlatformRouter.isDouyuUrl("https://live.douyin.com/605556584648"))
    }

    // ---- 2. 斗鱼未开播：透传 isLive=false，不请求流 ----

    @Test
    fun douyuOffline_noStreamRequest() = runTest {
        val client = RouterTestClient(
            mHtml = resource("douyu_m_room_raw.html"),
            betardJson = resource("douyu_betard_offline_raw.json"),
            h5playJson = """{"data":{}}""",
        )
        val info = routerWith(client).fetchStreamInfo("https://www.douyu.com/631134")
        assertFalse(info.isLive)
        assertEquals("上天入地大神通儿", info.anchorName)
        assertNull(client.postedRate.get())  // 未开播不请求流
    }

    // ---- 3. 斗鱼开播 → FLV 录制源 + 画质映射 ----

    @Test
    fun douyuOnline_flvRecordUrl() = runTest {
        val client = RouterTestClient(
            mHtml = resource("douyu_m_room_raw.html"),
            betardJson = liveBetard(),
            h5playJson = liveH5play("0"),
        )
        val info = routerWith(client).fetchStreamInfo("https://www.douyu.com/631134")
        assertTrue(info.isLive)
        assertEquals("测试主播", info.anchorName)
        // 上游 stream.py:303：flv_url = rtmp_url/rtmp_live，同时作 flv_url 与 record_url
        assertEquals("https://flv.douyucdn.cn/live/631134abc.flv", info.flvUrl)
        assertEquals("https://flv.douyucdn.cn/live/631134abc.flv", info.recordUrl)
        assertEquals("蓝光", info.quality)
        assertEquals("0", client.postedRate.get())
    }

    @Test
    fun douyuOnline_rateMapping() = runTest {
        // 上游 video_quality_options：UHD→3、HD→2、SD/LD→1、OD/BD→0、缺失→0
        val options = mapOf(
            "UHD" to "3", "HD" to "2", "SD" to "1", "LD" to "1",
            "OD" to "0", "BD" to "0", null to "0",
        )
        for ((qualityCode, expectedRate) in options) {
            val client = RouterTestClient(
                mHtml = resource("douyu_m_room_raw.html"),
                betardJson = liveBetard(),
                h5playJson = liveH5play("3"),
            )
            routerWith(client).fetchStreamInfo("https://www.douyu.com/631134", qualityCode)
            assertEquals("画质码 $qualityCode → rate", expectedRate, client.postedRate.get())
        }
    }
}
