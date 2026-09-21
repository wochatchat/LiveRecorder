package com.wochatchat.liverecorder.monitor

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MonitorLoop 单测（JVM）：状态跃迁 / 错误容错 / 常量对齐。
 * Log 依赖 testOptions.unitTests.isReturnDefaultValues = true。
 */
class MonitorLoopTest {

    private fun liveInfo(anchor: String = "测试主播") = DouyinStreamInfo(
        anchorName = anchor, isLive = true, title = "测试标题", quality = "原画",
    )
    private fun offlineInfo() = DouyinStreamInfo(anchorName = "测试主播", isLive = false)

    @Test
    fun pollOnce_updatesStatesPerUrl() = runTest {
        val loop = MonitorLoop(check = { url ->
            if (url.endsWith("/live")) liveInfo() else offlineInfo()
        })
        val errors = loop.pollOnce({ listOf("https://a/live", "https://b/live", "https://c/off") })
        assertEquals(0, errors)
        val states = loop.states.value
        assertEquals(3, states.size)
        assertEquals("测试主播", (states["https://a/live"] as MonitorLoop.State.Live).anchorName)
        assertEquals("测试主播", (states["https://b/live"] as MonitorLoop.State.Live).anchorName)
        assertEquals(MonitorLoop.State.Offline, states["https://c/off"])
    }

    @Test
    fun pollOnce_errorKeepsLoopAlive() = runTest {
        var fail = true
        val loop = MonitorLoop(check = {
            if (fail) throw RuntimeException("network down")
            else liveInfo()
        })
        // 第一轮：检查抛异常 → Error 状态，返回错误计数 1
        assertEquals(1, loop.pollOnce({ listOf("u1") }))
        assertEquals("network down", (loop.states.value["u1"] as? MonitorLoop.State.Error)?.message)
        // 第二轮恢复：状态翻转成 Live
        fail = false
        assertEquals(0, loop.pollOnce({ listOf("u1") }))
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
    }

    @Test
    fun stop_clearsStates() = runTest {
        val loop = MonitorLoop(check = { liveInfo() })
        loop.pollOnce({ listOf("u") })
        assertTrue(loop.states.value.isNotEmpty())
        loop.stop()
        assertTrue(loop.states.value.isEmpty())
        assertFalse(loop.isRunning)
    }

    @Test
    fun constants_alignUpstreamConfig() {
        // 循环时间(秒) 默认 300，对齐上游 config.ini；抖动 ±5s（random.randint(-5, 5)）
        assertEquals(300L, MonitorLoop.DEFAULT_INTERVAL_SEC)
        assertEquals(5, MonitorLoop.JITTER_SEC)
        assertEquals(60L, MonitorLoop.ERROR_EXTRA_DELAY_SEC)
    }
}
