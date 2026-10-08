package com.wochatchat.liverecorder.ui

import com.wochatchat.liverecorder.ui.screens.buildHighlightedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页搜索关键词高亮纯函数测试（V4-2）。 */
class SearchHighlightTest {

    private fun spanRanges(text: String, query: String): List<IntRange> {
        val s = buildHighlightedText(text, query)
        return s.spanStyles.map { it.start..it.end }
    }

    @Test fun `query blank returns plain string`() {
        val s = buildHighlightedText("视频画质", "")
        assertEquals(0, s.spanStyles.size)
        assertEquals("视频画质", s.text)
    }

    @Test fun `no match returns plain string`() {
        val s = buildHighlightedText("视频画质", "音频")
        assertEquals(0, s.spanStyles.size)
    }

    @Test fun `single match highlighted with correct range`() {
        val ranges = spanRanges("视频画质设置", "画质")
        assertEquals(1, ranges.size)
        assertEquals(2..3, ranges[0])
    }

    @Test fun `match is case insensitive`() {
        val ranges = spanRanges("Enable Proxy", "proxy")
        assertEquals(1, ranges.size)
        assertEquals(7..11, ranges[0])
    }

    @Test fun `multiple matches all highlighted`() {
        val ranges = spanRanges("画质优先，画质兜底", "画质")
        assertEquals(2, ranges.size)
        assertEquals(0..1, ranges[0])
        assertEquals(5..6, ranges[1])
    }

    @Test fun `query with surrounding spaces is trimmed`() {
        val ranges = spanRanges("视频画质", " 画质 ")
        assertEquals(1, ranges.size)
        assertEquals(2..3, ranges[0])
    }

    @Test fun `highlight preserves full text`() {
        val s = buildHighlightedText("录制目录", "目录")
        assertEquals("录制目录", s.text)
        assertTrue(s.spanStyles.isNotEmpty())
    }
}
