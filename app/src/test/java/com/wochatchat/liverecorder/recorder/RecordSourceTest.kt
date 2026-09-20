package com.wochatchat.liverecorder.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上游 main.py clean_name / select_source_url / get_record_headers / get_quality_code 行为对照。 */
class RecordSourceTest {

    // ---------- isFlvPreferredPlatform ----------

    @Test
    fun flvPreferred_platforms() {
        assertTrue(RecordSource.isFlvPreferredPlatform("https://live.douyin.com/123"))
        assertTrue(RecordSource.isFlvPreferredPlatform("https://v.douyin.com/abc/"))
        assertTrue(RecordSource.isFlvPreferredPlatform("https://www.tiktok.com/@u/live"))
        org.junit.Assert.assertFalse(RecordSource.isFlvPreferredPlatform("https://www.huya.com/123"))
    }

    // ---------- selectSourceUrl（上游 main.py:534） ----------

    @Test
    fun douyin_flv_h264_usesFlv() {
        val flv = "https://example.douyincdn.com/flv?codec=h264&rate=0"
        assertEquals(
            flv,
            RecordSource.selectSourceUrl("https://live.douyin.com/1", flv, "https://m3u8"),
        )
    }

    @Test
    fun douyin_flv_h265_fallsBackToRecordUrl() {
        val flv = "https://example.douyincdn.com/flv?codec=h265&rate=0"
        assertEquals(
            "https://hls.m3u8",
            RecordSource.selectSourceUrl("https://live.douyin.com/1", flv, "https://hls.m3u8"),
        )
    }

    @Test
    fun douyin_flv_noCodec_usesFlv() {
        val flv = "https://cdn.example.com/flv?rs=abc"
        assertEquals(flv, RecordSource.selectSourceUrl("https://live.douyin.com/1", flv, "https://hls.m3u8"))
    }

    @Test
    fun nonFlvPlatform_usesRecordUrl() {
        assertEquals(
            "https://record.url/stream",
            RecordSource.selectSourceUrl("https://live.kuaishou.com/1", "https://flv.url", "https://record.url/stream"),
        )
    }

    // ---------- getQueryParam（utils.get_query_params → parse_qs） ----------

    @Test
    fun queryParam_basic() {
        val url = "https://cdn.example.com/flv?codec=h265&rate=0"
        assertEquals(listOf("h265"), RecordSource.getQueryParam(url, "codec"))
        assertEquals(emptyList<String>(), RecordSource.getQueryParam(url, "missing"))
    }

    @Test
    fun queryParam_decodesAndKeepsDuplicates() {
        assertEquals(
            listOf("a b", "c"),
            RecordSource.getQueryParam("https://x.com/p?k=a+b&j=1&k=c", "k"),
        )
    }

    @Test
    fun queryParam_noQuery() {
        assertEquals(emptyList<String>(), RecordSource.getQueryParam("https://x.com/p", "codec"))
    }

    // ---------- getRecordHeaders ----------

    @Test
    fun recordHeaders_douyinNone() {
        assertNull(RecordSource.getRecordHeaders("抖音直播", "https://live.douyin.com/1"))
    }

    @Test
    fun recordHeaders_staticEntries() {
        assertEquals(
            "origin" to "https://www.pandalive.co.kr",
            RecordSource.getRecordHeaders("PandaTV", "https://ignored.example/x"),
        )
        assertEquals(
            "referer" to "https://qiandurebo.com",
            RecordSource.getRecordHeaders("千度热播", "https://x.com/y"),
        )
    }

    @Test
    fun recordHeaders_shopeeUsesLiveDomain() {
        assertEquals(
            "origin" to "https://live.shopee.com",
            RecordSource.getRecordHeaders("shopee", "https://live.shopee.com/pc/live?room=1"),
        )
    }

    // ---------- cleanName（上游 clean_name） ----------

    @Test
    fun cleanName_replacesForbiddenChars() {
        assertEquals("小央_视频", RecordSource.cleanName("小央:视频"))
        assertEquals("a_b_c", RecordSource.cleanName("a/b\\c"))
        assertEquals("x_y", RecordSource.cleanName("x?y"))
    }

    @Test
    fun cleanName_fullwidthParens() {
        assertEquals("主播(测试)", RecordSource.cleanName("主播（测试）"))
    }

    @Test
    fun cleanName_removesEmoji() {
        assertEquals("主播", RecordSource.cleanName("主播😀"))
        assertEquals("主播_录制", RecordSource.cleanName("主播😀🔥录制"))
    }

    @Test
    fun cleanName_blankFallsBack() {
        assertEquals("空白昵称", RecordSource.cleanName("???"))
        assertEquals("空白昵称", RecordSource.cleanName("  "))
        assertEquals("空白昵称", RecordSource.cleanName(""))
    }

    // ---------- getQualityCode ----------

    @Test
    fun qualityCode_mapping() {
        assertEquals("OD", RecordSource.getQualityCode("原画"))
        assertEquals("BD", RecordSource.getQualityCode("蓝光"))
        assertEquals("UHD", RecordSource.getQualityCode("超清"))
        assertEquals("HD", RecordSource.getQualityCode("高清"))
        assertEquals("SD", RecordSource.getQualityCode("标清"))
        assertEquals("LD", RecordSource.getQualityCode("流畅"))
        assertNull(RecordSource.getQualityCode("不存在"))
    }
}
