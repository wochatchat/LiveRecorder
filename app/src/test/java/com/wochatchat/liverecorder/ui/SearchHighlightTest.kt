package com.wochatchat.liverecorder.ui

import com.wochatchat.liverecorder.ui.screens.buildHighlightedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页搜索关键词高亮纯函数测试（V4-2）。 */
class SearchHighlightTest {

    private fun spanRanges(text: String, query: String): List<IntRange> {
        val s = buildHighlightedText(text, query)
        // AnnotatedString 区间 end 为排他（until 语义）
        return s.spanStyles.map { it.start until it.end }
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
        assertEquals(listOf(2..4), ranges)
    }

    @Test fun `match is case insensitive`() {
        val ranges = spanRanges("Enable Proxy", "proxy")
        assertEquals(listOf(7..12), ranges)
    }

    @Test fun `multiple matches all highlighted`() {
        val ranges = spanRanges("画质优先，画质兜底", "画质")
        assertEquals(listOf(0..2, 5..7), ranges)
    }

    @Test fun `query with surrounding spaces is trimmed`() {
        val ranges = spanRanges("视频画质", " 画质 ")
        assertEquals(listOf(2..4), ranges)
    }

    @Test fun `highlight preserves full text`() {
        val s = buildHighlightedText("录制目录", "目录")
        assertEquals("录制目录", s.text)
        assertTrue(s.spanStyles.isNotEmpty())
    }
}
