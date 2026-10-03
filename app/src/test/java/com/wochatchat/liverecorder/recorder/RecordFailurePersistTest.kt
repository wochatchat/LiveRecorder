package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * V3-1 R2：落库语义修复验证——
 * - Failed 但确有落盘文件（重连耗尽）→ 补写历史（completed=false）；
 * - 「未在直播」常态性 Failed 不发失败通知；
 * - 重连耗尽的失败发通知（onFailed 被调用）。
 */
class RecordFailurePersistTest {

    /** 每轮都开播且产出 bytes 的假 ffmpeg；配合 fetchInfo 恒 true → 无限录，仅由重连数耗尽收敛？不会——重连只在下载结束后触发。 */
    private class FakeFfmpegWithBytes(val bytes: Long) : FfmpegRecorder(
        File("/nonexistent"), CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
    ) {
        override suspend fun record(
            sourceUrl: String, outputDir: File, headers: Map<String, String>,
            anchorName: String, fileNameBase: String?, segmentSec: Int,
            audioOnly: Boolean, onProgress: ProgressCallback,
        ): RecordResult {
            outputDir.mkdirs()
            val seg = File(outputDir, "seg0.ts").apply { writeBytes(ByteArray(bytes.toInt().coerceAtMost(4096))) }
            onProgress(bytes)
            return RecordResult(listOf(seg), bytes)
        }

        override suspend fun recordDirect(
            sourceUrl: String, outputFile: File,
            headers: Map<String, String>, audioOnly: Boolean, onProgress: ProgressCallback,
        ): RecordResult {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(ByteArray(bytes.toInt().coerceAtMost(4096)))
            onProgress(bytes)
            return RecordResult(listOf(outputFile), bytes)
        }
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("v31test").toFile()

    private fun info(isLive: Boolean, anchor: String = "测试主播") = DouyinStreamInfo(
        anchorName = anchor, isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    /**
     * S1：首轮录制有 bytes，之后每轮解析失败且重连耗尽 → give-up Failed
     * 应携带落盘元信息并触发 onFinished 落库（completed=false）。
     */
    @Test
    fun `give-up Failed with file → history written as incomplete`() = runTest {
        val history = mutableListOf<Pair<String, RecordController.RecordState.Finished>>()
        val fetchCount = AtomicInteger(0)
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ ->
                val n = fetchCount.incrementAndGet()
                if (n == 1) info(isLive = true) else throw RuntimeException("net down")
            },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            ffmpeg = FakeFfmpegWithBytes(4096),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Failed（重连耗尽）", state is RecordController.RecordState.Failed)
        val f = state as RecordController.RecordState.Failed
        assertEquals("bytes 应随 Failed 保留", 4096L, f.bytes)
        assertTrue("savePath 应随 Failed 保留", f.savePath.isNotBlank())
        assertEquals("onFinished 应被调用 1 次（补写历史）", 1, history.size)
        val entry = history.first().second
        assertFalse("补写历史应为 completed=false", entry.completed)
        assertEquals(4096L, entry.bytes)
    }

    /** S2：重连耗尽的 Failed 应触发失败通知回调（notifyUser 默认 true）。 */
    @Test
    fun `give-up Failed → onFailed called`() = runTest {
        val failures = mutableListOf<Pair<String, RecordController.RecordState.Failed>>()
        val fetchCount = AtomicInteger(0)
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ ->
                val n = fetchCount.incrementAndGet()
                if (n == 1) info(isLive = true) else throw RuntimeException("net down")
            },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            ffmpeg = FakeFfmpegWithBytes(1024),
            onFailed = { url, fail -> failures.add(url to fail) },
            sleep = { },
        )
        controller.runRecord("u")
        assertEquals("onFailed 应被调用 1 次", 1, failures.size)
        assertTrue(failures.first().second.message.contains("断流重连失败"))
    }

    /** S3：未开播的「未在直播」Failed 不发通知（notifyUser=false）。 */
    @Test
    fun `offline probe Failed → no onFailed`() = runTest {
        val failures = mutableListOf<Pair<String, RecordController.RecordState.Failed>>()
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ -> info(isLive = false) },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            onFailed = { url, fail -> failures.add(url to fail) },
            sleep = { },
        )
        controller.runRecord("u")
        assertTrue("状态应为 Failed", controller.states.value["u"] is RecordController.RecordState.Failed)
        assertTrue("「未在直播」不应触发失败通知", failures.isEmpty())
    }

    /** S4：下载抛异常（流中途出错）→ 异常 Failed 并入分段字节并落库（completed=false）。 */
    @Test
    fun `download throws with bytes → Failed meta persisted`() = runTest {
        val history = mutableListOf<Pair<String, RecordController.RecordState.Finished>>()
        val downloader = object : StreamDownloader() {
            override suspend fun download(
                sourceUrl: String, saveFile: File, headers: Map<String, String>,
                proxyAddr: String?, onProgress: suspend (bytes: Long) -> Unit,
            ): Boolean {
                saveFile.parentFile?.mkdirs()
                onProgress(1536)
                throw RuntimeException("stream broken")
            }
        }
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, _ -> info(isLive = true) },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher()),
            onFinished = { url, state -> history.add(url to state) },
            sleep = { },
        )
        controller.runRecord("u")
        val state = controller.states.value["u"]
        assertTrue("应为 Failed", state is RecordController.RecordState.Failed)
        val f = state as RecordController.RecordState.Failed
        assertEquals("异常时分段字节应并入", 1536L, f.bytes)
        assertEquals("确有落盘字节 → 应补写历史", 1, history.size)
        assertFalse(history.first().second.completed)
    }
}
