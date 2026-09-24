package com.wochatchat.liverecorder.monitor

import com.wochatchat.liverecorder.data.AppLog
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
 * - 存储阈值（2h）：每轮开头检查保存目录剩余空间（上游主循环 check_disk_capacity
 *   同位置），低于阈值进入暂停态——跳过轮询（不触发新的录制）并由上层停掉活动录制
 *   （等价上游 exit_recording）；空间恢复后自动继续（上游需手动重启，移动端体验改进）
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
    /** 存储充足返回 true（2h）。低于阈值时本轮流询跳过且不触发录制。 */
    private val storageOk: suspend () -> Boolean = { true },
    /** 进入低存储暂停态（仅状态切换时触发一次）：上层停活动录制 + 发通知。 */
    private val onLowStorage: () -> Unit = {},
    /** 存储恢复后自动继续监控（同样仅切换时触发一次）。 */
    private val onStorageResumed: () -> Unit = {},
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

    /** url → 连续检查失败轮数（4c 健康徽标）。 */
    private val consecutiveErrors = ConcurrentHashMap<String, Int>()

    /** 连续失败达阈值的 url 集合（4c 健康徽标，UI 置灰「失效」）。 */
    private val _unhealthy = MutableStateFlow<Set<String>>(emptySet())

    /** url → 最新监控状态。 */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    /** 不健康条目集合（4c：连续失败 ≥ [HEALTH_FAIL_THRESHOLD] 轮）。检查成功即恢复。 */
    val unhealthy: StateFlow<Set<String>> = _unhealthy.asStateFlow()

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
                val round = runRound(urls)
                if (round == null) {
                    // 低存储暂停：仍按正常间隔定期复查空间（恢复后自动继续）
                    delay(intervalSec * 1000)
                    continue
                }
                if (round.errors > 0) AppLog.w(TAG, "本轮检查错误 ${round.errors} 条")
                val jitter = (-JITTER_SEC..JITTER_SEC).random().coerceAtLeast(0)
                val roundSec = (System.currentTimeMillis() - t0) / 1000
                val delaySec = nextDelaySec(intervalSec, jitter, round.errors, round.recordJustEnded, roundSec)
                AppLog.d(TAG, "下一轮延迟 ${delaySec}s")
                delay(delaySec * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _states.value = emptyMap()
        _unhealthy.value = emptySet()
        wasRecording.clear()
        suppressed.clear()
        consecutiveErrors.clear()
        storagePaused = false
    }

    /** 手动停止录制后抑制该条目的自动重启，直到房间转为未开播。 */
    fun suppressAutoStart(url: String) {
        suppressed.add(url)
        AppLog.i(TAG, "抑制自动录制: $url")
    }

    /** 移除/改名条目后清理其监控状态、录制结束标记与抑制标记（2g）。 */
    fun forget(url: String) {
        _states.update { it - url }
        wasRecording.remove(url)
        suppressed.remove(url)
        consecutiveErrors.remove(url)
        _unhealthy.update { it - url }
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

    /** 低存储暂停态（2h）：存储低于阈值时为 true，恢复后自动清除。 */
    @Volatile
    private var storagePaused = false

    /** 单轮完整流程：存储检查（2h）→ 轮询。存储不足返回 null（本轮跳过）。 */
    internal suspend fun runRound(urls: suspend () -> List<String>): RoundResult? {
        val ok = try {
            storageOk()
        } catch (e: Exception) {
            AppLog.w(TAG, "存储检查失败，按充足处理: ${e.message}")
            true
        }
        if (!ok) {
            if (!storagePaused) {
                storagePaused = true
                AppLog.w(TAG, "存储空间低于阈值，暂停监控录制")
                onLowStorage()
            }
            return null
        }
        if (storagePaused) {
            storagePaused = false
            AppLog.i(TAG, "存储空间恢复，继续监控")
            onStorageResumed()
        }
        return pollOnce(urls)
    }

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
                        AppLog.i(TAG, "开播检测: $url → ${info.anchorName}「${info.title}」→ 启动录制")
                        onLive(url, info)
                    } else {
                        AppLog.i(TAG, "开播检测: $url（已抑制自动录制）")
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
            // 4c：检查成功清零连续失败计数；失败则 +1，达阈值标记为不健康
            if (next !is State.Error) {
                consecutiveErrors.remove(url)
                _unhealthy.update { it - url }
            } else {
                val n = (consecutiveErrors[url] ?: 0) + 1
                consecutiveErrors[url] = n
                if (n >= HEALTH_FAIL_THRESHOLD) _unhealthy.update { it + url }
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

        /** 4c 健康徽标阈值：连续失败 ≥3 轮（默认间隔 300s ≈ 15 分钟）标记为不健康置灰。 */
        const val HEALTH_FAIL_THRESHOLD = 3

        /** 录制刚结束后的快检间隔（秒），上游 `x = 30`。 */
        const val QUICK_CHECK_SEC = 30L

        /** 快检触发条件：录制结束时本轮耗时 <60s（上游 `count_time_end < 60`）。 */
        const val QUICK_CHECK_WINDOW_SEC = 60L
    }
}
