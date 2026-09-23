/*
 * KuaishouSpiderTest — Phase 4 第一批：快手爬虫单元测试。
 *
 * LiveHttpClient open 子类按 URL 分发 fixture（同 DouyuSpiderTest 模式）：
 *   - web 路径 fixture 来自真实直播间页（h264 四档 bitrate 1000/2000/4000/8000）
 *   - api2 路径合成 JSON；api2 异常回落 web（上游 data2 语义）
 *   - 画质选择（bitrate 阈值档 / 倒序补齐下标）对齐 stream.py get_kuaishou_stream_url
 */
package com.wochatchat.liverecorder.platform.kuaishou

import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KuaishouSpiderTest {

    private fun resource(name: String): String {
        val url = javaClass.classLoader!!.getResource(name)
            ?: throw IllegalStateException("test resource not found: $name")
        return File(url.toURI()).readText()
    }

    private fun liveHtml() = resource("kuaishou_room_live_raw.html")
    private fun errorHtml() = resource("kuaishou_room_error_raw.html")

    // ---- 1. parseInitialState（web 路径解析）----

    @Test
    fun parseInitialState_liveRoom() {
        val data = KuaishouSpider().parseInitialState(liveHtml())
        assertEquals(2, data.type)
        assertTrue(data.isLive)
        assertEquals("王者荣耀天晴（最强赵云）", data.anchorName)
        assertEquals(4, data.flvUrlList.size)
        assertEquals(listOf(1000, 2000, 4000, 8000), data.flvUrlList.map { it.bitrate })
        assertTrue(data.flvUrlList.first().url.startsWith("https://"))
    }

    @Test
    fun parseInitialState_errorRoom() {
        val data = KuaishouSpider().parseInitialState(errorHtml())
        // errorType → type=2 未开播（上游 errorType 分支）
        assertEquals(2, data.type)
        assertFalse(data.isLive)
    }

    @Test
    fun parseInitialState_noInitialState() {
        // 无 __INITIAL_STATE__（请求失败/页面异常）→ type=1 透传
        val data = KuaishouSpider().parseInitialState("<html>plain page</html>")
        assertEquals(1, data.type)
        assertFalse(data.isLive)
    }

    @Test
    fun getKuaishouInfoByWeb_networkFailReturnsType1() = runTest {
        val spider = KuaishouSpider(KsFailClient())
        val data = spider.getKuaishouInfoByWeb("https://live.kuaishou.com/u/abc")
        assertEquals(1, data.type)
        assertFalse(data.isLive)
    }

    // ---- 2. parseApi2（/u/ 短链 api2 路径）----

    private fun api2Json(living: Boolean = true) = """{"result":1,"liveStream":{
        "user":{"user_name":"测试主播"},
        "living":$living,
        "hlsPlayUrl":"https://hls.example.com/live.m3u8",
        "playUrls":[{"url":"https://flv.example.com/backup.flv"}],
        "multiResolutionHlsPlayUrls":[{"urls":[
            {"url":"https://h.example.com/hd.m3u8","bitrate":1000},
            {"url":"https://h.example.com/sd.m3u8","bitrate":600}
        ]}],
        "multiResolutionPlayUrls":[{"urls":[
            {"url":"https://f.example.com/l0.flv","bitrate":1000},
            {"url":"https://f.example.com/l3.flv","bitrate":4000}
        ]}]
    }}""".trimIndent()

    @Test
    fun parseApi2_living() {
        val data = KuaishouSpider().parseApi2(api2Json(true))
        assertEquals(2, data.type)
        assertTrue(data.isLive)
        assertEquals("测试主播", data.anchorName)
        assertEquals(2, data.m3u8UrlList.size)
        assertEquals(2, data.flvUrlList.size)
        assertEquals(listOf(1000, 4000), data.flvUrlList.map { it.bitrate })
    }

    @Test
    fun parseApi2_notLiving() {
        val data = KuaishouSpider().parseApi2(api2Json(false))
        assertEquals(2, data.type)
        assertFalse(data.isLive)
        assertEquals("测试主播", data.anchorName)
    }

    @Test
    fun getKuaishouInfo_api2FallsBackToWebOnError() = runTest {
        // api2 抛异常（failApi2）→ 回落 web 路径（上游 get_kuaishou_stream_data2 尾行语义）
        val spider = KuaishouSpider(KsTestClient(webHtml = liveHtml(), failApi2 = true))
        val data = spider.getKuaishouInfo("https://live.kuaishou.com/u/abc")
        assertTrue(data.isLive)
        assertEquals("王者荣耀天晴（最强赵云）", data.anchorName)
    }

    // ---- 3. 画质选择（stream.py get_kuaishou_stream_url）----

    @Test
    fun selectStream_bitrateThresholds() {
        val spider = KuaishouSpider()
        val info = KuaishouSpider.KsStreamData(
            type = 2, isLive = true, anchorName = "a",
            flvUrlList = listOf(
                KuaishouSpider.KsStreamUrl("u1000", 1000),
                KuaishouSpider.KsStreamUrl("u8000", 8000),
                KuaishouSpider.KsStreamUrl("u2000", 2000),
                KuaishouSpider.KsStreamUrl("u4000", 4000),
            ),
        )
        // OD → 阈值 99999 → 降序首档命中（8000）
        assertEquals("u8000", spider.selectStream(info, "OD")!!.flvUrl)
        // BD → 4000 → 第一个 ≤4000（u4000）
        assertEquals("u4000", spider.selectStream(info, "BD")!!.flvUrl)
        // UHD → 2000；HD → 1000
        assertEquals("u2000", spider.selectStream(info, "UHD")!!.flvUrl)
        assertEquals("u1000", spider.selectStream(info, "HD")!!.flvUrl)
        // SD/LD 阈值(800/600)低于全部真实档位 → 取最高档（上游 quality_index=None → len-1）
        assertEquals("u8000", spider.selectStream(info, "SD")!!.flvUrl)
        assertEquals("u8000", spider.selectStream(info, "LD")!!.flvUrl)
        // 画质名回填
        assertEquals("HD", spider.selectStream(info, "HD")!!.quality)
    }

    @Test
    fun selectStream_notLiveReturnsNull() {
        val spider = KuaishouSpider()
        val offline = KuaishouSpider.KsStreamData(type = 2, isLive = false, anchorName = "x")
        assertNull(spider.selectStream(offline, "OD"))
        val type1 = KuaishouSpider.KsStreamData(type = 1, isLive = false)
        assertNull(spider.selectStream(type1, "OD"))
    }

    @Test
    fun selectStream_indexBasedReversedPadded() {
        val spider = KuaishouSpider()
        val list = listOf(
            KuaishouSpider.KsStreamUrl("https://f.example.com/l0.flv"),
            KuaishouSpider.KsStreamUrl("https://f.example.com/l3.flv"),
            KuaishouSpider.KsStreamUrl("https://f.example.com/l1.flv"),
        )
        // 反转 [l0, l3, l1] → [l1, l3, l0]，不足 5 档用末位补齐 → [l1, l3, l0, l0, l0]
        assertEquals("https://f.example.com/l1.flv", spider.pickReversed(list, 0).url)
        assertEquals("https://f.example.com/l3.flv", spider.pickReversed(list, 1).url)
        assertEquals("https://f.example.com/l0.flv", spider.pickReversed(list, 2).url)
        // 下标越界（不足 5 档，越界下标）→ 末档兜底
        val two = listOf(
            KuaishouSpider.KsStreamUrl("https://f.example.com/a.flv"),
            KuaishouSpider.KsStreamUrl("https://f.example.com/b.flv"),
        )
        assertEquals("https://f.example.com/b.flv", spider.pickReversed(two, 3).url)
    }
}

/** 全部请求抛异常的假客户端（模拟网络故障）。 */
private class KsFailClient : LiveHttpClient() {
    override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
        throw java.io.IOException("network down")
}

/** 按 URL 前缀分发 fixture 的假客户端。 */
private class KsTestClient(
    private val webHtml: String,
    private val failApi2: Boolean = false,
) : LiveHttpClient() {
    override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
        HttpResult(200, webHtml, url, emptyMap())

    override suspend fun post(url: String, headers: Map<String, String>, body: okhttp3.RequestBody, timeoutSec: Long): HttpResult =
        if (failApi2) throw java.io.IOException("api2 blocked")
        else HttpResult(200, "{}", url, emptyMap())
}
