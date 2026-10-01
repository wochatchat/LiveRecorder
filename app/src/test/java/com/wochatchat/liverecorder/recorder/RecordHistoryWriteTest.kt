package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1 修复验证：录制历史写入条件测试（4 场景）。
 * 修复前：首次探测未开播 / 录制中重探测 isLive=false / 下载中途断流<60s → history 不写入
 * 修复后：lastPath.isNotBlank() && totalBytes>0 → Finished 写入
 */
class RecordHistoryWriteTest {

    private class FakeFfmpegWithBytes(val bytes: Long) : FfmpegRecorder(
        File("/nonexistent"), CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
    ) {
        override suspend fun record(
            sourceUrl: String, outputDir: File, headers: Map<String, String>,
            anchorName: String, fileNameBase: String?, segmentSec: Int,
            onProgress: ProgressCallback,
        ): RecordResult {
            outputDir.mkdirs()
            val seg = File(outputDir, "seg0.ts").apply { writeBytes(ByteArray(bytes.toInt().coerceAtMost(4096))) }
            onProgress(bytes)
            return RecordResult(listOf(seg), bytes)
        }

        override suspend fun recordDirect(
            sourceUrl: String, outputFile: File,
            headers: Map<String, String>, onProgress: ProgressCallback,
        ): RecordResult {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(ByteArray(bytes.toInt().coerceAtMost(4096)))
            onProgress(bytes)
            return RecordResult(listOf(outputFile), bytes)
        }
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("b1test").toFile()

    private fun info(isLive: Boolean, anchor: String = "测试主播") = DouyinStreamInfo(
        anchorName = anchor, isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    /**
     * B1-S1：录制一轮（有 bytes）后探测 isLive=false → Finished 写入。
     * 修复前：走 Failed 分支，attempt=0 不写 history。
     * 修复后：lastPath.isNotBlank() && totalBytes>0 → Finished(completed=true) 写入。
     */
    @Test
    fun `B1-S1 recorded bytes then live ends → Finished written`() = runTest {
        val history = mutableListOf<Pair<String, RecordController.RecordState.Finished>>()
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ -> info(isLive = false) },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            ffmpeg = FakeFfmpegWithBytes(4096),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Finished（修复前走 Failed）", state is RecordController.RecordState.Finished)
        val s = state as RecordController.RecordState.Finished
        assertTrue("completed 应为 true", s.completed)
        assertEquals("bytes 应为 4096", 4096L, s.bytes)
        assertNotNull("onFinished 应被调用", history.find { it.first == "u" })
    }

    /**
     * B1-S2：首次探测未开播（无录文件）→ Failed，不写 history。
     */
    @Test
    fun `B1-S2 first probe offline no bytes → Failed no history`() = runTest {
        val history = mutableListOf<Any>()
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ -> info(isLive = false) },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Failed（无录文件走 Failed）", state is RecordController.RecordState.Failed)
        assertTrue("history 应为空", history.isEmpty())
    }

    /**
     * B1-S3：下载中途断流（segMs<60s，segBytes 已累计）→ 重探测关播 Finished 写入。
     * 修复前 attempt=0 时走 Failed 不写。
     */
    @Test
    fun `B1-S3 download mid-stream fail with bytes → Finished written`() = runTest {
        val history = mutableListOf<Pair<String, RecordController.RecordState.Finished>>()
        val fetchCount = AtomicInteger(0)
        // FakeDownloader：首段写文件返回 false（模拟中途断流），断流后探测关播
        val downloader = object : StreamDownloader() {
            override suspend fun download(
                sourceUrl: String, saveFile: File, headers: Map<String, String>,
                proxyAddr: String?, onProgress: suspend (bytes: Long) -> Unit,
            ): Boolean {
                saveFile.parentFile?.mkdirs()
                saveFile.writeBytes(ByteArray(2048))
                onProgress(2048)
                return false
            }
        }
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, _ ->
                val n = fetchCount.incrementAndGet()
                info(isLive = n <= 1) // 首轮开播；断流重连探测时已关播
            },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Finished", state is RecordController.RecordState.Finished)
        val s = state as RecordController.RecordState.Finished
        assertTrue("completed 应为 true", s.completed)
        assertEquals("bytes 应为 2048", 2048L, s.bytes)
        assertNotNull("onFinished 应被调用", history.find { it.first == "u" })
    }

    /**
     * B1-S4：录制中重探测 isLive=false（attempt=1）→ Finished 写入（修复前后都应写入）。
     */
    @Test
    fun `B1-S4 reconnect probe offline with bytes → Finished written`() = runTest {
        val history = mutableListOf<Pair<String, RecordController.RecordState.Finished>>()
        val fetchCount = AtomicInteger(0)
        val downloader = object : StreamDownloader() {
            override suspend fun download(
                sourceUrl: String, saveFile: File, headers: Map<String, String>,
                proxyAddr: String?, onProgress: suspend (bytes: Long) -> Unit,
            ): Boolean {
                saveFile.parentFile?.mkdirs()
                saveFile.writeBytes(ByteArray(1024))
                onProgress(1024)
                return true // EOF 正常结束
            }
        }
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, _ ->
                val n = fetchCount.incrementAndGet()
                info(isLive = n <= 1)
            },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Finished", state is RecordController.RecordState.Finished)
        val s = state as RecordController.RecordState.Finished
        assertTrue("completed 应为 true", s.completed)
        assertEquals("bytes 应为 1024", 1024L, s.bytes)
        assertNotNull("onFinished 应被调用", history.find { it.first == "u" })
    }
}