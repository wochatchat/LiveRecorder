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
import com.wochatchat.liverecorder.platform.bilibili.BilibiliSpider
import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyu.DouyuSpider
import com.wochatchat.liverecorder.platform.huya.HuyaSpider
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
        huyaSpider = com.wochatchat.liverecorder.platform.huya.HuyaSpider(client),
        bilibiliSpider = com.wochatchat.liverecorder.platform.bilibili.BilibiliSpider(client),
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

// ---- 4. 虎牙路由（HuyaSpider web 路径 + app 路径）----

    // 注意：stream: {...} 段必须单行（解析正则 . 不跨行，与真实页面/上游语义一致）
    private val huyaWebLive = """<script>stream: {"data":[{"gameLiveInfo":{"nick":"虎牙主播","introduction":"虎牙标题"},"gameStreamInfoList":[{"sCdnType":"AL","sFlvUrl":"http://al.flv.huya.com/src","sHlsUrl":"http://al.hls.huya.com/src","sStreamName":"113524-abc-1-10057-A","sFlvAntiCode":"fm=bW9iaWxlXw&ctype=tars_mp&fs=bhct&exsphd=264_4000,264_2000,264_1000,264_800,264_600","sHlsAntiCode":"fm=x","sFlvUrlSuffix":"flv","sHlsUrlSuffix":"m3u8"}]}],"iWebDefaultBitRate":0}</script>"""

    private val huyaAppLive = """{"data":{"profileInfo":{"nick":"App虎牙"},"liveData":{"introduction":"App标题"},
        "realLiveStatus":"ON","stream":{"baseSteamInfoList":[
            {"sCdnType":"TX","sFlvUrl":"tx.flv.huya.com/src","sHlsUrl":"tx.hls.huya.com/src",
             "sStreamName":"113524-tx","sFlvAntiCode":"fm=y&ctype=tars_mp&fs=bhct",
             "sHlsAntiCode":"fm=y","sFlvUrlSuffix":"flv","sHlsUrlSuffix":"m3u8"}
        ]}}}"""

    private class HuyaTestClient(private val webHtml: String, private val appJson: String) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, if (url.contains("mp.huya.com")) appJson else webHtml, url, emptyMap())
    }

    @Test
    fun isHuyaUrl() {
        assertTrue(PlatformRouter.isHuyaUrl("https://www.huya.com/113524"))
        assertTrue(PlatformRouter.isHuyaUrl("https://live.huya.com/116"))
        assertFalse(PlatformRouter.isHuyaUrl("https://www.douyu.com/631134"))
    }

    @Test
    fun huyaOnline_flvRecordUrl() = runTest {
        val router = PlatformRouter(huyaSpider = HuyaSpider(HuyaTestClient(huyaWebLive, huyaAppLive)))
        // HD 走 web 路径 anti-code 重算 + ratio（exsphd[::-1][1] = 2000）
        val info = router.fetchStreamInfo("https://www.huya.com/113524", "HD")
        assertTrue(info.isLive)
        assertEquals("虎牙主播", info.anchorName)
        assertEquals("HD", info.quality)
        assertTrue(info.flvUrl.contains("al.flv.huya.com"))
        assertTrue(info.recordUrl.contains("&ratio="))
        assertTrue(info.recordUrl.contains("ratio=800"))
    }

    @Test
    fun huyaOD_usesAppPath() = runTest {
        val router = PlatformRouter(huyaSpider = HuyaSpider(HuyaTestClient(huyaWebLive, huyaAppLive)))
        val info = router.fetchStreamInfo("https://www.huya.com/113524", "OD")
        assertTrue(info.isLive)
        // OD 走 app 路径：TX 优先 + https 强转 + ctype 替换
        assertTrue(info.recordUrl.startsWith("https://tx.flv.huya.com/"))
        assertTrue(info.recordUrl.contains("huya_webh5"))  // TX ctype 替换
    }
// ---- 5. B 站路由（room_init + playUrl）----

    private class BiliTestClient(
        private val roomInitJson: String,
        private val masterInfoJson: String,
        private val h5InfoJson: String,
        private val playUrlJson: String,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, when {
                url.contains("room_init") -> roomInitJson
                url.contains("Master/info") -> masterInfoJson
                url.contains("getH5InfoByRoom") -> h5InfoJson
                else -> playUrlJson
            }, url, emptyMap())
    }

    @Test
    fun isBilibiliUrl() {
        assertTrue(PlatformRouter.isBilibiliUrl("https://live.bilibili.com/26066074"))
        assertFalse(PlatformRouter.isBilibiliUrl("https://www.bilibili.com/video/BV1xx"))
    }

    @Test
    fun bilibiliOnline_recordUrl() = runTest {
        val client = BiliTestClient(
            roomInitJson = """{"code":0,"data":{"uid":12345678,"live_status":1,"room_id":26066074}}""",
            masterInfoJson = """{"code":0,"data":{"info":{"uname":"B站主播"}}}""",
            h5InfoJson = """{"code":0,"data":{"room_info":{"title":"B站标题"}}}""",
            playUrlJson = """{"code":0,"data":{"durl":[{"order":0,"url":"https://d1--cn-gotcha.bilibili.com/flv/test.flv"}]}}""",
        )
        val router = PlatformRouter(bilibiliSpider = BilibiliSpider(client))
        val info = router.fetchStreamInfo("https://live.bilibili.com/26066074", "OD")
        assertTrue(info.isLive)
        assertEquals("B站主播", info.anchorName)
        assertEquals("B站标题", info.title)
        assertTrue(info.recordUrl.contains("d1--cn-gotcha"))
    }

    @Test
    fun bilibiliOffline_noStream() = runTest {
        val client = BiliTestClient(
            roomInitJson = """{"code":0,"data":{"uid":12345678,"live_status":0,"room_id":26066074}}""",
            masterInfoJson = """{"code":0,"data":{"info":{"uname":"离线主播"}}}""",
            h5InfoJson = """{"code":0,"data":{"room_info":{"title":"离线标题"}}}""",
            playUrlJson = "{}",
        )
        val router = PlatformRouter(bilibiliSpider = BilibiliSpider(client))
        val info = router.fetchStreamInfo("https://live.bilibili.com/26066074", "OD")
        assertFalse(info.isLive)
        assertEquals("离线主播", info.anchorName)
    }
}
