package com.wochatchat.liverecorder.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6-6.4 通知聚合纯逻辑单测。
 */
class EventAggregatorTest {

    private val window = 5 * 60_000L

    private fun live(anchor: String, ts: Long) = EventAggregator.Event(true, anchor, "标题", ts)
    private fun offline(anchor: String, ts: Long) = EventAggregator.Event(false, anchor, "", ts)

    // ---- evictByWindow ----

    @Test
    fun `淘汰窗口外旧事件`() {
        val t0 = 1000_000L
        val entries = listOf(live("A", t0), live("B", t0 + 100_000))
        val now = t0 + 300_001L // 距 A 300s、距 B 200s，窗口 5min → A 淘汰（300s 不小于 300s）
        val kept = EventAggregator.evictByWindow(entries, now, window)
        assertEquals(listOf("B"), kept.map { it.anchor })
    }

    @Test
    fun `窗口边界含等于时淘汰`() {
        val t0 = 1000_000L
        val entries = listOf(live("A", t0))
        // now - ts == window → 不小于 window，淘汰
        val kept = EventAggregator.evictByWindow(entries, t0 + window, window)
        assertTrue(kept.isEmpty())
    }

    // ---- append ----

    @Test
    fun `append 追加并淘汰旧事件`() {
        val t0 = 1000_000L
        val entries = listOf(live("A", t0 - window - 1)) // 已过期
        val result = EventAggregator.append(entries, live("B", t0), t0, window)
        assertEquals(listOf("B"), result.map { it.anchor })
    }

    @Test
    fun `append 保留窗口内事件`() {
        val t0 = 1000_000L
        val entries = listOf(live("A", t0), offline("B", t0 + 10_000))
        val result = EventAggregator.append(entries, live("C", t0 + 20_000), t0 + 20_000, window)
        assertEquals(listOf("A", "B", "C"), result.map { it.anchor })
    }

    // ---- displayLines ----

    @Test
    fun `不超过上限时全部展示`() {
        val entries = (1..6).map { live("A$it", it.toLong()) }
        val (visible, extra) = EventAggregator.displayLines(entries)
        assertEquals(6, visible.size)
        assertEquals(0, extra)
    }

    @Test
    fun `超出上限折叠最旧行`() {
        val entries = (1..9).map { live("A$it", it.toLong()) }
        val (visible, extra) = EventAggregator.displayLines(entries)
        assertEquals(EventAggregator.MAX_LINES, visible.size)
        assertEquals(3, extra)
        // 保留最新（末尾）的事件
        assertEquals("A9", visible.last().anchor)
        assertEquals("A4", visible.first().anchor)
    }

    // ---- titleKind ----

    @Test
    fun `标题分类`() {
        val t = 1L
        assertEquals(EventAggregator.TITLE_LIVE, EventAggregator.titleKind(listOf(live("A", t), live("B", t))))
        assertEquals(EventAggregator.TITLE_OFFLINE, EventAggregator.titleKind(listOf(offline("A", t), offline("B", t))))
        assertEquals(EventAggregator.TITLE_MIXED, EventAggregator.titleKind(listOf(live("A", t), offline("B", t))))
        assertEquals(EventAggregator.TITLE_MIXED, EventAggregator.titleKind(emptyList()))
    }
}
