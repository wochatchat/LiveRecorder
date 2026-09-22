/*
 * DouyuSpiderTest — Phase 3d：斗鱼爬虫单元测试。
 */
package com.wochatchat.liverecorder.platform.douyu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.sign.RhinoJsEngine
import kotlinx.coroutines.test.runTest
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

    // ---- Mock HTTP Client（正确签名：suspend + HttpResult）----

    private inner class MockHttpClient(private val getResponses: Map<String, String>) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            val text = getResponses[url]
                ?: throw IllegalStateException("MockHttpClient: no GET response for $url")
            return HttpResult(code = 200, text = text, finalUrl = url, cookies = emptyMap())
        }
        override suspend fun postForm(url: String, headers: Map<String, String>, form: Map<String, String>, timeoutSec: Long): HttpResult {
            val text = getResponses[url]
                ?: throw IllegalStateException("MockHttpClient: no POST response for $url")
            return HttpResult(code = 200, text = text, finalUrl = url, cookies = emptyMap())
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
        assertEquals(
            "3125893",
            DouyuSpider.parseRidFromUrl("https://m.douyu.com/3125893?rid=3125893&dyshid=0-96003918aa5365bc6dcb4933000316p1")
        )
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
        assertEquals("631134", spider.extractActualRid(mRoomHtml(), "9999"))
    }

    @Test
    fun extractActualRid_fallbackToProvided() {
        val spider = DouyuSpider()
        assertEquals("12345", spider.extractActualRid("<html><body>no info</body></html>", "12345"))
    }

    // ---- 3. parseBetardInfo ----

    @Test
    fun parseBetardInfo_offlineRoom() {
        val spider = DouyuSpider()
        val info = spider.parseBetardInfo(betardJson())
        assertEquals("上天入地大神通儿", info.anchorName)
        assertFalse(info.isLive)  // show_status=2
        assertNull(info.title)    // offline
        assertEquals("631134", info.roomId)
    }

    // ---- 4. parseH5PlayResponse ----

    @Test
    fun parseH5PlayResponse_hlsUrl() {
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("nickname", "测试主播")
                put("hls_url", "https://txy.live.douyucdn.cn/live/room.flv?auth_key=abc")
                put("rate", "3")
            })
        }.toString()
        val info = DouyuSpider().parseH5PlayResponse(json, "631134")
        assertEquals("631134", info.roomId)
        assertEquals("测试主播", info.anchorName)
        assertEquals("https://txy.live.douyucdn.cn/live/room.flv?auth_key=abc", info.streamUrl)
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
        val info = DouyuSpider().parseH5PlayResponse(json, "631134")
        assertNull(info.streamUrl)
    }

    // ---- 5. getDouyuInfo 端到端（Mock HTTP，suspend）----

    @Test
    fun getDouyuInfo_offlineRoom() = runTest {
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

    // ---- 6. token 参数格式（直接测 DouyuSign，与 mock 无关）----

    @Test
    fun tokenParamsStructure_matchesTruth() {
        val truth = JSONObject(resource("douyu_sign_truth.json"))
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
        val arr = truth.getJSONArray("params_list")
        val expected = (0 until arr.length()).map { arr.getString(it) }
        assertArrayEquals(expected.toTypedArray(), params.toTypedArray())
    }

    // ---- 7. DouyuInfo 数据类 ----

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
        assertNull(info.rawJson)
    }
}