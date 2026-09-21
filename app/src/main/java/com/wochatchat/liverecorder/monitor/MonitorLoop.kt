package com.wochatchat.liverecorder.monitor

import android.util.Log
import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 监控轮询循环（Phase 2-2b），语义对齐上游 main.py 主循环：
 *
 * - 每轮顺序检查全部监控 URL（上游每条 URL 一个协程循环 + 排队读取网址时间；
 *   Kotlin 侧条目量小，顺序轮询等价且实现简单）
 * - 轮询间隔 = 循环时间(秒)（config.ini 默认 300，代码兜底 120）+ 随机抖动 ±5s（上游
 *   `num = random.randint(-5, 5) + delay_default`，负数归 0）
 * - 单条检查失败不中断循环，记为该条目的 Error 状态
 * - 瞬时错误过多（>20/轮）时下一轮间隔 +60s（上游同语义）
 * - 录制中（[isRecording] 为 true）该条目跳过轮询：上游每条 url 一个协程、录制时
 *   阻塞在下载里天然不轮询；Kotlin 侧轮询是集中式，用跳过等价
 * - 录制结束（isRecording true→false）后：若本轮耗时 <60s，下一轮间隔降为 30s 快检
 *   （防主播卡顿少录，上游 record_finished 同语义），之后回归正常间隔
 * - 开播检测（且未在录制、未被抑制）时回调 [onLive] 触发录制；用户手动停止后进入
 *   抑制集，直到该房间转为未开播才恢复自动启动
 * - 开播/关播事件（2e）：状态切换时分别回调 [onLiveEvent]/[onOfflineEvent]（开播
 *   事件在抑制中也触发，通知不受抑制影响），供上层发 Android 通知
 */
class MonitorLoop(
    /** 单条 URL 的开播检查。 */
    private val check: suspend (String) -> DouyinStreamInfo,
    /** 该 url 是否正在录制（录制中暂停轮询该条目）。 */
    private val isRecording: (String) -> Boolean = { false },
    /** 检测到开播且未在录制时的回调（启动录制任务）。 */
    private val onLive: (String, DouyinStreamInfo) -> Unit = { _, _ -> },
    /** 房间转为直播中（状态切换即触发一次，无论是否被抑制；用于发开播通知）。 */
    private val onLiveEvent: (url: String, anchorName: String, title: String) -> Unit = { _, _, _ -> },
    /** 房间从直播中转为未开播（用于发关播通知）。 */
    private val onOfflineEvent: (url: String, anchorName: String) -> Unit = { _, _ -> },
) {

    sealed class State {
        /** 尚未检查（启动后首轮前）。 */
        data object Unknown : State()

        /** 直播中。 */
        data class Live(val anchorName: String, val title: String) : State()

        /** 未开播。 */
        data object Offline : State()

        /** 检查失败（网络/解析异常）。 */
        data class Error(val message: String) : State()
    }

    private var job: Job? = null
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** 上一轮观察到正在录制的 url（用于检测"录制刚结束"）。 */
    private val wasRecording: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 手动停止后抑制自动重启的 url，直到该房间转为未开播。 */
    private val suppressed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** url → 最新监控状态。 */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 启动轮询。已运行时幂等（避免重复循环）。
     * [urls] 每轮拉取最新监控列表快照，[intervalSec] 为循环时间（秒）。
     */
    fun start(scope: CoroutineScope, urls: suspend () -> List<String>, intervalSec: Long = DEFAULT_INTERVAL_SEC) {
        if (isRunning) return
        job = scope.launch {
            while (isActive) {
                val t0 = System.currentTimeMillis()
                val round = pollOnce(urls)
                if (round.errors > 0) Log.w(TAG, "本轮检查错误 ${round.errors} 条")
                val jitter = (-JITTER_SEC..JITTER_SEC).random().coerceAtLeast(0)
                val roundSec = (System.currentTimeMillis() - t0) / 1000
                val delaySec = nextDelaySec(intervalSec, jitter, round.errors, round.recordJustEnded, roundSec)
                Log.d(TAG, "下一轮延迟 ${delaySec}s")
                delay(delaySec * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _states.value = emptyMap()
        wasRecording.clear()
        suppressed.clear()
    }

    /** 手动停止录制后抑制该条目的自动重启，直到房间转为未开播。 */
    fun suppressAutoStart(url: String) {
        suppressed.add(url)
        Log.i(TAG, "抑制自动录制: $url")
    }

    /**
     * 计算下一轮间隔（秒），语义对齐上游 main.py：
     * 间隔 + 抖动；错误过多 +60s；录制刚结束且本轮耗时 <60s → 固定 30s 快检。
     */
    internal fun nextDelaySec(
        intervalSec: Long,
        jitter: Int,
        errors: Int,
        recordJustEnded: Boolean,
        roundDurationSec: Long,
    ): Long {
        if (recordJustEnded && roundDurationSec < QUICK_CHECK_WINDOW_SEC) return QUICK_CHECK_SEC
        val extra = if (errors > ERROR_BURST_THRESHOLD) ERROR_EXTRA_DELAY_SEC else 0
        return intervalSec + jitter + extra
    }

    /** 单轮检查结果。 */
    internal class RoundResult(
        /** 本轮检查错误条数。 */
        val errors: Int,
        /** 本轮是否有条目从录制中转为结束（触发下一轮 30s 快检）。 */
        val recordJustEnded: Boolean,
    )

    /** 单轮检查：顺序检查全部 url。internal 便于单测注入。 */
    internal suspend fun pollOnce(urls: suspend () -> List<String>): RoundResult {
        var errors = 0
        var recordJustEnded = false
        for (url in urls()) {
            if (isRecording(url)) {
                // 录制中：该条目暂停轮询（上游 per-url 协程阻塞在下载里的等价语义），
                // 状态保持不变（仍显示 Live）
                wasRecording.add(url)
                continue
            }
            if (wasRecording.remove(url)) recordJustEnded = true
            val prev = _states.value[url]
            val next = try {
                val info = check(url)
                if (info.isLive) {
                    // 开播事件：仅状态切换时触发一次（抑制中也不错过通知，2e）
                    if (prev !is State.Live) onLiveEvent(url, info.anchorName, info.title)
                    if (url !in suppressed) {
                        Log.i(TAG, "开播检测: $url → ${info.anchorName}「${info.title}」→ 启动录制")
                        onLive(url, info)
                    } else {
                        Log.i(TAG, "开播检测: $url（已抑制自动录制）")
                    }
                    State.Live(info.anchorName, info.title)
                } else {
                    suppressed.remove(url) // 转为未开播后恢复该条目的自动启动
                    // 关播事件：仅直播中→未开播的切换触发（2e）
                    if (prev is State.Live) onOfflineEvent(url, prev.anchorName)
                    State.Offline
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors++
                State.Error(e.message ?: e.javaClass.simpleName)
            }
            _states.update { it + (url to next) }
        }
        return RoundResult(errors, recordJustEnded)
    }

    companion object {
        private const val TAG = "MonitorLoop"

        /** 循环时间(秒)，对齐上游 config.ini 默认 300（代码兜底 120）。 */
        const val DEFAULT_INTERVAL_SEC = 300L

        /** 间隔随机抖动幅度（秒），上游 `random.randint(-5, 5)`。 */
        const val JITTER_SEC = 5

        /** 瞬时错误阈值（超过则下轮 +60s，上游 error_count > 20 同语义）。 */
        const val ERROR_BURST_THRESHOLD = 20

        const val ERROR_EXTRA_DELAY_SEC = 60L

        /** 录制刚结束后的快检间隔（秒），上游 `x = 30`。 */
        const val QUICK_CHECK_SEC = 30L

        /** 快检触发条件：录制结束时本轮耗时 <60s（上游 `count_time_end < 60`）。 */
        const val QUICK_CHECK_WINDOW_SEC = 60L
    }
}
