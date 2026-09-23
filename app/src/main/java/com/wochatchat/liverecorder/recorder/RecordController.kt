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
 * 录制状态机（Phase 2-2d：断流自动重连；Phase 3-3g：ffmpeg 分段录制）。
 *
 * 流程对齐上游 main.py start_record 的 per-url `while True` 循环：
 * 解析直播源 → select_source_url → ffmpeg 分段录制（3-3g）或 OkHttp 直下（Phase 1）。
 *
 * 与上游的差异：
 * - 重连时重新解析直播源（旧流地址可能已失效），每次重连落到新分片
 *   （上游每轮循环同样生成新时间戳文件）
 * - 指数退避 2s×2^n 封顶 60s，连续 [MAX_RECONNECT_ATTEMPTS] 次失败放弃
 * - ffmpeg 可用时：HLS(m3u8) + FLV 均走 ffmpeg 分段录制（3-3g）；无 ffmpeg 时
 *   回退 OkHttp 直下（Phase 1/2 行为）
 * - 文件命名：{baseDir}/{平台目录}/{主播}/{主播}_{时间戳}.flv（3e：斗鱼=斗鱼直播，其余=抖音直播）
 */
class RecordController(
    private val baseDir: File,
    private val downloader: StreamDownloader = StreamDownloader(),
    private val fetchInfo: suspend (String) -> DouyinStreamInfo = { DouyinSpider().fetchStreamInfo(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * FFmpeg 分段录制引擎（Phase 3-3g）。
     * 传入 [FfmpegRecorder] 实例时，HLS(m3u8) 和 FLV 均走 ffmpeg 分段录制；
     * 传 null 时回退 OkHttp 直下（Phase 1/2 行为）。
     */
    private val ffmpeg: FfmpegRecorder? = null,
    /**
     * 3-3h：录制完成后自动转 MP4 开关（对齐上游 config.ini「录制完成后自动转为mp4格式」，默认关）。
     * ffmpeg 分段会话结束时查询；仅对 TS 分片生效（上游 save_type == 'TS' 同语义）。
     */
    private val mp4Convert: suspend () -> Boolean = { false },
    /** 等待注入点（单测收集退避延迟，生产即 delay）。 */
    internal val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    sealed class RecordState {
        /** 正在解析直播源。 */
        data object Resolving : RecordState()

        /**
         * 录制中。[savePath] 落盘路径（OkHttp 单文件模式为具体文件；
         * ffmpeg 分段模式为目录路径）；[bytes] 累计字节数；[quality] 画质。
         */
        data class Recording(val savePath: String, val bytes: Long, val quality: String) : RecordState()

        /** 断流重连中：第 [attempt] 次重试前等待 [nextDelaySec] 秒。[message] 为中断原因。 */
        data class Reconnecting(val attempt: Int, val nextDelaySec: Long, val message: String) : RecordState()

        /** 已结束（直播结束或手动停止，文件保留）。 */
        data class Finished(val savePath: String, val bytes: Long, val completed: Boolean) : RecordState()

        /** 失败（未开播 / 无流 / 下载错误 / 重连次数耗尽）。 */
        data class Failed(val message: String) : RecordState()
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, RecordState>>(emptyMap())

    /** 每个 url 的最新状态快照。 */
    val states: StateFlow<Map<String, RecordState>> = _states.asStateFlow()

    /** 是否正在录制该 url。 */
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

    /** 停止全部录制任务（Phase 2-2h 存储阈值触底，等价上游 exit_recording）。 */
    fun stopAll() {
        jobs.keys.toList().forEach { stop(it) }
    }

    internal suspend fun runRecord(url: String) {
        var attempt = 0
        var totalBytes = 0L
        var lastPath = ""
        var lastBytes = 0L

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

                val sourceUrl = RecordSource.selectSourceUrl(url, info.flvUrl, info.recordUrl)
                if (sourceUrl.isNullOrEmpty()) {
                    setState(url, RecordState.Failed("未获取到直播流地址"))
                    return
                }

                val anchor = RecordSource.cleanName(info.anchorName)
                val dir = File(File(baseDir, PLATFORM_DIR), anchor)
                // ffmpeg 不会创建输出目录（OkHttp 路径由 StreamDownloader mkdirs），首次录制需先建
                dir.mkdirs()
                val headers = RecordSource.getRecordHeaders(platformName(url), url)
                    ?.let { mapOf(it.first to it.second) }
                    ?: emptyMap()

                if (ffmpeg != null) {
                    // ffmpeg 分段录制（3-3g）：m3u8 必须走 ffmpeg；FLV 也走 ffmpeg
                    val res = ffmpeg.record(
                        sourceUrl = sourceUrl,
                        outputDir = dir,
                        headers = headers,
                        anchorName = anchor,
                    ) { bytes ->
                        setState(url, RecordState.Recording(dir.absolutePath, totalBytes + bytes, info.quality))
                    }
                    lastPath = dir.absolutePath
                    totalBytes += res.estimatedBytes
                    convertSegmentsAsync(res.segments)
                    // ++attempt 留下 attempt=1：下轮探测已关播时按 Finished(completed) 收敛（同 OkHttp 分支语义）
                    if (!backoffOrGiveUp(url, ++attempt, "直播流结束")) return
                } else {
                    // OkHttp 直下（Phase 1/2 行为）
                    if (sourceUrl.contains(".m3u8")) {
                        setState(url, RecordState.Failed("HLS(m3u8) 源需 ffmpeg 支持（Phase 3）"))
                        return
                    }
                    val now = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                    val saveFile = File(dir, "${anchor}_$now.flv")
                    setState(url, RecordState.Recording(saveFile.absolutePath, 0, info.quality))

                    var lastUpdate = 0L
                    val segStart = System.currentTimeMillis()
                    var written = 0L
                    val ok = downloader.download(sourceUrl, saveFile, headers) { bytes ->
                        written = bytes
                        val t = System.currentTimeMillis()
                        if (t - lastUpdate >= PROGRESS_INTERVAL_MS) {
                            lastUpdate = t
                            setState(url, RecordState.Recording(saveFile.absolutePath, totalBytes + bytes, info.quality))
                        }
                    }
                    lastPath = saveFile.absolutePath
                    totalBytes += written
                    val segMs = System.currentTimeMillis() - segStart
                    if (ok || segMs >= SEGMENT_RESET_MS) attempt = 0
                    if (!backoffOrGiveUp(url, ++attempt, if (ok) "直播流结束" else "下载中断")) return
                }
            }
        } catch (e: CancellationException) {
            setState(url, RecordState.Finished(lastPath, lastBytes, completed = false))
            throw e
        } catch (e: Exception) {
            setState(url, RecordState.Failed("录制异常: ${e.message}"))
        }
    }

    /**
     * 3-3h：会话分片后台转 MP4（fire-and-forget，对齐上游 threading.Thread 不阻塞重连）。
     * 开关关闭或无 TS 分片时不做任何事；单个分片失败不影响其余。
     */
    private fun convertSegmentsAsync(segments: List<File>) {
        if (segments.none { it.name.endsWith(".ts") }) return
        scope.launch {
            val enabled = try { mp4Convert() } catch (e: Exception) { false }
            if (!enabled) return@launch
            // 上游仅对 TS 保存类型转 mp4（main.py:454 `converts_to_mp4 and save_type == 'TS'`）
            segments.filter { it.name.endsWith(".ts") }.forEach { seg ->
                try {
                    ffmpeg?.remuxToMp4(seg, deleteOriginal = true)
                } catch (e: Exception) {
                    println("TS→MP4 转换失败 (${seg.name}): ${e.message}")
                }
            }
        }
    }

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

    internal fun reconnectDelaySec(attempt: Int): Long =
        (BASE_BACKOFF_SEC shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_SEC)

    private fun platformName(url: String): String = if (url.contains("douyu.com/")) "斗鱼直播" else "抖音直播"

    private fun setState(url: String, state: RecordState) {
        _states.update { it + (url to state) }
    }

    private companion object {
        const val PLATFORM_DIR = "抖音直播"
        const val PROGRESS_INTERVAL_MS = 500L
        const val BASE_BACKOFF_SEC = 2L
        const val MAX_BACKOFF_SEC = 60L
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val SEGMENT_RESET_MS = 60_000L
    }
}