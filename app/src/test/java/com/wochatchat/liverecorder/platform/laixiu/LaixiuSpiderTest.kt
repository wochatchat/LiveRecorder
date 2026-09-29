package com.wochatchat.liverecorder.platform.laixiu

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 来秀单测：roomId 解析 + 签名确定性 + 接口端到端（上游 spider.py:3309）。 */
class LaixiuSpiderTest {

    private class FakeClient : LiveHttpClient() {
        var lastUrl: String = ""
        var seenHeaders: Map<String, String> = emptyMap()
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            lastUrl = url
            seenHeaders = headers
            return HttpResult(200, """{"data":{"nickname":"来秀主播","playStatus":0,
                "playUrl":"https://a/b.flv"}}""", url, emptyMap())
        }
    }

    @Test
    fun parseRoomId() {
        assertEquals("123456", LaixiuSpider.parseRoomId("https://www.imkktv.com/live?roomId=123456"))
        assertEquals("abc", LaixiuSpider.parseRoomId("https://www.imkktv.com/anchor?anchorId=abc&x=1"))
        assertEquals("", LaixiuSpider.parseRoomId("https://www.imkktv.com/"))
    }

    @Test
    fun signDeterministic() {
        // 上游 f"web{s}{a}{u}"：web + imei + 时间戳 + 固定盐
        val input = LaixiuSpider.buildSignInput("abcdef123", 1727500000000L)
        assertEquals("webabcdef1231727500000000" + LaixiuSpider.SIGN_SALT, input)
        val sign = LaixiuSpider().calculateSign(timestamp = 1727500000000, imei = "abcdef123")
        assertEquals(LaixiuSpider.md5Hex(input), sign.requestId)
        assertEquals("abcdef123", sign.imei)
        assertEquals(1727500000000L, sign.timestamp)
    }

    @Test
    fun parseResponse_live() {
        val info = LaixiuSpider()
            .parseResponse("""{"data":{"nickname":"来秀主播","playStatus":0,"playUrl":"https://a/b.flv"}}""")
        assertTrue(info.isLive)
        assertEquals("来秀主播", info.anchorName)
        assertEquals("https://a/b.flv", info.flvUrl)
        assertEquals("https://a/b.flv", info.recordUrl)
    }

    @Test
    fun parseResponse_offline() {
        val info = LaixiuSpider().parseResponse("""{"data":{"nickname":"主播","playStatus":1}}""")
        assertFalse(info.isLive)
        assertEquals("主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val client = FakeClient()
        val spider = LaixiuSpider(client)
        val sign = spider.calculateSign(timestamp = 1727500000000, imei = "abc123")
        val info = spider.getStreamInfo(
            "https://www.imkktv.com/live?roomId=123456",
            signProvider = { sign },
        )
        assertTrue(info.isLive)
        assertEquals("来秀主播", info.anchorName)
        assertEquals("https://a/b.flv", info.flvUrl)
        assertTrue(client.lastUrl.startsWith("https://api.imkktv.com/liveroom/getShareLiveVideo?roomId=123456"))
        // 签名头三件套（上游 headers timestamp/imei/requestId）
        assertEquals("1727500000000", client.seenHeaders["timestamp"])
        assertEquals("abc123", client.seenHeaders["imei"])
        assertEquals(sign.requestId, client.seenHeaders["requestId"])
    }
}
