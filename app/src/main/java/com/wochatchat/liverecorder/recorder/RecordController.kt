package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinSpider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
 * 录制状态机（1g 最小版：手动单发录制，消费 DouyinStreamInfo）。
 *
 * 流程对齐上游 main.py start_record 的 FLV 直下分支：
 * 解析直播源 → select_source_url → direct_download_stream 落盘。
 *
 * 与上游的差异（后续 Phase 补齐）：
 * - 不做监控轮询（监控循环属 Phase 2），由用户手动开始/停止
 * - HLS(m3u8) 源需 ffmpeg（Phase 3），当前仅支持 FLV 直下，h265-only 房间报不支持
 * - 文件命名：{baseDir}/抖音直播/{主播}/{主播}_{时间戳}.flv（folder_by_author 默认开，
 *   filename_by_title 默认关，对齐 config.ini 默认值）
 */
class RecordController(
    private val baseDir: File,
    private val downloader: StreamDownloader = StreamDownloader(),
    private val spider: DouyinSpider = DouyinSpider(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed class RecordState {
        /** 正在解析直播源。 */
        data object Resolving : RecordState()

        /** 录制中。[bytes] 已写字节数，[savePath] 落盘路径。 */
        data class Recording(val savePath: String, val bytes: Long, val quality: String) : RecordState()

        /** 已结束（下载完成或手动停止，文件保留）。 */
        data class Finished(val savePath: String, val bytes: Long, val completed: Boolean) : RecordState()

        /** 失败（未开播 / 无流 / 下载错误）。 */
        data class Failed(val message: String) : RecordState()
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _states = MutableStateFlow<Map<String, RecordState>>(emptyMap())

    /** 每个 url 的最新状态快照。 */
    val states: StateFlow<Map<String, RecordState>> = _states.asStateFlow()

    /** 是否正在录制该 url（解析中或录制中）。 */
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

    private suspend fun runRecord(url: String) {
        setState(url, RecordState.Resolving)
        try {
            val info = spider.fetchStreamInfo(url)
            if (!info.isLive) {
                setState(url, RecordState.Failed("未在直播"))
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
            var lastBytes = 0L
            var lastUpdate = 0L
            val ok = downloader.download(
                sourceUrl = sourceUrl,
                saveFile = saveFile,
                headers = headers,
            ) { bytes ->
                lastBytes = bytes
                val t = System.currentTimeMillis()
                if (t - lastUpdate >= PROGRESS_INTERVAL_MS) {
                    lastUpdate = t
                    setState(url, RecordState.Recording(saveFile.absolutePath, bytes, info.quality))
                }
            }
            setState(
                url,
                if (ok) RecordState.Finished(saveFile.absolutePath, lastBytes, completed = true)
                else RecordState.Failed("下载中断，已保留部分文件: ${saveFile.name}"),
            )
        } catch (e: CancellationException) {
            // 用户主动停止：半截文件保留（上游同语义）
            setState(url, RecordState.Finished(currentPath(url) ?: "", currentBytes(url), completed = false))
            throw e
        } catch (e: Exception) {
            setState(url, RecordState.Failed("录制异常: ${e.message}"))
        }
    }

    private fun platformName(url: String): String = "抖音直播"

    private fun setState(url: String, state: RecordState) {
        _states.update { it + (url to state) }
    }

    private fun currentPath(url: String): String? =
        (states.value[url] as? RecordState.Recording)?.savePath

    private fun currentBytes(url: String): Long =
        (states.value[url] as? RecordState.Recording)?.bytes ?: 0L

    private companion object {
        /** 平台目录名（上游 full_path 拼接的 platform，抖音='抖音直播'）。 */
        const val PLATFORM_DIR = "抖音直播"

        /** 进度回调节流间隔（ms）。 */
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
