package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3-2：记录页筛选纯逻辑测试——平台键推导（中文反查/URL 回落/空白条目）、
 * 动态 chip 键排序（次数降序、平序按键名）、筛选组合（时间/平台/搜索 AND）。
 */
class RecordFiltersTest {

    private fun entry(
        url: String,
        platform: String = "",
        anchor: String = "主播",
        title: String = "标题",
        endMs: Long = 1000L,
        bytes: Long = 1L,
    ) = RecordHistoryEntry(
        url = url, platform = platform, anchorName = anchor, title = title,
        savePath = "/tmp/$endMs-$anchor", endTimeMs = endMs, durationMs = 60_000,
        bytes = bytes, completed = true,
    )

    // ---- platformKeyOf ----

    @Test
    fun `中文平台名反查 PLATFORM_LABELS 键`() {
        assertEquals("douyin", RecordFilters.platformKeyOf(entry("l.douyin.com", platform = "抖音直播")))
        assertEquals("bilibili", RecordFilters.platformKeyOf(entry("", platform = "B站直播")))
        assertEquals("huya", RecordFilters.platformKeyOf(entry("x", platform = "虎牙直播")))
    }

    @Test
    fun `无平台名回落 URL 域名推断`() {
        assertEquals("kuaishou", RecordFilters.platformKeyOf(entry("https://live.kuaishou.com/u/a")))
        assertEquals("douyu", RecordFilters.platformKeyOf(entry("https://douyu.com/123")))
    }

    @Test
    fun `幽灵条目（无平台名无URL）返回空键不参与筛选`() {
        assertEquals("", RecordFilters.platformKeyOf(entry("", platform = "")))
    }

    @Test
    fun `旧补录记录从平台目录恢复 chip 且可以筛选`() {
        val legacy = entry("").copy(savePath = "/data/app/downloads/抖音直播/象棋刺客")
        assertEquals("douyin", RecordFilters.platformKeyOf(legacy))
        assertEquals(listOf("douyin"), RecordFilters.platformKeys(listOf(legacy)))
        assertEquals(listOf(legacy), RecordFilters.apply(listOf(legacy), 0, "douyin", ""))
        assertEquals(listOf(legacy), RecordFilters.apply(listOf(legacy), 0, null, "抖音"))
        assertEquals("huya", RecordFilters.platformKeyOf(
            entry("").copy(savePath = "/storage/videos/虎牙直播/主播/video.ts"),
        ))
    }

    @Test
    fun `平台英文键兼容旧记录`() {
        assertEquals("bilibili", RecordFilters.platformKeyOf(entry("", platform = "BILIBILI")))
    }

    // ---- platformKeys ----

    @Test
    fun `动态 chip 按记录数降序 平序按键名`() {
        val entries = listOf(
            entry("https://live.bilibili.com/1", platform = "B站直播"),
            entry("https://live.bilibili.com/2", platform = "B站直播"),
            entry("https://v.douyin.com/a", platform = "抖音直播"),
            entry("https://v.douyin.com/b", platform = "抖音直播"),
            entry("https://v.douyin.com/c", platform = "抖音直播"),
            entry("https://huya.com/d", platform = "虎牙直播"),
            entry("", platform = ""), // 空键排除
        )
        assertEquals(listOf("douyin", "bilibili", "huya"), RecordFilters.platformKeys(entries))
    }

    @Test
    fun `空记录返回空列表`() {
        assertTrue(RecordFilters.platformKeys(emptyList()).isEmpty())
    }

    // ---- apply ----

    @Test
    fun `时间段过滤取结束时间不早于起点`() {
        val list = listOf(entry("u", endMs = 500), entry("u", endMs = 1500))
        val out = RecordFilters.apply(list, rangeStartMs = 1000, platformKey = null, query = "")
        assertEquals(listOf(1500L), out.map { it.endTimeMs })
    }

    @Test
    fun `rangeStart 为 0 表示不限时间`() {
        val list = listOf(entry("u", endMs = 5), entry("u", endMs = 7))
        assertEquals(2, RecordFilters.apply(list, 0L, null, "").size)
    }

    @Test
    fun `平台键过滤含中文反查条目`() {
        val list = listOf(
            entry("https://v.douyin.com/a", platform = "抖音直播"),
            entry("https://live.bilibili.com/1", platform = "B站直播"),
        )
        val out = RecordFilters.apply(list, 0L, "douyin", "")
        assertEquals(1, out.size)
        assertEquals("抖音直播", out.first().platform)
    }

    @Test
    fun `搜索大小写不敏感且命中主播标题平台任一`() {
        val list = listOf(
            entry("u", anchor = "ABC主播"),
            entry("u", title = "游戏[xiaohongshu]实况"),
            entry("u", anchor = "无关"),
        )
        assertEquals(1, RecordFilters.apply(list, 0L, null, "abc").size)
        assertEquals(1, RecordFilters.apply(list, 0L, null, "XIAOHONGSHU").size)
    }

    @Test
    fun `多条件 AND 组合`() {
        val list = listOf(
            entry("https://v.douyin.com/a", platform = "抖音直播", endMs = 1500, anchor = "A"),
            entry("https://v.douyin.com/b", platform = "抖音直播", endMs = 500, anchor = "A"),
            entry("https://live.bilibili.com/1", platform = "B站直播", endMs = 1500, anchor = "A"),
        )
        val out = RecordFilters.apply(list, 1000, "douyin", "A")
        assertEquals(1, out.size)
        assertEquals(1500L, out.first().endTimeMs)
    }
}
