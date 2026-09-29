package com.wochatchat.liverecorder.platform.tiktok

import com.wochatchat.liverecorder.net.LiveHttpClient
import com.wochatchat.liverecorder.net.LiveHttpClient.HttpResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TikTok 单测：SIGI_STATE 提取 + 画质排序/取档 + 探测回退 + 重试（上游 spider.py:286 / stream.py:82）。 */
class TikTokSpiderTest {

    // ---- fixture 构造（stream_data 为 JSON 字符串，程序化拼装避免转义地狱） ----

    private fun mainEntry(
        vbitrate: Int,
        resolution: String,
        codec: String = "h264",
        flv: String,
        hls: String,
    ): JSONObject = JSONObject()
        .put("flv", flv)
        .put("hls", hls)
        .put(
            "sdk_params",
            JSONObject().put("vbitrate", vbitrate).put("resolution", resolution).put("VCodec", codec).toString(),
        )

    private fun sigiJson(status: Int, streams: JSONObject): JSONObject {
        val streamDataStr = JSONObject().put("data", streams).toString()
        return JSONObject().put(
            "LiveRoom",
            JSONObject().put(
                "liveRoomUserInfo",
                JSONObject()
                    .put(
                        "user",
                        JSONObject().put("nickname", "TikTok主播").put("uniqueId", "ttuser").put("status", status),
                    )
                    .put(
                        "liveRoom",
                        JSONObject()
                            .put("title", "TT标题")
                            .put(
                                "streamData",
                                JSONObject().put(
                                    "pull_data",
                                    JSONObject().put("stream_data", streamDataStr),
                                ),
                            ),
                    ),
            ),
        )
    }

    /** 两档流：q_hd(4M/1080p) q_ld(1M/480p)。 */
    private fun twoQualityStreams(): JSONObject = JSONObject()
        .put(
            "q_hd",
            mainEntry(4000000, "1920x1080", flv = "https://flv.tt.com/live0.flv", hls = "https://hls.tt.com/live0.m3u8"),
        )
        .put(
            "q_ld",
            mainEntry(1000000, "480x270", flv = "https://flv.tt.com/live0_ld.flv", hls = "https://hls.tt.com/live0_ld.m3u8"),
        )

    private fun sigiHtml(sigi: JSONObject): String =
        "<html><script id=\"SIGI_STATE\" type=\"application/json\">$sigi</script></html>"

    private class FakeClient(
        private val pages: List<String>,
        private val probeOk: Boolean = true,
    ) : LiveHttpClient() {
        var attempts = 0
        var lastHeaders: Map<String, String> = emptyMap()
        var probedUrl = ""

        override suspend fun get(url: String, headers: Map<String, String>, timeoutSec: Long): HttpResult {
            attempts++
            lastHeaders = headers
            val page = pages.getOrNull(attempts - 1) ?: pages.last()
            return HttpResult(200, page, url, emptyMap())
        }

        override suspend fun head(url: String, headers: Map<String, String>, timeoutSec: Long): Boolean {
            probedUrl = url
            return probeOk
        }
    }

    // ---- 1. URL/提取纯逻辑 ----

    @Test
    fun isTiktokUrl() {
        assertTrue(TikTokSpider.isTiktokUrl("https://www.tiktok.com/@user/live"))
        assertFalse(TikTokSpider.isTiktokUrl("https://live.douyin.com/123"))
    }

    @Test
    fun extractSigiState() {
        assertEquals(
            """{"a":1}""",
            TikTokSpider.extractSigiState(
                """<script id="SIGI_STATE" type="application/json">{"a":1}</script>""",
            ),
        )
        assertNull(TikTokSpider.extractSigiState("<html>no state</html>"))
    }

    // ---- 2. 画质列表：排序 / codec 拼接 / 过滤 ----

    @Test
    fun qualityUrlList_sortedByBitrateThenResolution() {
        val streams = twoQualityStreams()
        val flv = TikTokSpider().qualityUrlList(streams, "flv")
        assertEquals(2, flv.size)
        // vbitrate 降序：q_hd 在前
        assertEquals("https://flv.tt.com/live0.flv?codec=h264", flv[0].url)
        assertEquals("https://flv.tt.com/live0_ld.flv?codec=h264", flv[1].url)
        assertEquals(4000000, flv[0].vbitrate)
        assertEquals(1920, flv[0].width)
        assertEquals(1080, flv[0].height)
    }

    @Test
    fun qualityUrlList_codecAppendsWithQueryMark() {
        val streams = JSONObject().put(
            "q1",
            mainEntry(
                2000000, "1280x720",
                flv = "https://flv.tt.com/a.flv?wsAuth=xyz", hls = "https://hls.tt.com/a.m3u8",
            ),
        )
        val flv = TikTokSpider().qualityUrlList(streams, "flv")
        // 已带 query → 用 & 拼 codec
        assertEquals("https://flv.tt.com/a.flv?wsAuth=xyz&codec=h264", flv[0].url)
        val hls = TikTokSpider().qualityUrlList(streams, "hls")
        assertEquals("https://hls.tt.com/a.m3u8?codec=h264", hls[0].url)
    }

    @Test
    fun qualityUrlList_filtersZeroBitrateAndMissingResolution() {
        val streams = JSONObject()
            .put("zero", mainEntry(0, "1920x1080", flv = "https://f.flv", hls = "https://h.m3u8"))
            .put("noRes", mainEntry(3000000, "", flv = "https://f2.flv", hls = "https://h2.m3u8"))
            .put("ok", mainEntry(1000000, "640x360", flv = "https://f3.flv", hls = "https://h3.m3u8"))
        val list = TikTokSpider().qualityUrlList(streams, "flv")
        assertEquals(1, list.size)
        assertEquals("https://f3.flv?codec=h264", list[0].url)
    }

    @Test
    fun padToFive_repeatsLast() {
        val one = listOf(TikTokSpider.QualityUrl("u", 1, 2, 3))
        val padded = TikTokSpider().padToFive(one)
        assertEquals(5, padded.size)
        assertEquals("u", padded[4].url)
        assertTrue(TikTokSpider().padToFive(emptyList()).isEmpty())
    }

    // ---- 3. selectStream：未开播 / 默认取档 / HD 取档 / 探测回退 ----

    @Test
    fun selectStream_offline() = runTest {
        val info = TikTokSpider().selectStream(sigiJson(4, twoQualityStreams()), null) { true }
        assertFalse(info.isLive)
        assertEquals("TikTok主播-ttuser", info.anchorName)
    }

    @Test
    fun selectStream_liveDefaultOD() = runTest {
        val info = TikTokSpider().selectStream(sigiJson(2, twoQualityStreams()), null) { true }
        assertTrue(info.isLive)
        assertEquals("TikTok主播-ttuser", info.anchorName)
        assertEquals("TT标题", info.title)
        // 默认 OD=index 0 → 最高码率档；recordUrl = m3u8 ?: flv
        assertEquals("https://hls.tt.com/live0.m3u8?codec=h264", info.m3u8Url)
        assertEquals("https://flv.tt.com/live0.flv?codec=h264", info.flvUrl)
        assertEquals("https://hls.tt.com/live0.m3u8?codec=h264", info.recordUrl)
    }

    @Test
    fun selectStream_hdIndex() = runTest {
        val info = TikTokSpider().selectStream(sigiJson(2, twoQualityStreams()), "HD") { true }
        assertTrue(info.isLive)
        // HD=index 2，补齐 5 档后取末档（低码率档）
        assertEquals("https://hls.tt.com/live0_ld.m3u8?codec=h264", info.m3u8Url)
    }

    @Test
    fun selectStream_probeFailFallsBack() = runTest {
        val info = TikTokSpider().selectStream(sigiJson(2, twoQualityStreams()), null) { false }
        assertTrue(info.isLive)
        // index 0 探测失败 → alt = 1 → 低码率档
        assertEquals("https://hls.tt.com/live0_ld.m3u8?codec=h264", info.m3u8Url)
        assertEquals("https://flv.tt.com/live0_ld.flv?codec=h264", info.flvUrl)
    }

    @Test
    fun selectStream_missingStreamData() = runTest {
        val noData = JSONObject().put(
            "LiveRoom",
            JSONObject().put(
                "liveRoomUserInfo",
                JSONObject().put("user", JSONObject().put("nickname", "n").put("uniqueId", "u").put("status", 2)),
            ),
        )
        val info = TikTokSpider().selectStream(noData, null) { true }
        assertFalse(info.isLive)
    }

    @Test
    fun selectStream_qualityOutOfRange() = runTest {
        // 上游数字画质越界 → IndexError → 兜底未开播
        val info = TikTokSpider().selectStream(sigiJson(2, twoQualityStreams()), "9") { true }
        assertFalse(info.isLive)
    }

    // ---- 4. getStreamInfo 端到端：cookie 兜底/覆盖、封锁、EOF 重试 ----

    @Test
    fun getStreamInfo_live() = runTest {
        val client = FakeClient(listOf(sigiHtml(sigiJson(2, twoQualityStreams()))))
        val info = TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live")
        assertTrue(info.isLive)
        assertEquals("TikTok主播-ttuser", info.anchorName)
        // 探测的是 check_url = m3u8
        assertEquals("https://hls.tt.com/live0.m3u8?codec=h264", client.probedUrl)
        // 上游兜底 cookie
        assertEquals(TikTokSpider.DEFAULT_COOKIE, client.lastHeaders["cookie"])
        assertEquals("https://www.tiktok.com/", client.lastHeaders["referer"])
    }

    @Test
    fun getStreamInfo_cookieOverride() = runTest {
        val client = FakeClient(listOf(sigiHtml(sigiJson(2, twoQualityStreams()))))
        TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live", cookie = "sid=abc")
        assertEquals("sid=abc", client.lastHeaders["cookie"])
    }

    @Test
    fun getStreamInfo_blockedRegion() = runTest {
        val blocked = "<p>\n  We regret to inform you that we have discontinued operating TikTok in your region.\n</p>"
        val client = FakeClient(listOf(blocked))
        val info = TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live")
        assertFalse(info.isLive)
        assertEquals(1, client.attempts)  // 封锁即返回，不重试
    }

    @Test
    fun getStreamInfo_eofRetriesThenSucceeds() = runTest {
        val client = FakeClient(
            listOf("<html>UNEXPECTED_EOF_WHILE_READING</html>", sigiHtml(sigiJson(2, twoQualityStreams()))),
        )
        val info = TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live")
        assertTrue(info.isLive)
        assertEquals(2, client.attempts)
    }

    @Test
    fun getStreamInfo_allEofGivesUp() = runTest {
        val client = FakeClient(listOf("<html>UNEXPECTED_EOF_WHILE_READING</html>"))
        val info = TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live")
        assertFalse(info.isLive)
        assertEquals(3, client.attempts)  // 上游 range(3)
    }

    @Test
    fun getStreamInfo_noSigiState() = runTest {
        val client = FakeClient(listOf("<html>unexpected page</html>"))
        val info = TikTokSpider(client).getStreamInfo("https://www.tiktok.com/@user/live")
        assertFalse(info.isLive)
    }
}
