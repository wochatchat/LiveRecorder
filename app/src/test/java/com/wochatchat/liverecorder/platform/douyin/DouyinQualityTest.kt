package com.wochatchat.liverecorder.platform.douyin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 画质映射单测（对照上游 src/stream.py QUALITY_MAPPING / get_quality_index / get_douyin_stream_url）。
 */
class DouyinQualityTest {

    // 2 档画质（flv/hls 各 2 个），index≥2 的档位由 padding 复制末档
    private val room2 = DouyinWebRoom(
        anchorName = "测试主播", status = 2, title = "测试直播",
        flvPullUrl = linkedMapOf("FULL_HD1" to "flv://a", "SD1" to "flv://b"),
        hlsPullUrlMap = linkedMapOf("FULL_HD1" to "hls://a", "SD1" to "hls://b"),
    )

    // 5 档（含 ORIGIN 合并，同真实数据）
    private val room5 = DouyinWebRoom(
        anchorName = "小央视频", status = 2, title = "直播中",
        flvPullUrl = linkedMapOf(
            "ORIGIN" to "flv://origin", "FULL_HD1" to "flv://fhd", "HD1" to "flv://hd",
            "SD1" to "flv://sd", "SD2" to "flv://ld",
        ),
        hlsPullUrlMap = linkedMapOf(
            "ORIGIN" to "hls://origin", "FULL_HD1" to "hls://fhd", "HD1" to "hls://hd",
            "SD1" to "hls://sd", "SD2" to "hls://ld",
        ),
    )

    // ------------------------------------------------------------------
    // resolveQualityIndex（上游 get_quality_index）
    // ------------------------------------------------------------------

    @Test fun `quality - null defaults to OD`() {
        assertEquals("OD" to 0, DouyinQuality.resolveQualityIndex(null))
    }

    @Test fun `quality - empty string defaults to OD like upstream falsy`() {
        assertEquals("OD" to 0, DouyinQuality.resolveQualityIndex(""))
    }

    @Test fun `quality - names map to index`() {
        assertEquals("OD" to 0, DouyinQuality.resolveQualityIndex("OD"))
        assertEquals("BD" to 0, DouyinQuality.resolveQualityIndex("BD"))
        assertEquals("UHD" to 1, DouyinQuality.resolveQualityIndex("UHD"))
        assertEquals("HD" to 2, DouyinQuality.resolveQualityIndex("HD"))
        assertEquals("SD" to 3, DouyinQuality.resolveQualityIndex("SD"))
        assertEquals("LD" to 4, DouyinQuality.resolveQualityIndex("LD"))
    }

    @Test fun `quality - lowercase input is uppercased`() {
        assertEquals("OD" to 0, DouyinQuality.resolveQualityIndex("od"))
    }

    @Test fun `quality - digits map through key list like upstream`() {
        // 上游：数字 → list(QUALITY_MAPPING.keys())[n] 取名字再查 index（键序含 BD）
        assertEquals("OD" to 0, DouyinQuality.resolveQualityIndex("0"))
        assertEquals("BD" to 0, DouyinQuality.resolveQualityIndex("1"))
        assertEquals("UHD" to 1, DouyinQuality.resolveQualityIndex("2"))
        assertEquals("HD" to 2, DouyinQuality.resolveQualityIndex("3"))
        assertEquals("SD" to 3, DouyinQuality.resolveQualityIndex("4"))
        assertEquals("LD" to 4, DouyinQuality.resolveQualityIndex("5"))
    }

    @Test fun `quality - unknown name falls back to index 0`() {
        assertEquals("XX" to 0, DouyinQuality.resolveQualityIndex("XX"))
    }

    // ------------------------------------------------------------------
    // resolveStream
    // ------------------------------------------------------------------

    @Test fun `not live returns isLive false`() {
        val room = DouyinWebRoom.empty("offline")
        val info = runBlocking { DouyinQuality.resolveStream(room, "OD") }
        assertEquals(false, info.isLive)
        assertEquals("", info.anchorName)
    }

    @Test fun `out of range digit quality degrades to not live like upstream`() {
        // 上游 IndexError → trace_error_decorator 返回 [] → 等价未开播
        val info = runBlocking { DouyinQuality.resolveStream(room5, "9", probe = { true }) }
        assertEquals(false, info.isLive)
    }

    @Test fun `live room - selected quality urls and record_url is m3u8`() {
        val info = runBlocking { DouyinQuality.resolveStream(room5, "UHD", probe = { true }) }
        assertEquals(true, info.isLive)
        assertEquals("UHD", info.quality)
        assertEquals("hls://fhd", info.m3u8Url)
        assertEquals("flv://fhd", info.flvUrl)
        assertEquals("hls://fhd", info.recordUrl)
    }

    @Test fun `live room - BD maps to index 0 same as OD`() {
        val info = runBlocking { DouyinQuality.resolveStream(room5, "BD", probe = { true }) }
        assertEquals("hls://origin", info.m3u8Url)
        assertEquals("BD", info.quality)
    }

    @Test fun `live room - digit 2 selects UHD index 1`() {
        val info = runBlocking { DouyinQuality.resolveStream(room5, "2", probe = { true }) }
        assertEquals("UHD", info.quality)
        assertEquals("hls://fhd", info.m3u8Url)
    }

    @Test fun `live room - padding repeats last tier for deep index`() {
        // 2 档补齐 5 档后，index 3/4 均为末档
        val info = runBlocking { DouyinQuality.resolveStream(room2, "LD", probe = { true }) }
        assertEquals("hls://b", info.m3u8Url)
        assertEquals("flv://b", info.flvUrl)
 высокого    }

    @Test fun `probe fail - falls back to next index`() {
        // index 0 探测失败 → 用 index 1（quality_index+1）
        val info = runBlocking { DouyinQuality.resolveStream(room5, "OD", probe = { false }) }
        assertEquals("hls://fhd", info.m3u8Url)
        assertEquals("flv://fhd", info.flvUrl)
    }

    @Test fun `probe fail at index 4 - falls back to previous index`() {
        val info = runBlocking { DouyinQuality.resolveStream(room5, "LD", probe = { false }) }
        // index=4（LD），<4 不成立 → index-1=3（SD 档）
        assertEquals("hls://sd", info.m3u8Url)
        assertEquals("flv://sd", info.flvUrl)
    }

    @Test fun `no probe - takes url directly and record_url is m3u8`() {
        val info = runBlocking { DouyinQuality.resolveStream(room2, "HD", client = null) }
        assertEquals(true, info.isLive)
        assertEquals("hls://a", info.m3u8Url)
        assertEquals("flv://a", info.flvUrl)
        assertEquals("hls://a", info.recordUrl)
        assertEquals("HD", info.quality)
        assertEquals("测试主播", info.anchorName)
    }

    @Test fun `two tier room - OD picks first tier`() {
        val info = runBlocking { DouyinQuality.resolveStream(room2, "OD", probe = { true }) }
        assertEquals("hls://a", info.m3u8Url)
        assertEquals("flv://a", info.flvUrl)
    }

    @Test fun `record url falls back to flv when m3u8 empty`() {
        val room = DouyinWebRoom(
            anchorName = "x", status = 2, title = "",
            flvPullUrl = linkedMapOf("FULL_HD1" to "flv://only"),
            hlsPullUrlMap = emptyMap(),
        )
        val info = runBlocking { DouyinQuality.resolveStream(room, null, probe = { true }) }
        assertEquals("", info.m3u8Url)
        assertEquals("flv://only", info.recordUrl)
    }
}