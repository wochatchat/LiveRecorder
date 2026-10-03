package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5-5.1：平台健康仪表盘数据层单测
 * （环形缓冲裁剪 / 7 天窗口统计 / JSON 往返 / 损坏容错）。
 */
class CheckResultHistoryTest {

    private val hour = 3600_000L
    private val day = 24 * hour

    @Test
    fun `追加后保留插入顺序且时间戳升序`() {
        var h = CheckResultHistory.append(emptyMap(), "douyin", ts = 1000L, ok = true)
        h = CheckResultHistory.append(h, "douyin", ts = 2000L, ok = false)
        h = CheckResultHistory.append(h, "douyin", ts = 3000L, ok = true)

        val list = h["douyin"]!!
        assertEquals(3, list.size)
        assertEquals(listOf(1000L, 2000L, 3000L), list.map { it.ts })
        assertEquals(listOf(true, false, true), list.map { it.ok })
    }

    @Test
    fun `环形缓冲最多保留100条`() {
        var h: Map<String, List<CheckResultEntry>> = emptyMap()
        val base = 100_000_000L
        for (i in 0 until 120) {
            h = CheckResultHistory.append(h, "huya", ts = base + i * 1000L, ok = true)
        }
        val list = h["huya"]!!
        assertEquals(CheckResultHistory.MAX_PER_PLATFORM, list.size)
        // 保留的是最新 100 条
        assertEquals(base + 20 * 1000L, list.first().ts)
        assertEquals(base + 119 * 1000L, list.last().ts)
    }

    @Test
    fun `追加时剪掉7天窗口外的过期条目`() {
        val now = 10L * day
        var h = CheckResultHistory.append(emptyMap(), "douyu", ts = now - 8 * day, ok = false)
        h = CheckResultHistory.append(h, "douyu", ts = now - 1 * day, ok = true)
        h = CheckResultHistory.append(h, "douyu", ts = now, ok = true)

        val list = h["douyu"]!!
        assertEquals(2, list.size) // 8 天前那条被剪掉
        assertTrue(list.all { it.ok })
    }

    @Test
    fun `平台之间互不干扰`() {
        var h = CheckResultHistory.append(emptyMap(), "douyin", ts = 1000L, ok = true)
        h = CheckResultHistory.append(h, "huya", ts = 2000L, ok = false)

        assertEquals(1, h["douyin"]!!.size)
        assertEquals(1, h["huya"]!!.size)
        assertEquals(false, h["huya"]!![0].ok)
    }

    @Test
    fun `统计只计入7天窗口内条目`() {
        val now = 10L * day
        val entries = listOf(
            CheckResultEntry(now - 8 * day, false), // 窗口外，不计
            CheckResultEntry(now - 3 * day, true),
            CheckResultEntry(now - 2 * day, true),
            CheckResultEntry(now - 1 * day, false),
            CheckResultEntry(now, true),
        )
        val stats = CheckResultHistory.stats(entries, now)
        assertEquals(4, stats.total)
        assertEquals(3, stats.ok)
        assertEquals(0.75, stats.successRate, 1e-9)
    }

    @Test
    fun `空列表成功率为0不崩溃`() {
        val stats = CheckResultHistory.stats(emptyList(), nowMs = 1000L)
        assertEquals(0, stats.total)
        assertEquals(0.0, stats.successRate, 1e-9)
    }

    @Test
    fun `JSON往返无损`() {
        var h = CheckResultHistory.append(emptyMap(), "douyin", ts = 1000L, ok = true)
        h = CheckResultHistory.append(h, "douyin", ts = 2000L, ok = false)
        h = CheckResultHistory.append(h, "bilibili", ts = 3000L, ok = true)

        val restored = CheckResultHistory.fromJson(CheckResultHistory.toJson(h))
        assertEquals(h, restored)
    }

    @Test
    fun `损坏JSON返回空map`() {
        assertEquals(emptyMap<String, List<CheckResultEntry>>(), CheckResultHistory.fromJson("not-json{"))
        assertEquals(emptyMap<String, List<CheckResultEntry>>(), CheckResultHistory.fromJson(""))
    }
}
