package com.wochatchat.liverecorder.ui

/**
 * 5c 录制统计格式化（纯函数，供录制统计面板与状态行复用）。
 * 面板数据准确性验收的单测覆盖点。
 */
object StatsFormat {

    /** 时长：不足 1 小时 `MM:SS`，否则 `H:MM:SS`。 */
    fun duration(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** 字节数人性化：B / KB / MB / GB（十进制单位语义同文件管理器，1KB=1024B）。 */
    fun bytes(b: Long): String = when {
        b >= GB -> "%.1f GB".format(b / GB.toDouble())
        b >= MB -> "%.1f MB".format(b / MB.toDouble())
        b >= KB -> "%.0f KB".format(b / KB.toDouble())
        else -> "$b B"
    }

    /** 平均码率：bytes*8 / 时长；<1 Mbps 用 kbps，无效输入返回 "--"。 */
    fun bitrate(bytes: Long, durationMs: Long): String {
        if (bytes <= 0 || durationMs <= 0) return "--"
        val kbps = bytes * 8.0 / 1000.0 / (durationMs / 1000.0)
        return if (kbps >= 1000) "%.1f Mbps".format(kbps / 1000) else "%.0f kbps".format(kbps)
    }

    private const val KB = 1024L
    private const val MB = KB * 1024
    private const val GB = MB * 1024
}
