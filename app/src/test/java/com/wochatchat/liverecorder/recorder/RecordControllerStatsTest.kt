package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import com.wochatchat.liverecorder.ui.StatsFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 5c 录制统计：durationMs 只累计 Recording 段（剔除解析/重连等待）；
 * 关播 / 手动停止的 Finished 携带总时长与真实字节数；格式化函数边界。
 */
class RecordControllerStatsTest {

    private fun info(isLive: Boolean) = DouyinStreamInfo(
        anchorName = "测试主播", isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("rcstats").toFile()

    private fun assertFinished(st: RecordController.RecordState?): RecordController.RecordState.Finished {
        assertTrue("期望 Finished，实际 $st", st is RecordController.RecordState.Finished)
        return st as RecordController.RecordState.Finished
    }

    /** ffmpeg 路径：录制段 10s 计入时长；解析期与重连等待不计入。 */
    @Test
    fun `ffmpeg duration accumulates recording segments only`() = runTest {
        var t = 0L
        val resolves = intArrayOf(0)
        val fake = object : FfmpegRecorder(File("/nonexistent"), CoroutineScope(UnconfinedTestDispatcher())) {
            override suspend fun record(
                sourceUrl: String,
                outputDir: File,
                headers: Map<String, String>,
                anchorName: String,
                fileNameBase: String?,
                segmentSec: Int,
                onProgress: ProgressCallback,
            ): RecordResult {
                t += 10_000
                onProgress(1024)
                return RecordResult(emptyList(), 4096)
            }
        }
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ ->
                t += 5_000 // 解析阶段推进：不进时长
                info(isLive = ++resolves[0] < 2)
            },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            ffmpeg = fake,
            sleep = { ms -> t += ms }, // 重连等待也不计入
            nowMs = { t },
        )

        controller.runRecord("u")

        val finished = assertFinished(controller.states.value["u"])
        assertEquals(10_000L, finished.durationMs)
        assertEquals(4096L, finished.bytes)
    }

    /** OkHttp 直下路径：录制中 durationMs 实时累计；手动停止 Finished 带已写字节数。 */
    @Test
    fun `okhttp mode reports duration and stopped bytes`() = runTest {
        var t = 0L
        var seenRecording: RecordController.RecordState.Recording? = null
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ ->
                t += 10_000 // 解析推进：不进时长
                info(isLive = true)
            },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            downloader = object : StreamDownloader() {
                override suspend fun download(
                    sourceUrl: String,
                    saveFile: File,
                    headers: Map<String, String>,
                    proxyAddr: String?,
                    onProgress: suspend (bytes: Long) -> Unit,
                ): Boolean {
                    saveFile.parentFile?.mkdirs()
                    saveFile.writeText("seg")
                    // 首拍：lastUpdate=0、t=1000 ≥ PROGRESS_INTERVAL_MS → 发 Recording 状态
                    t += 1_000
                    onProgress(4096)
                    t += 1_000
                    onProgress(8192)
                    // 两拍回调后挂起（等价手动停止点）
                    awaitCancellation()
                }
            },
            sleep = { ms -> t += ms },
            nowMs = { t },
        )
        // Unconfined 调度：start 后 runRecord 已推进到下载挂起点，读最新 Recording 快照
        controller.start("u")
        seenRecording = controller.states.value["u"] as? RecordController.RecordState.Recording

        controller.stop("u")

        val rec = seenRecording
        assertTrue("录制中应有 Recording 状态", rec != null)
        assertEquals(2000L, rec!!.durationMs) // 解析期 10s 不计入
        val finished = assertFinished(controller.states.value["u"])
        assertFalse("手动停止应 completed=false", finished.completed)
        assertEquals(8192L, finished.bytes)
        assertEquals(2000L, finished.durationMs)
    }
}
