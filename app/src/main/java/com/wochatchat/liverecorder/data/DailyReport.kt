package com.wochatchat.liverecorder.data

import java.util.Calendar

/**
 * Phase 8-8.1/8.2/8.3 数据统计纯逻辑：
 * - 日报统计（8.1）：某天场次/总时长/总大小/成功率
 * - 近 N 天每日用量（8.2 趋势图数据源）
 * - 带宽估算（8.3）：码率反推 + 日均流量
 *
 * 全部纯函数：调用方持有 entries 与时间基准，便于单测。
 */
object DailyReport {

    /** 单日录制统计。 */
    data class DailyStats(
        val sessions: Int,
        val totalDurationMs: Long,
        val totalBytes: Long,
        val completedCount: Int,
    ) {
        /** 成功率（无录制时为 0）。 */
        val successRate: Double
            get() = if (sessions == 0) 0.0 else completedCount.toDouble() / sessions
    }

    /** 一天的毫秒数（统计窗口对齐用，不处理夏令时——国内无影响）。 */
    const val DAY_MS = 24 * 3_600_000L

    /** 本地时区当天 00:00 的毫秒时间戳。 */
    fun dayStartOf(nowMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 统计 [dayStartMs] 起一天内（本地日）结束的录制。 */
    fun statsForDay(entries: List<RecordHistoryEntry>, dayStartMs: Long): DailyStats {
        val inDay = entries.filter { it.endTimeMs >= dayStartMs && it.endTimeMs < dayStartMs + DAY_MS }
        return DailyStats(
            sessions = inDay.size,
            totalDurationMs = inDay.sumOf { it.durationMs },
            totalBytes = inDay.sumOf { it.bytes },
            completedCount = inDay.count { it.completed },
        )
    }

    /**
     * 近 [days] 天每日录制字节量（8.2 趋势图）。
     * 返回长度 days，index 0 = 最旧一天，末位 = 今天（含当天起止窗口）。
     */
    fun dailyBytes(entries: List<RecordHistoryEntry>, todayStartMs: Long, days: Int): List<Long> =
        (days - 1 downTo 0).map { offset ->
            val start = todayStartMs - offset * DAY_MS
            entries
                .filter { it.endTimeMs >= start && it.endTimeMs < start + DAY_MS }
                .sumOf { it.bytes }
        }

    /** 平均码率 Mbps（8.3）：bytes*8/durationMs；时长无效返回 0。 */
    fun bitrateMbps(bytes: Long, durationMs: Long): Double =
        if (durationMs <= 0) 0.0 else bytes * 8.0 / durationMs / 1_000_000.0

    /** 日均流量（8.3）：近 [days] 天总字节 / days；不足一天按整窗估算。 */
    fun dailyAvgBytes(entries: List<RecordHistoryEntry>, todayStartMs: Long, days: Int): Long {
        val bytes = dailyBytes(entries, todayStartMs, days).sum()
        return bytes / days.coerceAtLeast(1)
    }
}
