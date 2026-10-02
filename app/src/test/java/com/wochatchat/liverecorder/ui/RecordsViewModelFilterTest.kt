package com.wochatchat.liverecorder.ui

import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R21 Phase 1.1：记录页搜索/排序/未完成标注纯逻辑单测。
 * 排序与筛选逻辑与 RecordsViewModel.filtered 一致（同算法覆盖）。
 */
class RecordsViewModelFilterTest {

    private fun entry(
        anchor: String,
        title: String,
        url: String,
        bytes: Long,
        durationMs: Long,
        endTimeMs: Long,
        completed: Boolean = true,
    ) = RecordHistoryEntry(
        url = url,
        platform = "",
        anchorName = anchor,
        title = title,
        savePath = "/fake/$endTimeMs.mp4",
        endTimeMs = endTimeMs,
        durationMs = durationMs,
        bytes = bytes,
        completed = completed,
    )

    private fun applyFilter(
        list: List<RecordHistoryEntry>,
        query: String = "",
        timeRange: RecordsViewModel.TimeRange = RecordsViewModel.TimeRange.ALL,
        todayStart: Long = 0L,
        weekStart: Long = 0L,
        sort: RecordsViewModel.SortMode = RecordsViewModel.SortMode.TIME,
    ): List<RecordHistoryEntry> = list
        .filter { e ->
            val rangeStart = when (timeRange) {
                RecordsViewModel.TimeRange.ALL -> 0L
                RecordsViewModel.TimeRange.TODAY -> todayStart
                RecordsViewModel.TimeRange.THIS_WEEK -> weekStart
            }
            (rangeStart == 0L || e.endTimeMs >= rangeStart) &&
                (query.isBlank() || (
                    e.anchorName.contains(query, ignoreCase = true) ||
                        e.title.contains(query, ignoreCase = true) ||
                        platformKeyForUrl(e.url).contains(query, ignoreCase = true)
                    ))
        }
        .let { list0 ->
            when (sort) {
                RecordsViewModel.SortMode.TIME -> list0.sortedByDescending { it.endTimeMs }
                RecordsViewModel.SortMode.SIZE -> list0.sortedByDescending { it.bytes }
                RecordsViewModel.SortMode.DURATION -> list0.sortedByDescending { it.durationMs }
            }
        }

    @Test
    fun `sort_time_descending_by_endTimeMs`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 100, 1000, 1000),
            entry("b", "t2", "https://x/2", 100, 1000, 3000),
            entry("c", "t3", "https://x/3", 100, 1000, 2000),
        )
        assertEquals(
            listOf(3000L, 2000L, 1000L),
            applyFilter(list).map { it.endTimeMs },
        )
    }

    @Test
    fun `sort_size_descending_by_bytes`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 300, 1000, 1000),
            entry("b", "t2", "https://x/2", 100, 1000, 2000),
            entry("c", "t3", "https://x/3", 200, 1000, 3000),
        )
        assertEquals(
            listOf(300L, 200L, 100L),
            applyFilter(list, sort = RecordsViewModel.SortMode.SIZE).map { it.bytes },
        )
    }

    @Test
    fun `sort_duration_descending_by_durationMs`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 100, 300, 1000),
            entry("b", "t2", "https://x/2", 100, 100, 2000),
            entry("c", "t3", "https://x/3", 100, 200, 3000),
        )
        assertEquals(
            listOf(300L, 200L, 100L),
            applyFilter(list, sort = RecordsViewModel.SortMode.DURATION).map { it.durationMs },
        )
    }

    @Test
    fun `search_matches_title`() {
        val list = listOf(
            entry("张三", "游戏直播", "https://www.douyu.com/123", 100, 1000, 1000),
            entry("李四", "唱歌", "https://live.bilibili.com/456", 200, 2000, 2000),
            entry("王五", "户外", "https://www.huya.com/789", 300, 3000, 3000),
        )
        val hit = applyFilter(list, query = "唱")
        assertEquals(1, hit.size)
        assertEquals("李四", hit[0].anchorName)
    }

    @Test
    fun `search_matches_anchor_name`() {
        val list = listOf(
            entry("张三", "游戏直播", "https://www.douyu.com/123", 100, 1000, 1000),
            entry("李四", "唱歌", "https://live.bilibili.com/456", 200, 2000, 2000),
        )
        val hit = applyFilter(list, query = "张三")
        assertEquals(1, hit.size)
        assertEquals("张三", hit[0].anchorName)
    }

    @Test
    fun `search_matches_platform_key`() {
        val list = listOf(
            entry("张三", "t", "https://www.douyu.com/123", 100, 1000, 1000),
            entry("李四", "t2", "https://live.bilibili.com/456", 200, 2000, 2000),
        )
        val hit = applyFilter(list, query = "bili")
        assertEquals(1, hit.size)
        assertEquals("李四", hit[0].anchorName)
    }

    @Test
    fun `search_blank_matches_all`() {
        val list = listOf(
            entry("a", "t", "https://x/1", 100, 1000, 1000),
            entry("b", "t2", "https://x/2", 100, 1000, 2000),
        )
        assertEquals(2, applyFilter(list, query = "  ").size)
    }

    @Test
    fun `today_filter_uses_endTimeMs_boundary`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 100, 1000, endTimeMs = 1000),
            entry("b", "t2", "https://x/2", 100, 1000, endTimeMs = 5000),
        )
        val hit = applyFilter(list, timeRange = RecordsViewModel.TimeRange.TODAY, todayStart = 2000)
        assertEquals(listOf(5000L), hit.map { it.endTimeMs })
    }

    @Test
    fun `week_filter_excludes_old_entries`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 100, 1000, endTimeMs = 1000),
            entry("b", "t2", "https://x/2", 100, 1000, endTimeMs = 5000),
        )
        // weekStart=3000 → only entries with endTimeMs >= 3000 pass
        val hit = applyFilter(list, timeRange = RecordsViewModel.TimeRange.THIS_WEEK, weekStart = 3000)
        assertEquals(listOf(5000L), hit.map { it.endTimeMs })
    }

    @Test
    fun `all_filter_matches_all_entries`() {
        val list = listOf(
            entry("a", "t1", "https://x/1", 100, 1000, 1000),
            entry("b", "t2", "https://x/2", 100, 1000, 2000),
        )
        // ALL with no date restrictions: rangeStart=0 → no filtering
        val hit = applyFilter(list, timeRange = RecordsViewModel.TimeRange.ALL)
        assertEquals(2, hit.size)
    }

    @Test
    fun `search_case_insensitive_on_platform_key`() {
        val list = listOf(
            entry("a", "t", "https://www.douyu.com/1", 100, 1000, 1000),
        )
        assertEquals(1, applyFilter(list, query = "DOUYU").size)
        assertEquals(1, applyFilter(list, query = "douyu").size)
    }

    @Test
    fun `incomplete_flag_json_roundtrip_preserved`() {
        val e = entry("a", "t", "https://x/1", 100, 1000, 1000, completed = false)
        assertFalse(e.completed)
        val restored = RecordHistoryEntry.fromJson(e.toJson())
        assertFalse(restored.completed)
        assertEquals(e, restored)
    }

    @Test
    fun `platform_keys_distinct_for_filter_chips`() {
        val list = listOf(
            entry("a", "t", "https://www.douyu.com/1", 100, 1000, 1000),
            entry("b", "t2", "https://www.douyu.com/2", 100, 1000, 2000),
            entry("c", "t3", "https://www.huya.com/3", 100, 1000, 3000),
        )
        val keys = list.map { platformKeyForUrl(it.url) }.distinct()
        assertEquals(listOf("douyu", "huya"), keys)
    }
}
