package com.wochatchat.liverecorder.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 5c 统计面板格式化：验收「面板数据准确」。 */
class StatsFormatTest {

    @Test
    fun `duration formats mmss and hss`() {
        assertEquals("00:00", StatsFormat.duration(0))
        assertEquals("00:59", StatsFormat.duration(59_999))
        assertEquals("12:34", StatsFormat.duration(754_000))
        assertEquals("1:02:03", StatsFormat.duration(3_723_000))
    }

    @Test
    fun `bytes humanizes by magnitude`() {
        assertEquals("0 B", StatsFormat.bytes(0))
        assertEquals("999 B", StatsFormat.bytes(999))
        assertEquals("1 KB", StatsFormat.bytes(1024))
        assertEquals("1.0 MB", StatsFormat.bytes(1024 * 1024))
        assertEquals("1.5 GB", StatsFormat.bytes(1536L * 1024 * 1024))
    }

    @Test
    fun `bitrate falls back to kbps and guards zero`() {
        assertEquals("--", StatsFormat.bitrate(0, 1000))
        assertEquals("--", StatsFormat.bitrate(1000, 0))
        // 2 MB / 2 s = 8 Mbps
        assertEquals("8.4 Mbps", StatsFormat.bitrate(2 * 1024 * 1024, 2_000))
        // 50 KB / 10 s = 4 kbps
        assertEquals("41 kbps", StatsFormat.bitrate(10240, 2_000))
    }
}
