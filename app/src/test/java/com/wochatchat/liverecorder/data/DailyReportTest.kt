package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 8-8.1/8.2/8.3 数据统计纯逻辑单测。
 */
class DailyReportTest {

    private val day0 = 1_700_000_000_000L // 某天 00:00（基准）
    private val day1 = day0 + DailyReport.DAY_MS

    private fun entry(endMs: Long, bytes: Long, durationMs: Long = 60_000, completed: Boolean = true) =
        RecordHistoryEntry(
            url = "https://test/$endMs", platform = "douyin", anchorName = "A", title = "",
            savePath = "/tmp/$endMs", endTimeMs = endMs, durationMs = durationMs,
            bytes = bytes, completed = completed,
        )

    // ---- statsForDay ----

    @Test
    fun `统计某天场次时长大小与成功率`() {
        val entries = listOf(
            entry(day0 + 3600_000, 1000, 60_000, completed = true),
            entry(day0 + 7200_000, 2000, 30_000, completed = false),
            // 昨天的记录不计入
            entry(day0 - 3600_000, 999, 10_000),
            // 明天的不计入
            entry(day0 + DailyReport.DAY_MS + 1, 999, 10_000),
        )
        val stats = DailyReport.statsForDay(entries, day0)
        org.junit.Assert.assertEquals(2, stats.sessions)
        org.junit.Assert.assertEquals(3000L, stats.totalBytes)
        org.junit.Assert.assertEquals(90_000L, stats.totalDurationMs)
        org.junit.Assert.assertEquals(1, stats.completedCount)
        org.junit.Assert.assertEquals(0.5, stats.successRate, 1e-9)
    }

    @Test
    fun `空日期成功率为零`() {
        val stats = DailyReport.statsForDay(emptyList(), day0)
        org.junit.Assert.assertEquals(0, stats.sessions)
        org.junit.Assert.assertEquals(0.0, stats.successRate, 1e-9)
    }

    // ---- dailyBytes ----

    @Test
    fun `近7天每日用量含今天且最旧在前`() {
        val yesterday = day0 - DailyReport.DAY_MS
        val entries = listOf(
            entry(day0 + 100, 300),          // 今天
            entry(yesterday + 100, 200),     // 昨天
            entry(yesterday + 200, 100),     // 昨天（合计 300）
            entry(yesterday - DailyReport.DAY_MS + 100, 100), // 前天
        )
        val bytes = DailyReport.dailyBytes(entries, todayStartMs = day0, days = 3)
        org.junit.Assert.assertEquals(listOf(100L, 300L, 300L), bytes)
    }

    @Test
    fun `窗口对齐边界含起不含止`() {
        // 恰好等于次日 00:00 计入次日（新窗口），不计入当天
        val bytes = DailyReport.dailyBytes(listOf(entry(day0 + DailyReport.DAY_MS, 500)), day0, days = 2)
        org.junit.Assert.assertEquals(listOf(0L, 500L), bytes)
    }

    // ---- 带宽估算 ----

    @Test
    fun `码率反推`() {
        // 1000 字节 / 1000ms → 8 bytes*8/1s = 8 bps → 0.000008 Mbps
        org.junit.Assert.assertEquals(0.0, DailyReport.bitrateMbps(1000, 0), 1e-9)
        org.junit.Assert.assertEquals(0.0, DailyReport.bitrateMbps(0, -1), 1e-9)
        // 8 Mbps = 1 MB/s
        org.junit.Assert.assertEquals(
            8.0, DailyReport.bitrateMbps(1_000_000, 1_000), 1e-9,
        )
    }

    @Test
    fun `日均流量取窗口均值`() {
        val entries = listOf(
            entry(day0 + 10, 700),
            entry(day0 - DailyReport.DAY_MS + 10, 300),
        )
        // 近 7 天总量 1000 → 日均 142（1000/7 取整）
        org.junit.Assert.assertEquals(0L, DailyReport.dailyAvgBytes(emptyList(), day0, 7))
        org.junit.Assert.assertEquals(
            (700L + 300L) / 7,
            DailyReport.dailyAvgBytes(entries, day0, 7),
        )
    }
}
