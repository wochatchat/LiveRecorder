/*
 * DouyuSpiderTest — Phase 3d：斗鱼爬虫单元测试。
 *
 * fixture：
 *   douyu_m_room_raw.html   — 真实 m.douyu.com 房间页（631134，含 crp-stript 签名 + vike_pageContext）
 *   douyu_betard_offline_raw.json — betard 接口响应（631134 离线状态）
 *
 * 验证点：
 *   1. parseRidFromUrl — 三种 URL 格式解析
 *   2. extractActualRid — m 站 HTML vike_pageContext 提取真实 rid
 *   3. parseBetardInfo — betard JSON → DouyuInfo（离线 → isLive=false）
 *   4. parseH5PlayResponse — 流 URL 解析（hls_url 路径）
 *   5. getDouyuInfo — MockHttpClient 端到端（HTML → rid → betard → DouyuInfo）
 *   6. getDouyuStreamData — token 生成参数构造（paramsList size=4，sign 字段 32 位 hex）
 */
package com.wochatchat.liverecorder.platform.douyu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.RhinoJsEngine
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DouyuSpiderTest {

    // ---- 资源加载 ----

    private fun resource(name: String): String {
        val url = javaClass.classLoader!!.getResource(name)
            ?: throw IllegalStateException("test resource not found: $name")
        return File(url.toURI()).readText()
    }

    private fun mRoomHtml() = resource("douyu_m_room_raw.html")
    private fun betardJson() = resource("douyu_betard_offline_raw.json")
    private fun signHtml() = resource("douyu_room_sign_raw.html")

    private val signTruth: JSONObject by lazy {
        JSONObject(resource("douyu_sign_truth.json"))
    }

    // ---- Mock HTTP Client ----

    private inner class MockHttpClient(private val responses: Map<String, String>) : LiveHttpClient() {
        override fun get(url: String, headers: Map<String, String>, timeout: Int): String {
            return responses[url] ?: throw IllegalStateException("MockHttpClient: no response for $url")
        }
        override fun postJson(url: String, formData: Map<String, String>, headers: Map<String, String>, timeout: Int): String {
            return responses[url] ?: throw IllegalStateException("MockHttpClient: no POST response for $url")
        }
    }

    // ---- 1. URL rid 解析 ----

    @Test
    fun parseRidFromUrl_douyuPath() {
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://www.douyu.com/631134"))
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://www.douyu.com/631134?from=abc"))
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://m.douyu.com/631134"))
    }

    @Test
    fun parseRidFromUrl_ridParam() {
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://www.douyu.com/betard/631134"))
        assertEquals("3125893", DouyuSpider.parseRidFromUrl("https://m.douyu.com/3125893?rid=3125893&dyshid=0-96003918aa5365bc6dcb4933000316p1"))
    }

    @Test
    fun parseRidFromUrl_invalid() {
        assertNull(DouyuSpider.parseRidFromUrl("https://www.douyu.com/"))
        assertNull(DouyuSpider.parseRidFromUrl("https://www.douyu.com/a"))
    }

    // ---- 2. extractActualRid ----

    @Test
    fun extractActualRid_fromVikePageContext() {
        val spider = DouyuSpider()
        val rid = spider.extractActualRid(mRoomHtml(), "9999")
        assertEquals("631134", rid)
    }

    @Test
    fun extractActualRid_fallbackToProvided() {
        val spider = DouyuSpider()
        // 不含 vike_pageContext 的 HTML
        assertEquals("12345", spider.extractActualRid("<html><body>no info</body></html>", "12345"))
    }

    // ---- 3. parseBetardInfo ----

    @Test
    fun parseBetardInfo_offlineRoom() {
        val spider = DouyuSpider()
        val info = spider.parseBetardInfo(betardJson())
        assertEquals("上天入地大神通儿", info.anchorName)
        assertFalse(info.isLive)  // show_status=2
        assertNull(info.title)    // offline → title null
        assertEquals("631134", info.roomId)
    }

    // ---- 4. parseH5PlayResponse ----

    @Test
    fun parseH5PlayResponse_hlsUrl() {
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("nickname", "测试主播")
                put("hls_url", "https://txy.live.douyucdn.cn/live/room_631134_2000.flv?auth_key=abc")
                put("rate", "3")
            })
        }.toString()
        val spider = DouyuSpider()
        val info = spider.parseH5PlayResponse(json, "631134")
        assertEquals("631134", info.roomId)
        assertEquals("测试主播", info.anchorName)
        assertEquals("https://txy.live.douyucdn.cn/live/room_631134_2000.flv?auth_key=abc", info.streamUrl)
        assertEquals("超清", info.qualityLabel)
    }

    @Test
    fun parseH5PlayResponse_noStream() {
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("nickname", "离线主播")
                put("rate", "2")
            })
        }.toString()
        val spider = DouyuSpider()
        val info = spider.parseH5PlayResponse(json, "631134")
        assertNull(info.streamUrl)
    }

    // ---- 5. getDouyuInfo 端到端（Mock HTTP）----

    @Test
    fun getDouyuInfo_offlineRoom() {
        val client = MockHttpClient(
            mapOf(
                "https://m.douyu.com/631134" to mRoomHtml(),
                "https://www.douyu.com/betard/631134" to betardJson(),
            )
        )
        val spider = DouyuSpider(client)
        val info = spider.getDouyuInfo("https://www.douyu.com/631134")
        assertEquals("631134", info.roomId)
        assertEquals("上天入地大神通儿", info.anchorName)
        assertFalse(info.isLive)
    }

    // ---- 6. getDouyuStreamData token 参数验证（Rhino 引擎）----

    @Test
    fun getDouyuStreamData_tokenParamsStructure() {
        // fixture m.douyu.com HTML 含签名脚本；固定 t10 验证 params 格式
        val truth = signTruth
        val client = MockHttpClient(mapOf("https://m.douyu.com/631134" to mRoomHtml()))
        val spider = DouyuSpider(client, jsEngine = { code -> RhinoJsEngine.eval(code) })
        // 用 signHtml fixture（最小包装）直接验证 DouyuSign 逻辑
        val params = DouyuSign.getTokenParams(
            eval = { code -> RhinoJsEngine.eval(code) },
            roomHtml = signHtml(),
            rid = truth.getString("rid"),
            did = truth.getString("did"),
            t10 = truth.getString("t10"),
        )
        assertEquals(4, params.size)
        assertEquals(truth.getString("v"), params[0])
        assertEquals(truth.getString("did"), params[1])
        assertEquals(truth.getString("t10"), params[2])
        assertTrue(params[3].matches(Regex("[0-9a-f]{32}")))
        assertEquals(truth.getString("params_list").let {
            // compare JSON array vs List
            (0 until JSONObject("[$it]").length()).map { i -> JSONObject("[$it]").getString(i) }
        }.let { expected -> expected == params })
    }

    // ---- 辅助测试：DouyuInfo / DouyuStreamInfo 数据类 ----

    @Test
    fun douyuInfo_fields() {
        val info = DouyuInfo(anchorName = "主播", isLive = true, title = "直播中", roomId = "123")
        assertEquals("主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("直播中", info.title)
        assertEquals("123", info.roomId)
    }

    @Test
    fun douyuStreamInfo_fields() {
        val info = DouyuStreamInfo(roomId = "631134", anchorName = "主播", streamUrl = "https://x.flv", qualityLabel = "蓝光")
        assertEquals("631134", info.roomId)
        assertEquals("https://x.flv", info.streamUrl)
        assertEquals("蓝光", info.qualityLabel)
        assertNotNull(info.rawJson) // rawJson is null by default
    }
}