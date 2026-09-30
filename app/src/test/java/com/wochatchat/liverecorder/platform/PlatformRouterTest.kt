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
import com.wochatchat.liverecorder.platform.yy.YySpider
import com.wochatchat.liverecorder.platform.yy.YySpider.YyStreamInfo
import com.wochatchat.liverecorder.platform.bigo.BigoSpider
import com.wochatchat.liverecorder.platform.bigo.BigoSpider.BigoStreamInfo
import com.wochatchat.liverecorder.platform.xhs.XhsSpider
import com.wochatchat.liverecorder.platform.tiktok.TikTokSpider
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
import com.wochatchat.liverecorder.platform.twitch.TwitchSpider
import com.wochatchat.liverecorder.platform.chzzk.CHZZKSpider
import com.wochatchat.liverecorder.platform.youtube.YouTubeSpider
import com.wochatchat.liverecorder.platform.shopee.ShopeeSpider
import com.wochatchat.liverecorder.platform.acfun.AcfunSpider
import com.wochatchat.liverecorder.platform.huajiao.HuajiaoSpider
import com.wochatchat.liverecorder.platform.inke.InkeSpider
import com.wochatchat.liverecorder.platform.liuxing.LiuxingSpider
import com.wochatchat.liverecorder.platform.yinbo.YinboSpider
import com.wochatchat.liverecorder.platform.soop.SoopliveSpider
import com.wochatchat.liverecorder.platform.pandatv.PandatvSpider
import com.wochatchat.liverecorder.platform.winktv.WinktvSpider
import com.wochatchat.liverecorder.platform.flextv.FlextvSpider
import com.wochatchat.liverecorder.platform.popkontv.PopkontvSpider
import com.wochatchat.liverecorder.platform.maoerfm.MaoerfmSpider
import com.wochatchat.liverecorder.platform.kugou.KugouSpider
import com.wochatchat.liverecorder.platform.changliao.ChangliaoSpider
import com.wochatchat.liverecorder.platform.vvxqiu.VvxqiuSpider

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

    // ---- YY 路由 ----

    @Test
    fun isYyUrl_routing() {
        assertTrue(PlatformRouter.isYyUrl("https://www.yy.com/123456"))
        assertTrue(PlatformRouter.isYyUrl("https://www.yy.com/1355280876?q=test"))
        assertFalse(PlatformRouter.isYyUrl("https://live.bilibili.com/1"))
        assertFalse(PlatformRouter.isYyUrl("https://m.yy.com/123"))
    }

    @Test
    fun fetchYy_live() = runTest {
        val fakeYy = object : YySpider() {
            override suspend fun getYyStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                YyStreamInfo(anchorName = "YY主播", cid = "54880976", title = "YY直播",
                    avpInfoRes = JSONObject().apply {
                        put("stream_line_addr", JSONObject().apply {
                            put("cdn_1", JSONObject().apply {
                                put("cdn_info", JSONObject().apply {
                                    put("url", "https://ks-flv-web.yy.com/live/a.flv")
                                })
                            })
                        })
                    })
        }
        val router = PlatformRouter(yySpider = fakeYy)
        val info = router.fetchStreamInfo("https://www.yy.com/54880976")
        assertTrue(info.isLive)
        assertEquals("YY主播", info.anchorName)
        assertEquals("YY直播", info.title)
        assertEquals("OD", info.quality)
        assertEquals("https://ks-flv-web.yy.com/live/a.flv", info.flvUrl)
    }

    @Test
    fun fetchYy_offline() = runTest {
        val fakeYy = object : YySpider() {
            override suspend fun getYyStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                YyStreamInfo(anchorName = "离线YY", cid = "54880976")
        }
        val router = PlatformRouter(yySpider = fakeYy)
        val info = router.fetchStreamInfo("https://www.yy.com/54880976")
        assertFalse(info.isLive)
        assertEquals("离线YY", info.anchorName)
    }

    // ---- Bigo 路由 ----

    @Test
    fun isBigoUrl_routing() {
        assertTrue(PlatformRouter.isBigoUrl("https://www.bigo.tv/600024469"))
        assertTrue(PlatformRouter.isBigoUrl("https://slink.bigovideo.tv/x/abc?e=1&h=123"))
        assertFalse(PlatformRouter.isBigoUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchBigo_live() = runTest {
        val fakeBigo = object : BigoSpider() {
            override suspend fun getBigoStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                BigoStreamInfo(anchorName = "Bigo主播", title = "Bigo标题", isLive = true,
                    m3u8Url = "https://hls.bigo.tv/a.m3u8", recordUrl = "https://hls.bigo.tv/a.m3u8")
        }
        val router = PlatformRouter(bigoSpider = fakeBigo)
        val info = router.fetchStreamInfo("https://www.bigo.tv/600024469")
        assertTrue(info.isLive)
        assertEquals("Bigo主播", info.anchorName)
        assertEquals("Bigo标题", info.title)
        assertEquals("https://hls.bigo.tv/a.m3u8", info.m3u8Url)
    }

    @Test
    fun fetchBigo_offline() = runTest {
        val fakeBigo = object : BigoSpider() {
            override suspend fun getBigoStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                BigoStreamInfo(anchorName = "离线Bigo", isLive = false)
        }
        val router = PlatformRouter(bigoSpider = fakeBigo)
        val info = router.fetchStreamInfo("https://www.bigo.tv/600024469")
        assertFalse(info.isLive)
        assertEquals("离线Bigo", info.anchorName)
    }

    // 6d R14：isSupported 域名白名单（添加对话框预校验）
    @Test
    fun isSupported_routedPlatforms() {
        val router = PlatformRouter()
        assertTrue(PlatformRouter.isSupported("https://live.douyin.com/123456"))
        assertTrue(PlatformRouter.isSupported("https://v.douyin.com/abc/"))
        assertTrue(PlatformRouter.isSupported("https://www.douyu.com/9999"))
        assertTrue(PlatformRouter.isSupported("https://live.kuaishou.com/u/anchor"))
        assertTrue(PlatformRouter.isSupported("https://www.huya.com/888888"))
        assertTrue(PlatformRouter.isSupported("https://live.bilibili.com/6"))
        assertTrue(PlatformRouter.isSupported("https://www.yy.com/12345678"))
        assertTrue(PlatformRouter.isSupported("https://www.bigo.tv/600024469"))
        assertTrue(PlatformRouter.isSupported("https://www.xiaohongshu.com/user/profile/x"))
        assertFalse(PlatformRouter.isSupported("https://example.com/live/1"))
    }

    @Test
    fun isSupported_onboardingExampleLinks() {
        // 6f R21/R23：引导页与空态使用的示例链接必须是可识别格式，防止平台前缀写错
        com.wochatchat.liverecorder.ui.screens.ONBOARDING_EXAMPLE_LINKS.forEach { (link, _) ->
            assertTrue("示例链接未被识别: $link", PlatformRouter.isSupported(link))
        }
    }

    // 7a R36：自定义流地址直录（上游 main.py:1026-1038）
    @Test
    fun isDirectStreamUrl_trueCases() {
        val m3u8 = "https://example.com/stream/live.m3u8"
        val flv = "https://example.com/stream/live.flv?token=abc"
        val withQuery = "https://cdn.example.com/hls/master.m3u8?key=xyz"
        assertTrue(PlatformRouter.isDirectStreamUrl(m3u8))
        assertTrue(PlatformRouter.isDirectStreamUrl(flv))
        assertTrue(PlatformRouter.isDirectStreamUrl(withQuery))
    }

    @Test
    fun isDirectStreamUrl_falseForKnownPlatforms() {
        // 已知平台域名优先，即使含 .m3u8/.flv 也不走自定义分支（与上游 main.py if/elif 链语义一致）
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.douyu.com/123.flv"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://live.kuaishou.com/u/anchor.m3u8"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.huya.com/888888"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://live.bilibili.com/6"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.yy.com/12345678"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.bigo.tv/600024469"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://live.douyin.com/123456"))
    }

    @Test
    fun isDirectStreamUrl_falseForPlainUrls() {
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.example.com/live/room1"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://example.com/"))
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.xiaohongshu.com/user/profile/xxx"))
    }

    @Test
    fun isSupported_includesDirectStream() {
        val router = PlatformRouter()
        assertTrue(PlatformRouter.isSupported("https://cdn.example.com/stream.m3u8"))
        assertTrue(PlatformRouter.isSupported("https://cdn.example.com/stream.flv"))
    }

    @Test
    fun fetchDirectStream_isLiveTrue_recordUrlIsInput() = runTest {
        val router = PlatformRouter()
        val m3u8Url = "https://cdn.example.com/stream.m3u8"
        val flvUrl = "https://cdn.example.com/stream.flv"

        val m3u8Info = router.fetchStreamInfo(m3u8Url)
        assertTrue(m3u8Info.isLive)
        assertEquals(m3u8Url, m3u8Info.recordUrl)
        assertEquals("", m3u8Info.flvUrl)
        assertEquals(m3u8Url, m3u8Info.m3u8Url)

        val flvInfo = router.fetchStreamInfo(flvUrl)
        assertTrue(flvInfo.isLive)
        assertEquals(flvUrl, flvInfo.recordUrl)
        assertEquals(flvUrl, flvInfo.flvUrl)
        assertEquals("", flvInfo.m3u8Url)
    }

    @Test
    fun fetchDirectStream_anchorNameStableByUrl() = runTest {
        val router = PlatformRouter()
        val url = "https://cdn.example.com/live.m3u8"
        // 同一 URL 多次调用 anchorName 稳定（移动端增强：上游每轮 uuid4[:8] 随机）
        val name1 = router.fetchStreamInfo(url).anchorName
        val name2 = router.fetchStreamInfo(url).anchorName
        assertEquals(name1, name2)
        // 格式："自定义录制直播_" + 8 位十六进制哈希
        assertTrue(name1.startsWith("自定义录制直播_"))
        assertTrue(name1.matches(Regex("^自定义录制直播_[0-9a-f]{8}$")))
    }

    // ---- 7d 新平台路由 ----

    @Test
    fun isNeteaseUrl() {
        assertTrue(PlatformRouter.isNeteaseUrl("https://cc.163.com/123456"))
        assertTrue(PlatformRouter.isNeteaseUrl("https://cc.163.com/"))
        assertFalse(PlatformRouter.isNeteaseUrl("https://douyu.com/123"))
    }

    @Test
    fun isBaiduUrl() {
        assertTrue(PlatformRouter.isBaiduUrl("https://live.baidu.com/weibo?room_id=123"))
        assertFalse(PlatformRouter.isBaiduUrl("https://live.bilibili.com/123"))
    }

    @Test
    fun isWeiboUrl() {
        assertTrue(PlatformRouter.isWeiboUrl("https://weibo.com/show/123456"))
        assertTrue(PlatformRouter.isWeiboUrl("https://weibo.com/u/123456"))
        assertFalse(PlatformRouter.isWeiboUrl("https://twitter.com/123"))
    }

    @Test
    fun isJdUrl() {
        assertTrue(PlatformRouter.isJdUrl("https://lives.jd.com/#/123"))
        assertFalse(PlatformRouter.isJdUrl("https://lives.taobao.com/123"))
    }

    @Test
    fun isZhihuUrl() {
        assertTrue(PlatformRouter.isZhihuUrl("https://www.zhihu.com/live/123456"))
        assertTrue(PlatformRouter.isZhihuUrl("https://www.zhihu.com/people/abc"))
        assertFalse(PlatformRouter.isZhihuUrl("https://www.zhihuihu.com/"))
    }

    @Test
    fun isSupported_newPlatforms() {
        assertTrue(PlatformRouter.isSupported("https://cc.163.com/123"))
        assertTrue(PlatformRouter.isSupported("https://live.baidu.com/weibo?room_id=123"))
        assertTrue(PlatformRouter.isSupported("https://weibo.com/show/123"))
        assertTrue(PlatformRouter.isSupported("https://lives.jd.com/#/123"))
        assertTrue(PlatformRouter.isSupported("https://www.zhihu.com/live/123"))
        assertFalse(PlatformRouter.isSupported("https://unknown.site.com/live/abc"))
    }

    // ---- 8a JS 签名类平台：路由判定 ----

    @Test
    fun isHaixiuUrl() {
        assertTrue(PlatformRouter.isHaixiuUrl("https://www.haixiutv.com/123456"))
        assertTrue(PlatformRouter.isHaixiuUrl("https://www.lehaitv.com/789"))
        assertFalse(PlatformRouter.isHaixiuUrl("https://www.douyu.com/631134"))
    }

    @Test
    fun isLaixiuUrl() {
        assertTrue(PlatformRouter.isLaixiuUrl("https://www.imkktv.com/live?roomId=123"))
        assertFalse(PlatformRouter.isLaixiuUrl("https://www.haixiutv.com/123"))
    }

    @Test
    fun isLiveMeUrl() {
        assertTrue(PlatformRouter.isLiveMeUrl("https://www.liveme.com/live/123/index.html"))
        assertFalse(PlatformRouter.isLiveMeUrl("https://www.livestream.com/123"))
    }

    @Test
    fun isTaobaoUrl() {
        assertTrue(PlatformRouter.isTaobaoUrl("https://tb.cn/x?id=123"))
        assertTrue(PlatformRouter.isTaobaoUrl("https://huodong.m.taobao.com/x?id=1"))
        assertFalse(PlatformRouter.isTaobaoUrl("https://www.taobaocdn.com/x"))
    }

    @Test
    fun isSupported_batch8a() {
        assertTrue(PlatformRouter.isSupported("https://www.haixiutv.com/123"))
        assertTrue(PlatformRouter.isSupported("https://www.lehaitv.com/456"))
        assertTrue(PlatformRouter.isSupported("https://www.imkktv.com/live?roomId=123"))
        assertTrue(PlatformRouter.isSupported("https://www.liveme.com/live/123/index.html"))
        assertTrue(PlatformRouter.isSupported("https://tb.cn/x?id=123"))
        // 自定义直链不被新平台域名抢路由（haixiutv 域名下的 .flv 仍走平台）
        assertTrue(PlatformRouter.isDirectStreamUrl("https://cdn.example.com/live.m3u8"))
    }

    // ---- 8b 小红书：路由判定与分发 ----

    @Test
    fun isXhsUrl() {
        assertTrue(PlatformRouter.isXhsUrl("https://www.xiaohongshu.com/user/profile/555"))
        assertTrue(PlatformRouter.isXhsUrl("https://xhslink.com/xpJpfM"))
        assertFalse(PlatformRouter.isXhsUrl("https://www.douyu.com/631134"))
    }

    @Test
    fun isSupported_batch8b() {
        assertTrue(PlatformRouter.isSupported("https://www.xiaohongshu.com/user/profile/555"))
        assertTrue(PlatformRouter.isSupported("https://xhslink.com/xpJpfM"))
        // xiaohongshu 域名不吃自定义直链路由
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.xiaohongshu.com/live/x.m3u8"))
    }

    @Test
    fun fetchXhs_live() = runTest {
        val fakeXhs = object : XhsSpider() {
            override suspend fun getStreamInfo(
                url: String, proxyAddr: String?, cookie: String?,
            ) = XhsSpider.XhsStreamInfo(
                anchorName = "小红书主播", title = "小红书直播", isLive = true,
                flvUrl = "http://live-source-play.xhscdn.com/live/room123.flv",
                m3u8Url = "http://live-source-play.xhscdn.com/live/room123.m3u8",
                recordUrl = "http://live-source-play.xhscdn.com/live/room123.flv",
            )
        }
        val router = PlatformRouter(xhsSpider = fakeXhs)
        val info = router.fetchStreamInfo("https://www.xiaohongshu.com/user/profile/555")
        assertTrue(info.isLive)
        assertEquals("小红书主播", info.anchorName)
        assertEquals("http://live-source-play.xhscdn.com/live/room123.flv", info.recordUrl)
    }

    @Test
    fun fetchXhs_offline() = runTest {
        val fakeXhs = object : XhsSpider() {
            override suspend fun getStreamInfo(
                url: String, proxyAddr: String?, cookie: String?,
            ) = XhsSpider.XhsStreamInfo(anchorName = "离线小红书")
        }
        val router = PlatformRouter(xhsSpider = fakeXhs)
        val info = router.fetchStreamInfo("https://www.xiaohongshu.com/user/profile/555")
        assertFalse(info.isLive)
        assertEquals("离线小红书", info.anchorName)
    }

    // ---- 8c TikTok：路由判定与分发 ----

    private var seenQuality: String? = null
    private var seenCookie: String? = null

    @Test
    fun isTiktokUrl() {
        assertTrue(PlatformRouter.isTiktokUrl("https://www.tiktok.com/@user/live"))
        assertFalse(PlatformRouter.isTiktokUrl("https://live.douyin.com/123"))
    }

    @Test
    fun isSupported_batch8c() {
        assertTrue(PlatformRouter.isSupported("https://www.tiktok.com/@user/live"))
        // tiktok 域名不吃自定义直链路由
        assertFalse(PlatformRouter.isDirectStreamUrl("https://www.tiktok.com/live/x.m3u8"))
    }

    @Test
    fun fetchTiktok_live() = runTest {
        val fakeTiktok = object : TikTokSpider() {
            override suspend fun getStreamInfo(
                url: String, quality: String?, proxyAddr: String?, cookie: String?,
            ): TikTokSpider.TikTokStreamInfo {
                seenQuality = quality
                seenCookie = cookie
                return TikTokSpider.TikTokStreamInfo(
                    anchorName = "TikTok主播-ttuser", title = "TT标题", isLive = true,
                    m3u8Url = "https://hls.tt.com/live0.m3u8?codec=h264",
                    flvUrl = "https://flv.tt.com/live0.flv?codec=h264",
                    recordUrl = "https://hls.tt.com/live0.m3u8?codec=h264",
                )
            }
        }
        val router = PlatformRouter(tiktokSpider = fakeTiktok)
        val info = router.fetchStreamInfo(
            "https://www.tiktok.com/@user/live", "HD", cookies = mapOf("tiktok" to "sid=abc"),
        )
        assertTrue(info.isLive)
        assertEquals("TikTok主播-ttuser", info.anchorName)
        assertEquals("TT标题", info.title)
        assertEquals("https://hls.tt.com/live0.m3u8?codec=h264", info.recordUrl)
        assertEquals("HD", seenQuality)
        assertEquals("sid=abc", seenCookie)
    }

    @Test
    fun fetchTiktok_offline() = runTest {
        val fakeTiktok = object : TikTokSpider() {
            override suspend fun getStreamInfo(
                url: String, quality: String?, proxyAddr: String?, cookie: String?,
            ) = TikTokSpider.TikTokStreamInfo(anchorName = "离线TikTok")
        }
        val router = PlatformRouter(tiktokSpider = fakeTiktok)
        val info = router.fetchStreamInfo("https://www.tiktok.com/@user/live")
        assertFalse(info.isLive)
        assertEquals("离线TikTok", info.anchorName)
    }

    @Test
    fun isSupported_acfun() {
        assertTrue(PlatformRouter.isAcfunUrl("https://live.acfun.cn/live/12345"))
        assertTrue(PlatformRouter.isAcfunUrl("https://m.acfun.cn/live/12345"))
    }

    @Test
    fun isSupported_unknown_goesToDouyin() {
        assertFalse(PlatformRouter.isSupported("https://unknownplatform.com/room"))
    }

    @Test
    fun fetchTwitch_online() = runTest {
        val fakeTwitch = object : TwitchSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                TwitchSpider.TwitchStreamInfo(
                    anchorName = "Twitch主播-twuser",
                    isLive = true,
                    m3u8Url = "https://usher.ttvnw.net/test.m3u8",
                    recordUrl = "https://usher.ttvnw.net/test.m3u8",
                )
        }
        val router = PlatformRouter(twitchSpider = fakeTwitch)
        val info = router.fetchStreamInfo("https://twitch.tv/streamer")
        assertTrue(info.isLive)
        assertTrue(info.anchorName.contains("Twitch"))
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun fetchCHZZK_online() = runTest {
        val fakeCHZZK = object : CHZZKSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                CHZZKSpider.ChzzkStreamInfo(
                    anchorName = "CHZZK主播",
                    isLive = true,
                    m3u8Url = "https://chzzk.example.com/live.m3u8",
                    recordUrl = "https://chzzk.example.com/live.m3u8",
                )
        }
        val router = PlatformRouter(chzzkSpider = fakeCHZZK)
        val info = router.fetchStreamInfo("https://chzzk.naver.com/live/abc123")
        assertTrue(info.isLive)
        assertEquals("CHZZK主播", info.anchorName)
    }

    @Test
    fun fetchYouTube_online() = runTest {
        val fakeYT = object : YouTubeSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                YouTubeSpider.YouTubeStreamInfo(
                    anchorName = "YT主播",
                    title = "直播标题",
                    isLive = true,
                    m3u8Url = "https://manifest.youtube.com/live.m3u8",
                    recordUrl = "https://manifest.youtube.com/live.m3u8",
                )
        }
        val router = PlatformRouter(youTubeSpider = fakeYT)
        val info = router.fetchStreamInfo("https://youtube.com/watch?v=abc")
        assertTrue(info.isLive)
        assertEquals("直播标题", info.title)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun fetchShopee_cookieRequired() = runTest {
        val fakeShopee = object : ShopeeSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                ShopeeSpider.ShopeeStreamInfo(
                    anchorName = "Shopee主播",
                    isLive = true,
                    flvUrl = "https://flv.shopee/live.flv",
                    recordUrl = "https://flv.shopee/live.flv",
                )
        }
        val router = PlatformRouter(shopeeSpider = fakeShopee)
        val info = router.fetchStreamInfo(
            "https://live.shopee.sg/share?sid=123",
            cookies = mapOf("shopee" to "_m_h5_tk=abc"),
        )
        assertTrue(info.isLive)
        assertEquals("https://flv.shopee/live.flv", info.recordUrl)
    }

    @Test
    fun fetchAcfun_online() = runTest {
        val fakeAcfun = object : AcfunSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                AcfunSpider.AcfunStreamInfo(
                    anchorName = "AcFun主播",
                    title = "AcFun直播",
                    isLive = true,
                    m3u8Url = "https://acfun.example/live.m3u8",
                    recordUrl = "https://acfun.example/live.m3u8",
                )
        }
        val router = PlatformRouter(acfunSpider = fakeAcfun)
        val info = router.fetchStreamInfo("https://live.acfun.cn/live/12345")
        assertTrue(info.isLive)
        assertEquals("AcFun直播", info.title)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    // ── Batch B: 花椒 / 流星 / 映客 / 音播 ──────────────────────────────────────

    @Test
    fun isHuajiaoUrl_routing() {
        assertTrue(PlatformRouter.isHuajiaoUrl("https://www.huajiao.com/l/123456"))
        assertTrue(PlatformRouter.isHuajiaoUrl("https://www.huajiao.com/user/123456"))
        assertFalse(PlatformRouter.isHuajiaoUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchHuajiao_live() = runTest {
        val fake = object : HuajiaoSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                HuajiaoSpider.HuajiaoStreamInfo(
                    anchorName = "花椒主播",
                    title = "花椒直播",
                    isLive = true,
                    flvUrl = "https://stream.huajiao.com/live/123.flv",
                    recordUrl = "https://stream.huajiao.com/live/123.flv",
                )
        }
        val router = PlatformRouter(huajiaoSpider = fake)
        val info = router.fetchStreamInfo("https://www.huajiao.com/l/67890")
        assertTrue(info.isLive)
        assertEquals("花椒主播", info.anchorName)
        assertEquals("花椒直播", info.title)
        assertEquals("https://stream.huajiao.com/live/123.flv", info.recordUrl)
    }

    @Test
    fun fetchHuajiao_offline() = runTest {
        val fake = object : HuajiaoSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                HuajiaoSpider.HuajiaoStreamInfo(anchorName = "花椒离线", isLive = false)
        }
        val router = PlatformRouter(huajiaoSpider = fake)
        val info = router.fetchStreamInfo("https://www.huajiao.com/l/67890")
        assertFalse(info.isLive)
        assertEquals("花椒离线", info.anchorName)
    }

    @Test
    fun isLiuxingUrl_routing() {
        assertTrue(PlatformRouter.isLiuxingUrl("https://www.7u66.com/198189"))
        assertTrue(PlatformRouter.isLiuxingUrl("https://wap.7u66.com/123"))
        assertFalse(PlatformRouter.isLiuxingUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchLiuxing_live() = runTest {
        val fake = object : LiuxingSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                LiuxingSpider.LiuxingStreamInfo(
                    anchorName = "流星主播",
                    isLive = true,
                    m3u8Url = "https://hls.liuxing.com/live.m3u8",
                    flvUrl = "https://flv.liuxing.com/live.flv",
                    recordUrl = "https://flv.liuxing.com/live.flv",
                )
        }
        val router = PlatformRouter(liuxingSpider = fake)
        val info = router.fetchStreamInfo("https://www.7u66.com/198189")
        assertTrue(info.isLive)
        assertEquals("流星主播", info.anchorName)
        assertEquals("https://flv.liuxing.com/live.flv", info.recordUrl)
    }

    @Test
    fun fetchLiuxing_offline() = runTest {
        val fake = object : LiuxingSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                LiuxingSpider.LiuxingStreamInfo(anchorName = "流星离线", isLive = false)
        }
        val router = PlatformRouter(liuxingSpider = fake)
        val info = router.fetchStreamInfo("https://www.7u66.com/198189")
        assertFalse(info.isLive)
    }

    @Test
    fun isInkeUrl_routing() {
        assertTrue(PlatformRouter.isInkeUrl("https://www.inke.cn/?uid=123&id=456"))
        assertTrue(PlatformRouter.isInkeUrl("https://www.inke.cn/live?uid=abc"))
        assertFalse(PlatformRouter.isInkeUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchInke_live() = runTest {
        val fake = object : InkeSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                InkeSpider.InkeStreamInfo(
                    anchorName = "映客主播",
                    isLive = true,
                    m3u8Url = "https://inke.cn/hls/live.m3u8",
                    flvUrl = "https://inke.cn/flv/live.flv",
                    recordUrl = "https://inke.cn/hls/live.m3u8",
                )
        }
        val router = PlatformRouter(inkeSpider = fake)
        val info = router.fetchStreamInfo("https://www.inke.cn/?uid=123&id=456")
        assertTrue(info.isLive)
        assertEquals("映客主播", info.anchorName)
        assertEquals("https://inke.cn/hls/live.m3u8", info.recordUrl)
    }

    @Test
    fun fetchInke_offline() = runTest {
        val fake = object : InkeSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                InkeSpider.InkeStreamInfo(anchorName = "映客离线", isLive = false)
        }
        val router = PlatformRouter(inkeSpider = fake)
        val info = router.fetchStreamInfo("https://www.inke.cn/?uid=123&id=456")
        assertFalse(info.isLive)
    }

    @Test
    fun isYinboUrl_routing() {
        assertTrue(PlatformRouter.isYinboUrl("https://www.ybw1666.com/800005143"))
        assertTrue(PlatformRouter.isYinboUrl("https://wap.ybw1666.com/123"))
        assertFalse(PlatformRouter.isYinboUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchYinbo_live() = runTest {
        val fake = object : YinboSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                YinboSpider.YinboStreamInfo(
                    anchorName = "音播主播",
                    isLive = true,
                    m3u8Url = "https://yinbo.com/hls/live.m3u8",
                    flvUrl = "https://yinbo.com/flv/live.flv",
                    recordUrl = "https://yinbo.com/flv/live.flv",
                )
        }
        val router = PlatformRouter(yinboSpider = fake)
        val info = router.fetchStreamInfo("https://www.ybw1666.com/800005143")
        assertTrue(info.isLive)
        assertEquals("音播主播", info.anchorName)
        assertEquals("https://yinbo.com/flv/live.flv", info.recordUrl)
    }

    @Test
    fun fetchYinbo_offline() = runTest {
        val fake = object : YinboSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                YinboSpider.YinboStreamInfo(anchorName = "音播离线", isLive = false)
        }
        val router = PlatformRouter(yinboSpider = fake)
        val info = router.fetchStreamInfo("https://www.ybw1666.com/800005143")
        assertFalse(info.isLive)
    }

    // ── Batch C: SOOP / PandaTV / WinkTV / FlexTV / PopkonTV ──────────────────

    @Test
    fun isSoopUrl_routing() {
        assertTrue(PlatformRouter.isSoopUrl("https://play.sooplive.co.kr/oul282/249469582"))
        assertTrue(PlatformRouter.isSoopUrl("https://www.sooplive.co.kr/station/20519630/oul282"))
        assertTrue(PlatformRouter.isSoopUrl("https://www.sooplive.com/oul282/1"))
        assertFalse(PlatformRouter.isSoopUrl("https://chzzk.naver.com/1"))
    }

    @Test
    fun fetchSoop_live() = runTest {
        val fake = object : SoopliveSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?,
                                               username: String?, password: String?) =
                SoopliveSpider.SoopStreamInfo(anchorName = "SOOP主播", isLive = true,
                    m3u8Url = "https://cdn.sooplive.co.kr/live/1/master.m3u8?aid=t",
                    recordUrl = "https://cdn.sooplive.co.kr/live/1/master.m3u8?aid=t")
        }
        val router = PlatformRouter(soopliveSpider = fake)
        val info = router.fetchStreamInfo("https://play.sooplive.co.kr/oul282/249469582")
        assertTrue(info.isLive)
        assertEquals("SOOP主播", info.anchorName)
        assertEquals("https://cdn.sooplive.co.kr/live/1/master.m3u8?aid=t", info.recordUrl)
    }

    @Test
    fun fetchSoop_offline() = runTest {
        val fake = object : SoopliveSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?,
                                               username: String?, password: String?) =
                SoopliveSpider.SoopStreamInfo(anchorName = "SOOP离线", isLive = false)
        }
        val router = PlatformRouter(soopliveSpider = fake)
        val info = router.fetchStreamInfo("https://play.sooplive.co.kr/oul282/249469582")
        assertFalse(info.isLive)
        assertEquals("SOOP离线", info.anchorName)
    }

    @Test
    fun isPandatvUrl_routing() {
        assertTrue(PlatformRouter.isPandatvUrl("https://www.pandalive.co.kr/live/panda123"))
        assertFalse(PlatformRouter.isPandatvUrl("https://www.winktv.co.kr/live/x"))
    }

    @Test
    fun fetchPandatv_live() = runTest {
        val fake = object : PandatvSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                PandatvSpider.PandatvStreamInfo(
                    anchorName = "熊猫主播", isLive = true,
                    m3u8Url = "https://panda.m3u8/live.m3u8",
                    recordUrl = "https://panda.m3u8/live.m3u8")
        }
        val router = PlatformRouter(pandatvSpider = fake)
        val info = router.fetchStreamInfo("https://www.pandalive.co.kr/live/panda123")
        assertTrue(info.isLive)
        assertEquals("熊猫主播", info.anchorName)
        assertEquals("https://panda.m3u8/live.m3u8", info.recordUrl)
    }

    @Test
    fun isWinktvUrl_routing() {
        assertTrue(PlatformRouter.isWinktvUrl("https://www.winktv.co.kr/live/wink123"))
        assertFalse(PlatformRouter.isWinktvUrl("https://www.pandalive.co.kr/live/x"))
    }

    @Test
    fun fetchWinktv_live() = runTest {
        val fake = object : WinktvSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                WinktvSpider.WinktvStreamInfo(
                    anchorName = "眨眼主播", isLive = true,
                    m3u8Url = "https://wink.m3u8/master.m3u8",
                    recordUrl = "https://wink.m3u8/master.m3u8")
        }
        val router = PlatformRouter(winktvSpider = fake)
        val info = router.fetchStreamInfo("https://www.winktv.co.kr/live/wink123")
        assertTrue(info.isLive)
        assertEquals("眨眼主播", info.anchorName)
        assertEquals("https://wink.m3u8/master.m3u8", info.recordUrl)
    }

    @Test
    fun isFlextvUrl_routing() {
        assertTrue(PlatformRouter.isFlextvUrl("https://www.flextv.co.kr/channels/123/live"))
        assertTrue(PlatformRouter.isFlextvUrl("https://www.ttinglive.com/channels/123/live"))
        assertFalse(PlatformRouter.isFlextvUrl("https://www.popkontv.com/live"))
    }

    @Test
    fun fetchFlextv_live() = runTest {
        val fake = object : FlextvSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?,
                                               username: String?, password: String?) =
                FlextvSpider.FlextvStreamInfo(
                    anchorName = "Flex主播", isLive = true,
                    m3u8Url = "https://flex.m3u8/stream.m3u8",
                    recordUrl = "https://flex.m3u8/stream.m3u8")
        }
        val router = PlatformRouter(flextvSpider = fake)
        val info = router.fetchStreamInfo("https://www.ttinglive.com/channels/flex123/live")
        assertTrue(info.isLive)
        assertEquals("Flex主播", info.anchorName)
        assertEquals("https://flex.m3u8/stream.m3u8", info.recordUrl)
    }

    @Test
    fun isPopkontvUrl_routing() {
        assertTrue(PlatformRouter.isPopkontvUrl(
            "https://www.popkontv.com/live/view?castId=pk123&partnerCode=P-00001"))
        assertFalse(PlatformRouter.isPopkontvUrl("https://www.winktv.co.kr/live/x"))
    }

    @Test
    fun fetchPopkontv_live() = runTest {
        val fake = object : PopkontvSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?,
                                               accessToken: String?, username: String?, password: String?,
                                               partnerCode: String) =
                PopkontvSpider.PopkontvStreamInfo(
                    anchorName = "泡泡主播", isLive = true,
                    m3u8Url = "https://pk.m3u8/live.m3u8",
                    recordUrl = "https://pk.m3u8/live.m3u8")
        }
        val router = PlatformRouter(popkontvSpider = fake)
        val info = router.fetchStreamInfo(
            "https://www.popkontv.com/live/view?castId=pk123&partnerCode=P-00001")
        assertTrue(info.isLive)
        assertEquals("泡泡主播", info.anchorName)
        assertEquals("https://pk.m3u8/live.m3u8", info.recordUrl)
    }

    @Test
    fun isSupported_batch9c() {
        assertTrue(PlatformRouter.isSupported("https://play.sooplive.co.kr/oul282/249469582"))
        assertTrue(PlatformRouter.isSupported("https://www.sooplive.com/oul282/1"))
        assertTrue(PlatformRouter.isSupported("https://www.pandalive.co.kr/live/panda123"))
        assertTrue(PlatformRouter.isSupported("https://www.winktv.co.kr/live/wink123"))
        assertTrue(PlatformRouter.isSupported("https://www.flextv.co.kr/channels/123/live"))
        assertTrue(PlatformRouter.isSupported("https://www.ttinglive.com/channels/123/live"))
        assertTrue(PlatformRouter.isSupported(
            "https://www.popkontv.com/live/view?castId=pk123&partnerCode=P-00001"))
        // 9c 平台域名不抢自定义直链路由
        assertTrue(PlatformRouter.isDirectStreamUrl("https://cdn.example.com/live.m3u8"))
        assertFalse(PlatformRouter.isSupported("https://unknown.kr/live/1"))
    }

    // ── Batch D: 猫耳FM / 酷狗 / 畅聊 / VV星球 ───────────────────────────────

    @Test
    fun isMaoerfmUrl_routing() {
        assertTrue(PlatformRouter.isMaoerfmUrl("https://fm.missevan.com/live/868895007"))
        assertTrue(PlatformRouter.isMaoerfmUrl("https://www.missevan.com/live/123"))
        assertFalse(PlatformRouter.isMaoerfmUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun isKugouUrl_routing() {
        assertTrue(PlatformRouter.isKugouUrl("https://fanxing2.kugou.com/123456"))
        assertTrue(PlatformRouter.isKugouUrl("https://fanxing.kugou.com/live/123"))
        assertFalse(PlatformRouter.isKugouUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun isChangliaoUrl_routing() {
        assertTrue(PlatformRouter.isChangliaoUrl("https://live.tlclw.com/15777"))
        assertTrue(PlatformRouter.isChangliaoUrl("https://wap.tlclw.com/123"))
        assertFalse(PlatformRouter.isChangliaoUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun isVvxqiuUrl_routing() {
        assertTrue(PlatformRouter.isVvxqiuUrl("https://vvxqiu.com/?roomId=123456"))
        assertTrue(PlatformRouter.isVvxqiuUrl("https://www.vvxqiu.com/live?roomId=789"))
        assertFalse(PlatformRouter.isVvxqiuUrl("https://live.bilibili.com/1"))
    }

    @Test
    fun fetchMaoerfm_live() = runTest {
        val fake = object : MaoerfmSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                MaoerfmSpider.MaoerfmStreamInfo(
                    anchorName = "猫耳主播",
                    title = "猫耳直播",
                    isLive = true,
                    m3u8Url = "https://hls.missevan.com/stream/abc.m3u8",
                    flvUrl = "https://flv.missevan.com/stream/abc.flv",
                    recordUrl = "https://flv.missevan.com/stream/abc.flv",
                )
        }
        val router = PlatformRouter(maoerfmSpider = fake)
        val info = router.fetchStreamInfo("https://fm.missevan.com/live/868895007")
        assertTrue(info.isLive)
        assertEquals("猫耳主播", info.anchorName)
        assertEquals("猫耳直播", info.title)
        assertEquals("https://flv.missevan.com/stream/abc.flv", info.recordUrl)
    }

    @Test
    fun fetchMaoerfm_offline() = runTest {
        val fake = object : MaoerfmSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                MaoerfmSpider.MaoerfmStreamInfo(anchorName = "猫耳离线", isLive = false)
        }
        val router = PlatformRouter(maoerfmSpider = fake)
        val info = router.fetchStreamInfo("https://fm.missevan.com/live/868895007")
        assertFalse(info.isLive)
        assertEquals("猫耳离线", info.anchorName)
    }

    @Test
    fun fetchKugou_live() = runTest {
        val fake = object : KugouSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                KugouSpider.KugouStreamInfo(
                    anchorName = "酷狗主播",
                    isLive = true,
                    flvUrl = "https://flv.kugou.com/live/abc.flv",
                    recordUrl = "https://flv.kugou.com/live/abc.flv",
                )
        }
        val router = PlatformRouter(kugouSpider = fake)
        val info = router.fetchStreamInfo("https://fanxing2.kugou.com/123456")
        assertTrue(info.isLive)
        assertEquals("酷狗主播", info.anchorName)
        assertEquals("https://flv.kugou.com/live/abc.flv", info.recordUrl)
    }

    @Test
    fun fetchKugou_offline() = runTest {
        val fake = object : KugouSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                KugouSpider.KugouStreamInfo(anchorName = "酷狗离线", isLive = false)
        }
        val router = PlatformRouter(kugouSpider = fake)
        val info = router.fetchStreamInfo("https://fanxing2.kugou.com/123456")
        assertFalse(info.isLive)
        assertEquals("酷狗离线", info.anchorName)
    }

    @Test
    fun fetchChangliao_live() = runTest {
        val fake = object : ChangliaoSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                ChangliaoSpider.ChangliaoStreamInfo(
                    anchorName = "畅聊主播",
                    isLive = true,
                    m3u8Url = "https://hls.tlclw.com/live/lid.m3u8",
                    flvUrl = "https://flv.tlclw.com/live/lid.flv",
                    recordUrl = "https://flv.tlclw.com/live/lid.flv",
                )
        }
        val router = PlatformRouter(changliaoSpider = fake)
        val info = router.fetchStreamInfo("https://live.tlclw.com/15777")
        assertTrue(info.isLive)
        assertEquals("畅聊主播", info.anchorName)
        assertEquals("https://flv.tlclw.com/live/lid.flv", info.recordUrl)
    }

    @Test
    fun fetchChangliao_offline() = runTest {
        val fake = object : ChangliaoSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                ChangliaoSpider.ChangliaoStreamInfo(anchorName = "畅聊离线", isLive = false)
        }
        val router = PlatformRouter(changliaoSpider = fake)
        val info = router.fetchStreamInfo("https://live.tlclw.com/15777")
        assertFalse(info.isLive)
        assertEquals("畅聊离线", info.anchorName)
    }

    @Test
    fun fetchVvxqiu_live() = runTest {
        val fake = object : VvxqiuSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                VvxqiuSpider.VvxqiuStreamInfo(
                    anchorName = "VV星球主播",
                    isLive = true,
                    m3u8Url = "https://liveplay-pro.wasaixiu.com/live/test.m3u8",
                    recordUrl = "https://liveplay-pro.wasaixiu.com/live/test.m3u8",
                )
        }
        val router = PlatformRouter(vvxqiuSpider = fake)
        val info = router.fetchStreamInfo("https://vvxqiu.com/?roomId=123456")
        assertTrue(info.isLive)
        assertEquals("VV星球主播", info.anchorName)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun fetchVvxqiu_offline() = runTest {
        val fake = object : VvxqiuSpider() {
            override suspend fun getStreamInfo(url: String, proxyAddr: String?, cookie: String?) =
                VvxqiuSpider.VvxqiuStreamInfo(anchorName = "VV星球离线", isLive = false)
        }
        val router = PlatformRouter(vvxqiuSpider = fake)
        val info = router.fetchStreamInfo("https://vvxqiu.com/?roomId=123456")
        assertFalse(info.isLive)
        assertEquals("VV星球离线", info.anchorName)
    }

    @Test
    fun isSupported_batch9d() {
        assertTrue(PlatformRouter.isSupported("https://fm.missevan.com/live/868895007"))
        assertTrue(PlatformRouter.isSupported("https://fanxing2.kugou.com/123456"))
        assertTrue(PlatformRouter.isSupported("https://fanxing.kugou.com/live/123"))
        assertTrue(PlatformRouter.isSupported("https://live.tlclw.com/15777"))
        assertTrue(PlatformRouter.isSupported("https://vvxqiu.com/?roomId=123456"))
        // 9d 平台域名不抢自定义直链路由
        assertTrue(PlatformRouter.isDirectStreamUrl("https://cdn.example.com/live.m3u8"))
        assertFalse(PlatformRouter.isSupported("https://unknown.cn/live/1"))
    }
}