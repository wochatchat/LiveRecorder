package com.wochatchat.liverecorder.platform.popkontv

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PopkonTV 单测：search/all + live/view + castwatch（上游 spider.py:1675-1837）。 */
class PopkontvSpiderTest {

    companion object {
        private const val CAST_ID = "pk123"
        private const val LIVE_URL =
            "https://www.popkontv.com/live/view?castId=$CAST_ID&partnerCode=P-00001"

        private val searchJson = """
        {"data":{"broadCastList":[
            {"mcSignId":"pk123","nickName":"泡泡主播","mcPartnerCode":"P-00001"},
            {"mcSignId":"other","nickName":"别的","mcPartnerCode":"P-00002"}]}}
    """.trimIndent()

        private val roomNextData = """
        {"props":{"pageProps":{"mcData":{"data":{
            "mc_isPrivate":0,"mc_castStartDate":"20260930",
            "mc_signId":"pk123","castType":"live"}}}}}
    """.trimIndent()

        private val ROOM_HTML = """<html><head><title>popkon</title></head>
        <script id="__NEXT_DATA__" type="application/json">$roomNextData</script></body></html>"""

        private val watchOkJson = """
        {"statusCd":"L0000","statusMsg":"ok","data":{"castHlsUrl":"https://pk.m3u8/pk123/live.m3u8"}}
    """.trimIndent()

        private val watchDateShiftJson = """
        {"statusCd":"L0001","statusMsg":"date shift"}
    """.trimIndent()
    }

    private class FakeClient(
        private val searchResp: String = searchJson,
        private val roomHtml: String = ROOM_HTML,
        private val watchResp: String = watchOkJson,
    ) : LiveHttpClient() {
        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult =
            HttpResult(200, roomHtml, url, emptyMap())

        override suspend fun post(url: String, headers: Map<String, String>,
                                  body: okhttp3.RequestBody, timeoutSec: Long): HttpResult = when {
            url.contains("search/all") -> HttpResult(200, searchResp, url, emptyMap())
            url.contains("castwatchonoffguest") -> HttpResult(200, watchResp, url, emptyMap())
            else -> HttpResult(404, "not found", url, emptyMap())
        }
    }

    @Test
    fun parseAnchorId_castIdAndMcid() {
        assertEquals("pk123", PopkontvSpider.parseAnchorId(LIVE_URL))
        assertEquals("pk123", PopkontvSpider.parseAnchorId(
            "https://www.popkontv.com/channel/notices?mcid=pk123&mcPartnerCode=P-00001"))
        assertEquals(null, PopkontvSpider.parseAnchorId("https://www.popkontv.com/live"))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val spider = PopkontvSpider(FakeClient())
        val info = spider.getStreamInfo(LIVE_URL)
        assertEquals("泡泡主播-pk123", info.anchorName)
        assertTrue(info.isLive)
        assertEquals("https://pk.m3u8/pk123/live.m3u8", info.m3u8Url)
        assertEquals(info.m3u8Url, info.recordUrl)
    }

    @Test
    fun getStreamInfo_L0001_dateShiftRetry() = runTest {
        val spider = PopkontvSpider(FakeClient(watchResp = watchDateShiftJson))
        val info = spider.getStreamInfo(LIVE_URL)
        // L0001 → castStartDate-1 重试，fixture 的 watch 始终回 L0001 JSON，
        // 第二次重试 data.castHlsUrl 缺失 → 防御性返回未开播（上游会炸出异常）
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_roomNotFound() = runTest {
        val spider = PopkontvSpider(FakeClient(roomHtml = "<html><head></head></html>"))
        val info = spider.getStreamInfo(LIVE_URL)
        assertFalse(info.isLive)
    }

    @Test
    fun getStreamInfo_privateRoomNoPassword() = runTest {
        val privateRoomHtml = ROOM_HTML.replace("\"mc_isPrivate\":0", "\"mc_isPrivate\":1")
        val spider = PopkontvSpider(FakeClient(roomHtml = privateRoomHtml))
        val info = spider.getStreamInfo(LIVE_URL)  // URL 无 pwd 参数
        assertFalse(info.isLive)
    }
}