package com.wochatchat.liverecorder.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ---------- applyRecordingScheme（5a，上游 main.py:1150-1156） ----------

    @Test
    fun recordingScheme_forceHttpsRewritesHttp() {
        assertEquals(
            "https://cdn.example.com/live.flv",
            RecordSource.applyRecordingScheme("http://cdn.example.com/live.flv", forceHttps = true, platform = "抖音直播"),
        )
        assertEquals(
            "http://cdn.example.com/live.flv",
            RecordSource.applyRecordingScheme("http://cdn.example.com/live.flv", forceHttps = false, platform = "抖音直播"),
        )
        // https 源不受影响
        assertEquals(
            "https://hls.example.com/x.m3u8",
            RecordSource.applyRecordingScheme("https://hls.example.com/x.m3u8", forceHttps = true, platform = "虎牙直播"),
        )
    }

    @Test
    fun recordingScheme_shopeeMiguForcedHttp() {
        // 上游 http_record_list：shopee/migu 无论开关强制 http
        assertEquals(
            "http://migu.example.com/stream.flv",
            RecordSource.applyRecordingScheme("https://migu.example.com/stream.flv", forceHttps = true, platform = "migu"),
        )
    }

        // ---------- 5b：文件命名规则（上游 main.py:1117-1146） ----------

    private val OPTS_DEFAULT = RecordSource.NamingOptions() // 作者区分=是，其余否

    @Test
    fun saveDir_authorOnlyByDefault() {
        val dir = RecordSource.buildSaveDir(File("/base"), "抖音直播", "主播A", "", "2026-09-24", OPTS_DEFAULT)
        assertEquals("/base/抖音直播/主播A", dir.path)
    }

    @Test
    fun saveDir_noAuthorWhenDisabled() {
        val dir = RecordSource.buildSaveDir(
            File("/base"), "抖音直播", "主播A", "标题", "2026-09-24",
            OPTS_DEFAULT.copy(folderByAuthor = false),
        )
        assertEquals("/base/抖音直播", dir.path)
    }

    @Test
    fun saveDir_timeLayer() {
        val dir = RecordSource.buildSaveDir(
            File("/base"), "快手直播", "主播A", "", "2026-09-24",
            OPTS_DEFAULT.copy(folderByTime = true),
        )
        assertEquals("/base/快手直播/主播A/2026-09-24", dir.path)
    }

    @Test
    fun saveDir_titleWithTime_usesTitleAuthor() {
        // 上游 folder_by_title && folder_by_time → {标题}_{主播}
        val dir = RecordSource.buildSaveDir(
            File("/base"), "抖音直播", "主播A", "标题X", "2026-09-24",
            OPTS_DEFAULT.copy(folderByTime = true, folderByTitle = true),
        )
        assertEquals("/base/抖音直播/主播A/2026-09-24/标题X_主播A", dir.path)
    }

    @Test
    fun saveDir_titleWithoutTime_usesDateTitle() {
        // 上游 folder_by_title 且未按时间 → {日期}_{标题}
        val dir = RecordSource.buildSaveDir(
            File("/base"), "抖音直播", "主播A", "标题X", "2026-09-24",
            OPTS_DEFAULT.copy(folderByTitle = true),
        )
        assertEquals("/base/抖音直播/主播A/2026-09-24_标题X", dir.path)
    }

    @Test
    fun saveDir_titleEmpty_fallsBackPlain() {
        // 标题为空时标题开关无效（上游 `and port_info.get('title')`）
        val dir = RecordSource.buildSaveDir(
            File("/base"), "抖音直播", "主播A", "", "2026-09-24",
            OPTS_DEFAULT.copy(folderByTitle = true),
        )
        assertEquals("/base/抖音直播/主播A", dir.path)
    }

    @Test
    fun baseName_withoutTitle() {
        assertEquals(
            "主播A_2026-09-24_10-00-00",
            RecordSource.buildBaseName("主播A", "", "2026-09-24_10-00-00", filenameByTitle = true),
        )
    }

    @Test
    fun baseName_withTitle() {
        // 上游 anchor_name + f'_{title_in_name}' + now：标题开关开且标题非空 → {主播}_{标题}_{时间}
        assertEquals(
            "主播A_标题X_2026-09-24_10-00-00",
            RecordSource.buildBaseName("主播A", "标题X", "2026-09-24_10-00-00", filenameByTitle = true),
        )
        // 开关关 → 标题不进文件名
        assertEquals(
            "主播A_2026-09-24_10-00-00",
            RecordSource.buildBaseName("主播A", "标题X", "2026-09-24_10-00-00", filenameByTitle = false),
        )
    }

    @Test
    fun cleanName_keepsEmojiWhenDisabled() {
        // clean_emoji=否：表情符号原样保留
        assertEquals("主播😀", RecordSource.cleanName("主播😀", cleanEmoji = false))
        // 默认（是）：表情符号去除
        assertEquals("主播", RecordSource.cleanName("主播😀", cleanEmoji = true))
    }
}
