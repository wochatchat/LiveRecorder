package com.wochatchat.liverecorder.service

import com.wochatchat.liverecorder.data.PLATFORM_PACKAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3-4 R3（降级方案）：各平台分享路径指引映射测试。 */
class FloatingBallProbeHintTest {

    @Test
    fun `所有支持前台探测的平台都有指引文案`() {
        // R2 包名映射的全部平台键必须命中专属指引（不允许落到 fallback）
        val fallback = FloatingBallService.probeHintRes("___none___")
        PLATFORM_PACKAGES.values.distinct().forEach { key ->
            assertTrue(
                "平台 $key 缺少分享指引",
                FloatingBallService.probeHintRes(key) != fallback,
            )
        }
    }

    @Test
    fun `未知平台回落通用文案`() {
        assertEquals(
            FloatingBallService.probeHintRes("unknown_app"),
            FloatingBallService.probeHintRes(""),
        )
    }

    @Test
    fun `映射键与 R2 平台键系一致`() {
        // 抽查：键名对齐 FgPlatform 的平台键（PLATFORM_COLORS/PLATFORM_LABELS 键系）
        val expected = setOf("douyin", "kuaishou", "xiaohongshu", "bilibili", "huya", "douyu", "yy")
        assertTrue(PLATFORM_PACKAGES.values.toSet() == expected)
    }
}
