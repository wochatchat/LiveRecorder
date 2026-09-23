/*
 * HuyaSpiderTest — Phase 4 第一批：虎牙爬虫单元测试。
 *
 * 覆盖：parseRoomId / parseWebResponse / parseAppResponse / selectStream
 *      （OD/BD/UHD → app 路径 TX 优先；HD/SD/LD → web 路径 ratio）/ buildAntiCode。
 */
package com.wochatchat.liverecorder.platform.huya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuyaSpiderTest {

    private val spider = HuyaSpider()

    // ---- 1. parseRoomId ----

    @Test
    fun parseRoomId_numeric() {
        assertEquals("11352416", HuyaSpider.parseRoomId("https://www.huya.com/11352416?from=app"))
        assertEquals("113524", HuyaSpider.parseRoomId("https://www.huya.com/113524"))
    }

    @Test
    fun parseRoomId_alphanumeric() {
        assertNull(HuyaSpider.parseRoomId("https://www.huya.com/lpl"))
    }

    // ---- 2. web 页解析（spider.py:408）----

    private fun webHtml(streamListJson: String): String {
        val json = """{"data":[{"gameLiveInfo":{"nick":"测试主播","introduction":"测试标题"},
            "gameStreamInfoList":$streamListJson}]}"""
        return "<html><script>stream: $json,\"iWebDefaultBitRate\":0</script></html>"
    }

    private val alStream = """{"sCdnType":"AL","sFlvUrl":"http://al.flv.huya.com/src",
        "sHlsUrl":"http://al.hls.huya.com/src","sStreamName":"113524-1234567-524288-1-10057-A",
        "sFlvAntiCode":"fm=bW9iaWxlXw&ctype=tars_mp&fs=bhct&exsphd=264_4000,264_2000,264_1000,264_800,264_600",
        "sHlsAntiCode":"fm=x","sFlvUrlSuffix":"flv","sHlsUrlSuffix":"m3u8"}"""

    private val txStream = """{"sCdnType":"TX","sFlvUrl":"http://tx.flv.huya.com/src",
        "sHlsUrl":"http://tx.hls.huya.com/src","sStreamName":"113524-txstream-1-10057-A",
        "sFlvAntiCode":"fm=y&ctype=tars_mp&fs=bhct","sHlsAntiCode":"fm=y",
        "sFlvUrlSuffix":"flv","sHlsUrlSuffix":"m3u8"}"""

    @Test
    fun parseWebResponse_live() {
        val info = spider.parseWebResponse(webHtml("[$alStream,$txStream]"))
        assertEquals("测试主播", info.anchorName)
        assertEquals("测试标题", info.title)
        assertTrue(info.isLive)
        assertEquals(2, info.streams.size)
        assertEquals("AL", info.streams[0].cdnType)
        assertEquals("TX", info.streams[1].cdnType)
    }

    @Test
    fun parseWebResponse_noMatch() {
        val info = spider.parseWebResponse("<html>empty page</html>")
        assertFalse(info.isLive)
        assertTrue(info.streams.isEmpty())
    }

    // ---- 3. app 路径解析（spider.py:441-468）----

    private fun appJson(liveStatus: String, streamListJson: String): String = """
        {"data":{"profileInfo":{"nick":"App主播"},"liveData":{"introduction":"App标题"},
        "realLiveStatus":"$liveStatus","stream":{"baseSteamInfoList":$streamListJson}}}
    """.trimIndent()

    @Test
    fun parseAppResponse_live() {
        val info = spider.parseAppResponse(appJson("ON", "[$alStream,$txStream]"), "113524")
        assertEquals("App主播", info.anchorName)
        assertEquals("App标题", info.title)
        assertTrue(info.isLive)
        assertEquals(2, info.streams.size)
        assertEquals("113524-1234567-524288-1-10057-A", info.streams[0].streamName)
    }

    @Test
    fun parseAppResponse_offline() {
        val info = spider.parseAppResponse(appJson("REPLAY", "[]"), "113524")
        assertFalse(info.isLive)
        assertEquals("App主播", info.anchorName)
    }

    // ---- 4. selectStream 路径分流（main.py:618-630 + stream.py:210）----

    @Test
    fun selectStream_OD_usesAppPath_txPriority() {
        val info = spider.parseAppResponse(appJson("ON", "[$alStream,$txStream]"), "113524")
        val play = spider.selectStream(info, "OD")!!
        // TX CDN 优先 + ctype/fs 替换 + 强转 https（spider.py:487-506）
        assertTrue(play.recordUrl.startsWith("https://tx.flv.huya.com/src/"))
        assertTrue(play.recordUrl.contains("113524-txstream-1-10057-A.flv?"))
        assertTrue(play.recordUrl.endsWith("?fm=y&ctype=tars_mp&fs=bhct"))
        // m3u8/flv 参考字段取 play_url_list[0]（AL）
        assertTrue(play.m3u8Url.startsWith("http://al.hls.huya.com/"))
        assertTrue(play.flvUrl.startsWith("http://al.flv.huya.com/src/"))
    }

    @Test
    fun selectAppPath_hdQuality_fallsBackToFirstCdn_whenNoTx() {
        // OD 走 app 路径，无 TX/AL 时回退（上游 selected_flv_url 为 None → record_url=None，
        // 本侧回退首条避免空录制源）
        val info = spider.parseAppResponse(appJson("ON", "[$txStream]"), "113524")
        val play = spider.selectStream(info, "BD")!!
        assertTrue(play.recordUrl.startsWith("https://tx.flv.huya.com/"))
    }

    @Test
    fun selectStream_HD_webPathRatio() {
        val info = spider.parseWebResponse(webHtml("[$alStream]"))
        val play = spider.selectStream(info, "HD")!!
        // exsphd 码率倒序后 HD 档 = 800（上游 quality_list[::-1] 后 [1]）
        assertTrue(play.recordUrl.contains("&ratio=800"))
        assertEquals("HD", play.quality)
    }

    @Test
    fun selectStream_UHD_webPathFirstRatio() {
        val info = spider.parseWebResponse(webHtml("[$alStream]"))
        val play = spider.selectStream(info, "UHD")!!
        assertTrue(play.recordUrl.contains("&ratio=600"))
    }

    // ---- 5. anti-code 重算（stream.py:220-262）----

    @Test
    fun buildAntiCode_structure() {
        val old = "fm=bU9iaWxlXzM%3D&ctype=tars_mp&fs=bhct&wsTime=65f01234"
        val anti = spider.buildAntiCode(old, streamName = "113524-abc")
        val map = spider.parseQuery(anti)
        assertEquals(old.substringAfter("ctype=").substringBefore('&'), map["ctype"])
        assertEquals(old.substringAfter("fs=").substringBefore('&'), map["fs"])
        assertEquals("1", map["ver"])
        assertEquals("264", map["codec"])
        assertEquals("100", map["t"])
        assertEquals("2403051612", map["sv"])
        assertNotNull(map["wsSecret"])
        assertTrue(map["wsSecret"]!!.matches(Regex("[0-9a-f]{32}")))
        assertTrue(map["wsTime"]!!.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun parseQuery_decodes() {
        val map = spider.parseQuery("a=1&ctype=tars_mp&fm=x%3Dy&fs=bhct")
        assertEquals("1", map["a"])
        assertEquals("x=y", map["fm"])
        assertEquals("bhct", map["fs"])
    }
}
