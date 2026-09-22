/*
 * DouyuSpiderTest — Phase 3d：斗鱼爬虫单元测试。
 *
 * LiveHttpClient 现为 open class，测试子类 DouyuTestClient 按 URL 前缀返回 fixture。
 * 覆盖：
 *   1. parseRidFromUrl（静态，不走 HTTP）
 *   2. extractActualRid — vike_pageContext 提取真实 rid
 *   3. parseBetardInfo — betard JSON → DouyuInfo（离线/在线）
 *   4. parseH5PlayResponse — 流 URL / 画质标签
 *   5. getDouyuInfo / getDouyuStreamData — fixture 端到端（suspend）
 *   6. 数据类字段
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

    private fun resource(name: String): String {
        val url = javaClass.classLoader!!.getResource(name)
            ?: throw IllegalStateException("test resource not found: $name")
        return File(url.toURI()).readText()
    }

    private fun mRoomHtml() = resource("douyu_m_room_raw.html")
    private fun betardJson() = resource("douyu_betard_offline_raw.json")
    private fun signHtml() = resource("douyu_room_sign_raw.html")

    // ---- 1. URL rid 解析 ----

    @Test
    fun parseRidFromUrl_douyuPath() {
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://www.douyu.com/631134"))
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://www.douyu.com/631134?from=abc"))
        assertEquals("631134", DouyuSpider.parseRidFromUrl("https://m.douyu.com/631134"))
    }

    @Test
    fun parseRidFromUrl_ridParam() {
        // rid= 参数优先（上游 match_rid 语义）
        assertEquals(
            "3125893",
            DouyuSpider.parseRidFromUrl("https://m.douyu.com/3125893?rid=3125893&dyshid=0-96003918aa5365bc6dcb4933000316p1")
        )
        // 路径段只认纯数字（betard/xxx 等 API 路径不是房间链接）
        assertNull(DouyuSpider.parseRidFromUrl("https://www.douyu.com/betard/631134"))
    }

    @Test
    fun parseRidFromUrl_invalid() {
        assertNull(DouyuSpider.parseRidFromUrl("https://www.douyu.com/"))
        assertNull(DouyuSpider.parseRidFromUrl("https://www.douyu.com/a"))
    }

    // ---- 2. extractActualRid ----

    @Test
    fun extractActualRid_fromVikePageContext() {
        assertEquals("631134", DouyuSpider().extractActualRid(mRoomHtml(), "9999"))
    }

    @Test
    fun extractActualRid_fallbackToProvided() {
        assertEquals("12345", DouyuSpider().extractActualRid("<html><body>no info</body></html>", "12345"))
    }

    // ---- 3. parseBetardInfo ----

    @Test
    fun parseBetardInfo_offlineRoom() {
        val info = DouyuSpider().parseBetardInfo(betardJson())
        assertEquals("上天入地大神通儿", info.anchorName)
        assertFalse(info.isLive)  // show_status=2
        assertNull(info.title)    // offline
        assertEquals("631134", info.roomId)
    }

    @Test
    fun parseBetardInfo_liveRoom() {
        val liveJson = JSONObject(betardJson()).apply {
            getJSONObject("room").apply {
                put("show_status", 1)
                put("roomName", "测试直播")
            }
        }.toString()
        val info = DouyuSpider().parseBetardInfo(liveJson)
        assertTrue(info.isLive)
        assertEquals("测试直播", info.title)
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
        assertNotNull(info.rawJson)
    }

    @Test
    fun parseH5PlayResponse_flvUrlFromRtmp() {
        // 上游 stream.py:303：flv_url = rtmp_url/rtmp_live
        val json = JSONObject().apply {
            put("data", JSONObject().apply {
                put("nickname", "测试主播")
                put("rtmp_url", "https://flv.douyucdn.cn/live")
                put("rtmp_live", "631134abc.flv")
                put("hls_url", "https://txy/live.m3u8")
            })
        }.toString()
        val info = DouyuSpider().parseH5PlayResponse(json, "631134")
        assertEquals("https://txy/live.m3u8", info.streamUrl)
        assertEquals("https://flv.douyucdn.cn/live/631134abc.flv", info.flvUrl)
    }

    @Test
    fun parseH5PlayResponse_noStream() {
        val json = """{"data":{"nickname":"离线"}}"""
        assertNull(DouyuSpider().parseH5PlayResponse(json, "631134").streamUrl)
    }

    @Test
    fun parseH5PlayResponse_rateLabels() {
        val map = mapOf("0" to "蓝光", "3" to "超清", "2" to "高清", "1" to "标清", "9" to "9")
        for ((input, expected) in map) {
            val json = """{"data":{"nickname":"x","rate":"$input"}}"""
            assertEquals(expected, DouyuSpider().parseH5PlayResponse(json, "1").qualityLabel)
        }
    }

    // ---- 5. getDouyuInfo 端到端（测试子类 client，suspend）----

    @Test
    fun getDouyuInfo_offlineRoom() = runTest {
        val client = DouyuTestClient(mHtml = mRoomHtml(), betardJson = betardJson())
        val info = DouyuSpider(client).getDouyuInfo("https://www.douyu.com/631134")
        assertEquals("631134", info.roomId)
        assertEquals("上天入地大神通儿", info.anchorName)
        assertFalse(info.isLive)
    }

    // ---- 5b. getDouyuStreamData 端到端（fixture HTML 签名 → postForm）----

    @Test
    fun getDouyuStreamData_tokenFlow() = runTest {
        val truth = JSONObject(resource("douyu_sign_truth.json"))
        val client = DouyuTestClient(
            mHtml = mRoomHtml(),
            betardJson = betardJson(),
            h5playJson = """{"data":{"nickname":"测试主播","hls_url":"https://x/live.flv","rate":"0"}}""",
        )
        val spider = DouyuSpider(client, jsEngine = { code -> RhinoJsEngine.eval(code) })
        val info = spider.getDouyuStreamData("631134", t10 = truth.getString("t10"))
        assertEquals("631134", info.roomId)
        assertEquals("测试主播", info.anchorName)
        assertEquals("https://x/live.flv", info.streamUrl)
        assertEquals("蓝光", info.qualityLabel)
    }

    // ---- 6. 数据类 ----

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
        val info = DouyuStreamInfo(roomId = "631134", anchorName = "主播",
            streamUrl = "https://x.flv", qualityLabel = "蓝光")
        assertEquals("631134", info.roomId)
        assertEquals("https://x.flv", info.streamUrl)
        assertEquals("蓝光", info.qualityLabel)
        assertNull(info.rawJson)
    }
}

// ---- 测试专用 HTTP Client（open LiveHttpClient 子类，按 URL 前缀分发 fixture）----

private class DouyuTestClient(
    private val mHtml: String,
    private val betardJson: String,
    private val h5playJson: String = """{"data":{"nickname":"测试主播"}}""",
) : LiveHttpClient() {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult = when {
        url.startsWith("https://m.douyu.com/") -> HttpResult(200, mHtml, url, emptyMap())
        url.startsWith("https://www.douyu.com/betard/") -> HttpResult(200, betardJson, url, emptyMap())
        else -> HttpResult(404, "not found", url, emptyMap())
    }

    override suspend fun postForm(url: String, headers: Map<String, String>,
                                  form: Map<String, String>, timeoutSec: Long): HttpResult =
        HttpResult(200, h5playJson, url, emptyMap())
}