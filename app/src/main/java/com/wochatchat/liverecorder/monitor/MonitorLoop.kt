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
 *
 * 录制结束后的 30s 快检（防主播卡顿少录）在 2c 接入录制调度时补齐。
 */
class MonitorLoop(
    /** 单条 URL 的开播检查。 */
    private val check: suspend (String) -> DouyinStreamInfo,
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
                val errors = pollOnce(urls)
                if (errors > 0) Log.w(TAG, "本轮检查错误 $errors 条")
                val jitter = (-JITTER_SEC..JITTER_SEC).random().coerceAtLeast(0)
                val extra = if (errors > ERROR_BURST_THRESHOLD) ERROR_EXTRA_DELAY_SEC else 0
                Log.d(TAG, "下一轮延迟 ${intervalSec + jitter + extra}s")
                delay((intervalSec + jitter + extra) * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _states.value = emptyMap()
    }

    /** 单轮检查：顺序检查全部 url，返回本轮错误条数。internal 便于单测注入。 */
    suspend fun pollOnce(urls: suspend () -> List<String>): Int {
        var errors = 0
        for (url in urls()) {
            val prev = _states.value[url]
            val next = try {
                val info = check(url)
                if (info.isLive) {
                    Log.i(TAG, "开播检测: $url → ${info.anchorName}「${info.title}」")
                    State.Live(info.anchorName, info.title)
                } else {
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
        return errors
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
    }
}
