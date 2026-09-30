package com.wochatchat.liverecorder.platform.kugou

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 酷狗单测：fanxing2.kugou.com + fx1.service.kugou.com（上游 spider.py:2054）。 */
class KugouSpiderTest {

    companion object {
        // 开播 fixture：normalRoom.nickName 非空，liveType != -1
        private val liveInfoJson = """
        {"errno":0,"data":{"normalRoomInfo":{"nickName":"酷狗主播"},
        "liveType":1}}
    """.trimIndent()

        // 未开播 fixture：liveType = -1
        private val offlineInfoJson = """
        {"errno":0,"data":{"normalRoomInfo":{"nickName":"酷狗离线"},
        "liveType":-1}}
    """.trimIndent()

        // 流地址 fixture：lines[-1].streamProfiles[0].httpsFlv = JSON 数组
        private val streamJson = """
        {"errno":0,"data":{"lines":[
            {"streamProfiles":[
                {"httpsFlv":"[\"https:\\/\\/flv.kugou.com\\/live\\/abc.flv\"]"}
            ]},
            {"streamProfiles":[
                {"httpsFlv":"[\"https:\\/\\/flv2.kugou.com\\/live\\/def.flv\"]"}
            ]}
        ]}}
    """.trimIndent()
    }

    private var reqCount = 0

    private inner class FakeClient(
        private val infoJson: String = liveInfoJson,
        private val streamRespJson: String = streamJson,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            reqCount++
            return if (url.contains("getEnterRoomInfo")) {
                HttpResult(200, infoJson, url, emptyMap())
            } else {
                HttpResult(200, streamRespJson, url, emptyMap())
            }
        }
    }

    @Test
    fun parseRoomId_fromPath() {
        assertEquals("123456", KugouSpider.parseRoomId("https://fanxing2.kugou.com/123456"))
        assertEquals("123456", KugouSpider.parseRoomId("https://fanxing2.kugou.com/123456?from=abc"))
        assertEquals("roomId", KugouSpider.parseRoomId("https://fanxing2.kugou.com/roomId"))
    }

    @Test
    fun parseRoomId_fromQuery() {
        assertEquals("789012", KugouSpider.parseRoomId("https://fanxing2.kugou.com/?roomId=789012"))
        assertEquals("555555",
            KugouSpider.parseRoomId("https://fanxing2.kugou.com/live?roomId=555555&sharefrom=web"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        reqCount = 0
        val spider = KugouSpider(FakeClient())
        val info = spider.getStreamInfo("https://fanxing2.kugou.com/123456")
        assertEquals("酷狗主播", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://flv.kugou.com/live/abc.flv", info.flvUrl)
        assertEquals("https://flv.kugou.com/live/abc.flv", info.recordUrl)
        // 两步 API 各一次
        assertEquals(2, reqCount)
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        reqCount = 0
        val spider = KugouSpider(FakeClient(infoJson = offlineInfoJson))
        val info = spider.getStreamInfo("https://fanxing2.kugou.com/123456")
        assertEquals("酷狗离线", info.anchorName)
        assertFalse(info.isLive)
        // 只走 info API，不请求流地址
        assertEquals(1, reqCount)
    }

    @Test
    fun getStreamInfo_blankNickName() = runTest {
        val spider = KugouSpider(FakeClient(infoJson =
            """{"errno":0,"data":{"normalRoomInfo":{"nickName":""},"liveType":1}}"""
        ))
        val info = spider.getStreamInfo("https://fanxing2.kugou.com/123456")
        assertFalse(info.isLive)
    }
}