package com.wochatchat.liverecorder.platform.jd

import com.wochatchat.liverecorder.net.HttpResult
import com.wochatchat.liverecorder.net.LiveHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 京东单测：重定向链 + 双接口端到端。 */
class JdSpiderTest {

    private class FakeClient(private val liveStatus: Int) : LiveHttpClient() {
        var lastBody = ""
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long) =
            HttpResult(200, "", "https://lives.jd.com/#/123456?origin=share", emptyMap())
        override suspend fun postForm(
            url: String, headers: Map<String, String>, form: Map<String, String>, timeoutSec: Long,
        ): HttpResult = when {
            url.contains("findTalentMsg") -> HttpResult(200, """{"result":{"talentName":"京东主播","livingRoomJump":{"params":{"id":"live888"}}}}""", url, emptyMap())
            url.contains("jdTalentContentList") -> HttpResult(200, """{"result":{"content":[{"title":"京东直播标题"}]}}""", url, emptyMap())
            else -> HttpResult(200, """{"data":{"status":$liveStatus,"videoUrl":"https://flv/jd.flv","h5VideoUrl":"https://h5/jd.m3u8"}}""", url, emptyMap())
        }
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = JdSpider(FakeClient(liveStatus = 1))
        val info = spider.getStreamInfo("https://lives.jd.com/#/123456?origin=share")
        assertTrue(info.isLive)
        assertTrue(info.recordUrl.isNotBlank())
    }

    @Test
    fun getStreamInfo_offline() = runTest {
        val spider = JdSpider(FakeClient(liveStatus = 0))
        val info = spider.getStreamInfo("https://lives.jd.com/#/123456?origin=share")
        assertFalse(info.isLive)
    }
}