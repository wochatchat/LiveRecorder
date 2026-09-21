package com.wochatchat.liverecorder.monitor

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MonitorLoop 单测（JVM）：状态跃迁 / 错误容错 / 常量对齐 / 2c 录制调度。
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
        val round = loop.pollOnce({ listOf("https://a/live", "https://b/live", "https://c/off") })
        assertEquals(0, round.errors)
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
        assertEquals(1, loop.pollOnce({ listOf("u1") }).errors)
        assertEquals("network down", (loop.states.value["u1"] as? MonitorLoop.State.Error)?.message)
        // 第二轮恢复：状态翻转成 Live
        fail = false
        assertEquals(0, loop.pollOnce({ listOf("u1") }).errors)
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
        assertEquals(30L, MonitorLoop.QUICK_CHECK_SEC)
        assertEquals(30L, MonitorLoop.QUICK_CHECK_SEC)
        assertEquals(60L, MonitorLoop.QUICK_CHECK_WINDOW_SEC)
    }

    @Test
    fun pollOnce_skipsRecordingUrls() = runTest {
        val recording = mutableSetOf("u1")
        val checked = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { url -> checked.add(url); liveInfo() },
            isRecording = { it in recording },
        )
        val round = loop.pollOnce({ listOf("u1", "u2") })
        // 录制中的条目不检查、不更新状态
        assertEquals(listOf("u2"), checked)
        assertEquals(0, round.errors)
        assertFalse(round.recordJustEnded)
        assertTrue(loop.states.value["u1"] == null)
        assertTrue(loop.states.value["u2"] is MonitorLoop.State.Live)
    }

    @Test
    fun pollOnce_onLiveFiresAndRecordEndDetected() = runTest {
        val recording = mutableSetOf<String>()
        val liveUrls = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { liveInfo() },
            isRecording = { it in recording },
            onLive = { url, info -> liveUrls.add("$url|${info.anchorName}") },
        )
        // 开播 → onLive 触发，未标记快检
        var round = loop.pollOnce({ listOf("u1") })
        assertEquals(0, round.errors)
        assertFalse(round.recordJustEnded)
        assertEquals(listOf("u1|测试主播"), liveUrls)
        // 模拟录制开始：暂停轮询，onLive 不再触发
        recording.add("u1")
        round = loop.pollOnce({ listOf("u1") })
        assertEquals(1, liveUrls.size)
        assertFalse(round.recordJustEnded)
        // 录制结束：下一轮检测到刚结束，恢复轮询并再次触发 onLive
        recording.remove("u1")
        round = loop.pollOnce({ listOf("u1") })
        assertTrue(round.recordJustEnded)
        assertEquals(listOf("u1|测试主播", "u1|测试主播"), liveUrls)
    }

    @Test
    fun pollOnce_suppressAutoStartUntilOffline() = runTest {
        val live = mutableListOf<DouyinStreamInfo>()
        var online = true
        val loop = MonitorLoop(
            check = { if (online) liveInfo() else offlineInfo() },
            onLive = { _, info -> live.add(info) },
        )
        loop.suppressAutoStart("u1")
        // 抑制中：状态仍为 Live，但不触发 onLive
        loop.pollOnce({ listOf("u1") })
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
        assertTrue(live.isEmpty())
        // 关播后抑制解除
        online = false
        loop.pollOnce({ listOf("u1") })
        assertEquals(MonitorLoop.State.Offline, loop.states.value["u1"])
        online = true
        loop.pollOnce({ listOf("u1") })
        assertEquals(1, live.size)
    }

    @Test
    fun nextDelaySec_alignsUpstreamQuickCheck() {
        val loop = MonitorLoop(check = { offlineInfo() })
        // 正常轮：间隔 + 抖动
        assertEquals(305L, loop.nextDelaySec(300L, jitter = 5, errors = 0, recordJustEnded = false, roundDurationSec = 10))
        // 错误过多 +60s
        assertEquals(365L, loop.nextDelaySec(300L, jitter = 5, errors = 21, recordJustEnded = false, roundDurationSec = 10))
        // 录制刚结束且本轮 <60s → 固定 30s（覆盖错误加时，上游同语义）
        assertEquals(30L, loop.nextDelaySec(300L, jitter = 5, errors = 100, recordJustEnded = true, roundDurationSec = 59))
        // 录制结束但本轮耗时 >=60s → 正常间隔
        assertEquals(305L, loop.nextDelaySec(300L, jitter = 5, errors = 0, recordJustEnded = true, roundDurationSec = 60))
    }

    // ---- 2e：开播/关播事件（仅状态切换触发一次） ----

    @Test
    fun pollOnce_liveEventFiresOnlyOnTransition() = runTest {
        val events = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { liveInfo() },
            onLiveEvent = { url, anchor, title -> events.add("$url|$anchor|$title") },
        )
        loop.pollOnce({ listOf("u1") })
        assertEquals(listOf("u1|测试主播|测试标题"), events)
        // 第二轮仍直播：不重复触发
        loop.pollOnce({ listOf("u1") })
        assertEquals(1, events.size)
    }

    @Test
    fun pollOnce_liveEventFiresEvenWhenSuppressed() = runTest {
        val events = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { liveInfo() },
            onLiveEvent = { url, anchor, _ -> events.add("$url|$anchor") },
        )
        loop.suppressAutoStart("u1")
        loop.pollOnce({ listOf("u1") })
        // 抑制自动录制不影响开播通知
        assertEquals(listOf("u1|测试主播"), events)
    }

    @Test
    fun pollOnce_offlineEventFiresOnLiveToOffline() = runTest {
        var online = true
        val offlineEvents = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { if (online) liveInfo() else offlineInfo() },
            onOfflineEvent = { url, anchor -> offlineEvents.add("$url|$anchor") },
        )
        // 首轮未开播：无关播通知（Unknown→Offline 不是切换）
        loop.pollOnce({ listOf("u1") })
        assertTrue(offlineEvents.isEmpty())
        online = true
        loop.pollOnce({ listOf("u1") })
        online = false
        loop.pollOnce({ listOf("u1") })
        assertEquals(listOf("u1|测试主播"), offlineEvents)
    }

    @Test
    fun pollOnce_offlineEventFiresAfterRecordEnd() = runTest {
        val recording = mutableSetOf<String>()
        var online = true
        val offlineEvents = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { if (online) liveInfo() else offlineInfo() },
            isRecording = { it in recording },
            onOfflineEvent = { url, anchor -> offlineEvents.add("$url|$anchor") },
        )
        loop.pollOnce({ listOf("u1") })
        recording.add("u1")
        // 录制中关播：该条目暂停轮询，状态保持 Live，不触发关播通知
        loop.pollOnce({ listOf("u1") })
        assertTrue(offlineEvents.isEmpty())
        // 录制结束下一轮检测：关播通知触发
        recording.remove("u1")
        online = false
        loop.pollOnce({ listOf("u1") })
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Offline)
        assertEquals(listOf("u1|测试主播"), offlineEvents)
    }

    // ---- 2g：移除/改名条目后的状态清理 ----

    @Test
    fun forget_clearsStateAndSuppression() = runTest {
        val live = mutableListOf<String>()
        var online = true
        val loop = MonitorLoop(
            check = { if (online) liveInfo() else offlineInfo() },
            onLive = { url, _ -> live.add(url) },
        )
        // 建立状态 + 抑制标记
        loop.suppressAutoStart("u1")
        loop.pollOnce({ listOf("u1", "u2") })
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
        // forget 后：状态、抑制标记全部清除
        loop.forget("u1")
        assertEquals(null, loop.states.value["u1"])
        // 再轮询：抑制已解除，onLive 正常触发（等价于重新添加该条目）
        online = true
        loop.pollOnce({ listOf("u1") })
        // u2 首轮（未抑制）已触发过，u1 在 forget 清除抑制后重新触发
        assertEquals(listOf("u2", "u1"), live)
    }

    @Test
    fun forget_clearsRecordEndTracking() = runTest {
        val recording = mutableSetOf("u1")
        val loop = MonitorLoop(
            check = { liveInfo() },
            isRecording = { it in recording },
        )
        // 录制中：进入 wasRecording 集合
        loop.pollOnce({ listOf("u1") })
        // 移除条目后：录制结束标记一并清理，不再触发快检
        loop.forget("u1")
        // 模拟录制也已停止（ forget 后该条目已不在录制链路里）
        recording.remove("u1")
        val round = loop.pollOnce({ listOf("u1") })
        assertFalse(round.recordJustEnded)
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
    }

    // ---- 2h：存储阈值暂停/恢复 ----

    @Test
    fun runRound_storageLowPausesAndResumes() = runTest {
        var ok = false
        val lowEvents = mutableListOf<Int>()
        val resumedEvents = mutableListOf<Int>()
        val checked = mutableListOf<String>()
        val loop = MonitorLoop(
            check = { url -> checked.add(url); liveInfo() },
            storageOk = { ok },
            onLowStorage = { lowEvents.add(1) },
            onStorageResumed = { resumedEvents.add(1) },
        )
        // 空间不足：本轮流询跳过，不检查任何条目，触发一次低存储回调
        assertEquals(null, loop.runRound({ listOf("u1") }))
        assertTrue(loop.states.value.isEmpty())
        assertTrue(checked.isEmpty())
        assertEquals(1, lowEvents.size)
        // 持续不足：不重复触发
        assertEquals(null, loop.runRound({ listOf("u1") }))
        assertEquals(1, lowEvents.size)
        // 恢复：轮询继续，恢复回调触发一次
        ok = true
        assertTrue(loop.runRound({ listOf("u1") }) != null)
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
        assertEquals(1, resumedEvents.size)
        // 后续正常轮：不再重复触发恢复回调
        loop.runRound({ listOf("u1") })
        assertEquals(1, resumedEvents.size)
        assertEquals(1, lowEvents.size)
    }

    @Test
    fun runRound_storageCheckErrorTreatedAsOk() = runTest {
        val loop = MonitorLoop(
            check = { liveInfo() },
            storageOk = { throw RuntimeException("storage probe failed") },
        )
        // 存储探测异常时按充足处理（不因检查失败卡死监控）
        val round = loop.runRound({ listOf("u1") })
        assertTrue(round != null)
        assertTrue(loop.states.value["u1"] is MonitorLoop.State.Live)
    }
}
