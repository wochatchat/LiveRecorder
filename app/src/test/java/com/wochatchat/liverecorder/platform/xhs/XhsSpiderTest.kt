package com.wochatchat.liverecorder.platform.xhs

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小红书单测：参数解析 + __INITIAL_STATE__ 解析 + 短链重定向 + 端到端（上游 spider.py:769）。 */
class XhsSpiderTest {

    private val liveState =
        """<script>window.__INITIAL_STATE__={"liveStream":{"liveStatus":"success",
            "roomData":{"roomInfo":{"roomTitle":"小红书直播",
            "deeplink":"xhsdiscover://liveentry?flvUrl=https%3A%2F%2Fcdn.xhscdn.com%2Flive%2Froom123.flv%3FtxSecret%3Dabc&host_nickname=%E5%B0%8F%E7%BA%A2%E4%B9%A6%E4%B8%BB%E6%92%AD"}}}}</script>"""

    private val replayState =
        """<script>window.__INITIAL_STATE__={"liveStream":{"liveStatus":"success",
            "roomData":{"roomInfo":{"roomTitle":"直播回放",
            "deeplink":"xhsdiscover://liveentry?flvUrl=https%3A%2F%2Fcdn.xhscdn.com%2Flive%2Froom123.flv&host_nickname=%E4%B8%BB%E6%92%AD"}}}}</script>"""

    private class FakeClient(
        private val roomHtml: String,
        private val profileHtml: String = "<title>@个人页主播 的个人主页</title>",
        private val redirectUrl: String? = null,
    ) : LiveHttpClient() {
        var seenProfileUrl: String = ""

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult = when {
            url.contains("xhslink.com") && redirectUrl != null ->
                HttpResult(200, "", redirectUrl, emptyMap())
            url.contains("/user/profile/") -> {
                seenProfileUrl = url
                HttpResult(200, profileHtml, url, emptyMap())
            }
            else -> HttpResult(200, roomHtml, url, emptyMap())
        }
    }

    @Test
    fun queryParam_decodesValue() {
        assertEquals(
            "主播",
            XhsSpider.queryParam("https://x.cn/live?host_nickname=%E4%B8%BB%E6%92%AD", "host_nickname"),
        )
        assertEquals("b", XhsSpider.queryParam("https://x.cn/?a=b&a=abc", "a"))
        assertNull(XhsSpider.queryParam("https://x.cn/live", "host_id"))
        assertNull(XhsSpider.queryParam("https://x.cn/?other=1", "host_id"))
    }

    @Test
    fun userIdFromPath() {
        assertEquals("555", XhsSpider.userIdFromPath("https://www.xiaohongshu.com/user/profile/555"))
        assertEquals("555", XhsSpider.userIdFromPath("https://www.xiaohongshu.com/user/profile/555/discover"))
        assertEquals("666", XhsSpider.userIdFromPath("https://www.xiaohongshu.com/user/profile/666?q=1"))
        assertNull(XhsSpider.userIdFromPath("https://xhslink.com/xpJpfM"))
    }

    @Test
    fun extractInitialState_matches() {
        assertEquals(
            """{"a":1}""",
            XhsSpider.extractInitialState("""<script>window.__INITIAL_STATE__={"a":1}</script>"""),
        )
        assertNull(XhsSpider.extractInitialState("<html>no state</html>"))
    }

    @Test
    fun parseLive_fixedCdnDirectLink() {
        val json = org.json.JSONObject(
            """{"liveStatus":"success","roomData":{"roomInfo":{"roomTitle":"小红书直播",
                "deeplink":"xhsdiscover://liveentry?flvUrl=https%3A%2F%2Fcdn.xhscdn.com%2Flive%2Froom123.flv&host_nickname=%E4%B8%BB%E6%92%AD"}}}""",
        )
        val info = XhsSpider().parseLive(json)!!
        assertTrue(info.isLive)
        assertEquals("主播", info.anchorName)
        assertEquals("小红书直播", info.title)
        // 上游：固定 CDN 直链（房间号取 flvUrl 'live/' 段至首个 '.'）
        assertEquals("http://live-source-play.xhscdn.com/live/room123.flv", info.flvUrl)
        assertEquals("http://live-source-play.xhscdn.com/live/room123.m3u8", info.m3u8Url)
        assertEquals("http://live-source-play.xhscdn.com/live/room123.flv", info.recordUrl)
    }

    @Test
    fun parseLive_replayTitleReturnsNull() {
        val json = org.json.JSONObject(
            """{"liveStatus":"success","roomData":{"roomInfo":{"roomTitle":"直播回放",
                "deeplink":"xhsdiscover://liveentry?flvUrl=https%3A%2F%2Fcdn.xhscdn.com%2Flive%2Froom123.flv"}}}""",
        )
        assertNull(XhsSpider().parseLive(json))
    }

    @Test
    fun getStreamInfo_live() = runTest {
        val client = FakeClient(liveState)
        val info = XhsSpider(client).getStreamInfo("https://www.xiaohongshu.com/user/profile/555")
        assertTrue(info.isLive)
        assertEquals("小红书主播", info.anchorName)
        assertEquals("小红书直播", info.title)
        assertEquals("http://live-source-play.xhscdn.com/live/room123.flv", info.recordUrl)
        // 开播分支直接返回：不应请求个人主页
        assertEquals("", client.seenProfileUrl)
    }

    @Test
    fun getStreamInfo_replayFallsBackToProfile() = runTest {
        val client = FakeClient(replayState)
        val info = XhsSpider(client).getStreamInfo("https://www.xiaohongshu.com/user/profile/555")
        assertFalse(info.isLive)
        assertEquals("个人页主播", info.anchorName)
        assertTrue(client.seenProfileUrl.endsWith("/user/profile/555"))
    }

    @Test
    fun getStreamInfo_offlineProfileAnchor() = runTest {
        val client = FakeClient("<html>no state</html>")
        val info = XhsSpider(client).getStreamInfo("https://www.xiaohongshu.com/user/profile/777")
        assertFalse(info.isLive)
        assertEquals("个人页主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_xhslinkResolvesRedirect() = runTest {
        // 短链重定向到直播间页（携带 __INITIAL_STATE__），随后正常解析
        val client = FakeClient(
            roomHtml = liveState,
            redirectUrl = "https://www.xiaohongshu.com/live/room999",
        )
        val info = XhsSpider(client).getStreamInfo("https://xhslink.com/xpJpfM")
        assertTrue(info.isLive)
        assertEquals("小红书主播", info.anchorName)
    }

    @Test
    fun getStreamInfo_hostIdFallback() = runTest {
        // 无 /user/profile/ 路径时 user_id 回落 query host_id
        val client = FakeClient("<html>no state</html>")
        XhsSpider(client).getStreamInfo("https://www.xiaohongshu.com/live?host_id=888")
        assertTrue(client.seenProfileUrl.endsWith("/user/profile/888"))
    }
}
