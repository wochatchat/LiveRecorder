package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FFmpeg 分段录制核心引擎（Phase 3-3g）。
 * 纯 JVM，无 Android 依赖；[ffmpegBin] 由调用方从 nativeLibraryDir 注入。
 *
 * 对照上游 main.py:1254–1268 / 1366–1368 分段分支：
 * `-f segment -segment_time {split_time} -segment_format {flv/mpegts} -reset_timestamps 1`
 *
 * 与上游的差异：
 * - 上游 fork 同步等待；本实现协程化，外部可 cancel
 * - 进度估算来自 ffmpeg stderr 解析（`frame=` 行含 bytes 估算）
 * - 默认 1800s 分段（对齐上游 config.ini 默认值）
 */
class FfmpegRecorder(
    private val ffmpegBin: File,
    private val scope: CoroutineScope,
    /** 协程睡眠点（单测注入，生产走 kotlinx.coroutines.delay）。 */
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /** 录制结果。 */
    data class RecordResult(
        val segments: List<File>,
        /** 估算累计字节数（来自 ffmpeg 解析，关闭前为近似值）。 */
        val estimatedBytes: Long,
    )

    /** 进度回调：当前估算累计字节数（可在 IO 线程调用）。 */
    typealias ProgressCallback = (estimatedBytes: Long) -> Unit

    /**
     * 分段录制直播流。
     * @param sourceUrl   直播流地址（m3u8 或 flv）
     * @param outputDir   输出目录（ffmpeg 在此写入 {anchor}_{ts}_*.{ext}）
     * @param headers     HTTP 头（referer/origin 等）
     * @param anchorName  主播名（用于文件名）
     * @param segmentSec  分段时长（秒），默认 [DEFAULT_SEGMENT_SEC]
     * @param onProgress  进度回调（估算字节数，外部据此更新 UI）
     * @return 录制结果
     * @throws kotlinx.coroutines.CancellationException 协程被取消
     */
    suspend fun record(
        sourceUrl: String,
        outputDir: File,
        headers: Map<String, String>,
        anchorName: String,
        segmentSec: Int = DEFAULT_SEGMENT_SEC,
        onProgress: ProgressCallback = {},
    ): RecordResult = coroutineScope {
        val baseName = "${RecordSource.cleanName(anchorName)}_${
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        }"

        // 选择输出格式（对齐 main.py:1367 条件分支）
        val isM3u8 = sourceUrl.contains(".m3u8") || sourceUrl.contains(".ts")
        val extension = if (isM3u8) "ts" else "flv"
        val segmentFormat = if (isM3u8) "mpegts" else "flv"
        // FLV 的 aac 音频需要 bitstream filter 转换 ADTS→ASC
        val extraArgs = if (isM3u8) {
            listOf("-c:v", "copy", "-c:a", "copy")
        } else {
            listOf("-c:v", "copy", "-c:a", "copy", "-bsf:a", "aac_adtstoasc")
        }

        val outputPath = File(outputDir, "${baseName}_%03d.$extension").absolutePath
        val cmd = buildCommand(sourceUrl, outputPath, headers, segmentSec, segmentFormat, extraArgs)

        var estimatedBytes = 0L
        val progressJob: Job

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(false)
            .start()

        // 在 IO 线程解析 stderr 并上报进度
        progressJob = scope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(process.errorStream))
            try {
                var line: String?
                while (isActive) {
                    line = reader.readLine() ?: break
                    parseProgress(line) { bytes ->
                        estimatedBytes = bytes
                        onProgress(bytes)
                    }
                }
            } finally {
                reader.close()
            }
        }

        try {
            // 等待 ffmpeg 结束（协程 cancel → process.destroyForcibly）
            val exitCode = process.waitFor()
            progressJob.cancel()

            if (exitCode != 0 && exitCode != -2) {
                throw IllegalStateException("ffmpeg 录制失败，exitCode=$exitCode")
            }

            // 收集分片文件（%03d 编号由 ffmpeg 管理）
            val segments = outputDir.listFiles { f ->
                f.name.startsWith(baseName) && f.name.endsWith(".$extension")
            }?.sortedBy { it.name } ?: emptyList()

            // 进程退出后获取更精确的文件大小
            val totalBytes = segments.sumOf { it.length() }.coerceAtLeast(estimatedBytes)

            RecordResult(segments, totalBytes)
        } finally {
            progressJob.cancel()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun buildCommand(
        sourceUrl: String,
        outputPath: String,
        headers: Map<String, String>,
        segmentSec: Int,
        segmentFormat: String,
        extraArgs: List<String>,
    ): List<String> = buildList {
        add(ffmpegBin.absolutePath)
        add("-y")
        add("-hide_banner")
        add("-loglevel")  ; add("error")
        add("-rw_timeout"); add("15000000")
        add("-user_agent"); add(DEFAULT_UA)
        add("-re")                              // 以实时速率读直播流
        add("-i")        ; add(sourceUrl)
        add("-bufsize")  ; add("8000k")
        add("-sn")                               // skip subtitles
        add("-dn")                               // skip data
        add("-reconnect_streamed")
        add("-reconnect_at_eof")
        add("-reconnect_delay_max"); add("60")

        if (headers.isNotEmpty()) {
            add("-headers")
            add(headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n")
        }

        addAll(extraArgs)

        // 分段参数（对齐 main.py:1254–1258 / 1366–1368）
        add("-f")             ; add("segment")
        add("-segment_time")  ; add(segmentSec.toString())
        add("-segment_format"); add(segmentFormat)
        add("-reset_timestamps"); add("1")

        add(outputPath)   // 含 %03d，ffmpeg 自动编号
    }

    /**
     * 解析 ffmpeg stderr 行，提取估算字节数。
     *
     * ffmpeg frame= 行示例（hls）：
     *   frame=  123 fps=30 q=-0.0 size=    2048kB time=00:10:30.00 bitrate=  123.4kbits/s
     * ffmpeg (general)：
     *   frame=  456 fps=30 q=-0.0 size=    8192kB time=00:05:00.00 bitrate=  128.0kbits/s
     *
     * `size=NNNkB` 在 segment 模式下是当前分片字节数，累加已开启的分片估算总大小。
     */
    private fun parseProgress(line: String, onBytes: (Long) -> Unit) {
        if (!line.startsWith("frame=")) return
        // 提取 "size=    NNNNkB" 或 "size=    NNNNkB "
        val sizeMatch = SIZE_RE.find(line) ?: return
        val kb = sizeMatch.groupValues[1].toLongOrNull() ?: return
        onBytes(kb * 1024)
    }

    private companion object {
        const val DEFAULT_SEGMENT_SEC = 1800  // 30 min，对齐上游 config.ini 默认值
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36"
        private val SIZE_RE = Regex("""size=\s*(\d+)kB""")
    }
}