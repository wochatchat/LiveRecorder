package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 录制状态机（Phase 2-2d：断流自动重连）。
 *
 * 流程对齐上游 main.py start_record 的 per-url `while True` 循环：
 * 解析直播源 → select_source_url → direct_download_stream 落盘；
 * 下载中断/流提前结束后回到循环顶重新探测，仍开播则重录（上游靠 30s 快检，
 * 本实现按 phase-2 规格改为指数退避快速重试，单段录制满 [SEGMENT_RESET_MS] 后计数清零）。
 *
 * 与上游的差异：
 * - 重连时重新解析直播源（旧流地址可能已失效），每次重连落到新分段文件
 *   （上游每轮循环同样生成新时间戳文件）
 * - 指数退避 2s×2^n 封顶 60s，连续 [MAX_RECONNECT_ATTEMPTS] 次失败放弃（上游固定
 *   循环间隔 300s 重试，移动端改为快速退避）
 * - HLS(m3u8) 源需 ffmpeg（Phase 3），当前仅支持 FLV 直下，h265-only 房间报不支持
 * - 文件命名：{baseDir}/抖音直播/{主播}/{主播}_{时间戳}.flv
 */
class RecordController(
    private val baseDir: File,
    private val downloader: StreamDownloader = StreamDownloader(),
    private val fetchInfo: suspend (String) -> DouyinStreamInfo = { DouyinSpider().fetchStreamInfo(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** 等待注入点（单测收集退避延迟，生产即 delay）。 */
    internal val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    sealed class RecordState {
        /** 正在解析直播源。 */
        data object Resolving : RecordState()

        /** 录制中。[bytes] 当前分段已写字节数，[savePath] 落盘路径。 */
        data class Recording(val savePath: String, val bytes: Long, val quality: String) : RecordState()

        /** 断流重连中：第 [attempt] 次重试前等待 [nextDelaySec] 秒。[message] 为中断原因。 */
        data class Reconnecting(val attempt: Int, val nextDelaySec: Long, val message: String) : RecordState()

        /** 已结束（下载完成或手动停止，文件保留）。 */
        data class Finished(val savePath: String, val bytes: Long, val completed: Boolean) : RecordState()

        /** 失败（未开播 / 无流 / 下载错误 / 重连次数耗尽）。 */
        data class Failed(val message: String) : RecordState()
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, RecordState>>(emptyMap())

    /** 每个 url 的最新状态快照。 */
    val states: StateFlow<Map<String, RecordState>> = _states.asStateFlow()

    /** 是否正在录制该 url（解析中 / 录制中 / 重连中）。 */
    fun isActive(url: String): Boolean = jobs.containsKey(url)

    fun start(url: String) {
        if (isActive(url)) return
        val job = scope.launch { runRecord(url) }
        jobs[url] = job
        job.invokeOnCompletion { jobs.remove(url) }
    }

    fun stop(url: String) {
        jobs.remove(url)?.cancel()
    }

    internal suspend fun runRecord(url: String) {
        var attempt = 0
        var totalBytes = 0L
        var lastPath = ""
        var lastBytes = 0L
        var hadContent = false
        try {
            while (true) {
                setState(url, RecordState.Resolving)
                val info: DouyinStreamInfo = try {
                    fetchInfo(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (attempt == 0) {
                        setState(url, RecordState.Failed("解析直播源失败: ${e.message}"))
                        return
                    }
                    // 重连阶段的探测失败（网络整体断开）同样计入退避
                    if (!backoffOrGiveUp(url, ++attempt, "解析失败: ${e.message}")) return
                    continue
                }
                if (!info.isLive) {
                    setState(
                        url,
                        if (attempt > 0) RecordState.Finished(lastPath, totalBytes, completed = true)
                        else RecordState.Failed("未在直播"),
                    )
                    return
                }
                // 路径 A 仅支持 FLV 直下（上游 direct_download_stream 分支）；HLS 需 ffmpeg（Phase 3）
                val sourceUrl = RecordSource.selectSourceUrl(url, info.flvUrl, info.recordUrl)
                if (sourceUrl.isNullOrEmpty()) {
                    setState(url, RecordState.Failed("未获取到直播流地址"))
                    return
                }
                if (sourceUrl.contains(".m3u8")) {
                    setState(url, RecordState.Failed("HLS(m3u8) 源需 ffmpeg 支持（Phase 3）"))
                    return
                }

                val anchor = RecordSource.cleanName(info.anchorName)
                val now = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val dir = File(File(baseDir, PLATFORM_DIR), anchor)
                val saveFile = File(dir, "${anchor}_$now.flv")
                setState(url, RecordState.Recording(saveFile.absolutePath, 0, info.quality))

                val headers = RecordSource.getRecordHeaders(platformName(url), url)
                    ?.let { mapOf(it.first to it.second) }
                    ?: emptyMap()
                var lastUpdate = 0L
                val segStart = System.currentTimeMillis()
                var segBytes = 0L
                val ok = downloader.download(
                    sourceUrl = sourceUrl,
                    saveFile = saveFile,
                    headers = headers,
                ) { bytes ->
                    segBytes = bytes
                    lastBytes = totalBytes + bytes
                    val t = System.currentTimeMillis()
                    if (t - lastUpdate >= PROGRESS_INTERVAL_MS) {
                        lastUpdate = t
                        setState(url, RecordState.Recording(saveFile.absolutePath, totalBytes + bytes, info.quality))
                    }
                }
                lastPath = saveFile.absolutePath
                totalBytes += segBytes
                val segMs = System.currentTimeMillis() - segStart
                if (ok || segMs >= SEGMENT_RESET_MS) attempt = 0

                if (!backoffOrGiveUp(url, ++attempt, if (ok) "直播流提前结束" else "下载中断")) return
            }
        } catch (e: CancellationException) {
            // 用户主动停止：半截文件保留（上游同语义）
            setState(url, RecordState.Finished(lastPath, lastBytes, completed = false))
            throw e
        } catch (e: Exception) {
            setState(url, RecordState.Failed("录制异常: ${e.message}"))
        }
    }

    /**
     * 断流后的退避决策：置 Reconnecting 并等待 [reconnectDelaySec] 后重试；
     * 连续失败超过 [MAX_RECONNECT_ATTEMPTS] 次则放弃（Failed）。返回 false 表示已放弃。
     */
    private suspend fun backoffOrGiveUp(url: String, attempt: Int, message: String): Boolean {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            setState(url, RecordState.Failed("断流重连失败（已重试 $MAX_RECONNECT_ATTEMPTS 次），已保留已录文件"))
            return false
        }
        val delaySec = reconnectDelaySec(attempt)
        setState(url, RecordState.Reconnecting(attempt, delaySec, message))
        sleep(delaySec * 1000)
        return true
    }

    /** 指数退避：2s, 4s, 8s, 16s, 32s, … 封顶 [MAX_BACKOFF_SEC]。 */
    internal fun reconnectDelaySec(attempt: Int): Long =
        (BASE_BACKOFF_SEC shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_SEC)

    private fun platformName(url: String): String = "抖音直播"

    private fun setState(url: String, state: RecordState) {
        _states.update { it + (url to state) }
    }

    private companion object {
        /** 平台目录名（上游 full_path 拼接的 platform，抖音='抖音直播'）。 */
        const val PLATFORM_DIR = "抖音直播"

        /** 进度回调节流间隔（ms）。 */
        const val PROGRESS_INTERVAL_MS = 500L

        /** 重连退避基数（秒）：第 n 次重试等待 2×2^(n-1) 秒。 */
        const val BASE_BACKOFF_SEC = 2L

        /** 重连退避上限（秒）。 */
        const val MAX_BACKOFF_SEC = 60L

        /** 连续重试上限（超出后放弃录制；单段录满 SEGMENT_RESET_MS 后计数清零）。 */
        const val MAX_RECONNECT_ATTEMPTS = 5

        /** 单段录制满此时长后重试计数清零（防止短命分段无限快速循环）。 */
        const val SEGMENT_RESET_MS = 60_000L
    }
}
