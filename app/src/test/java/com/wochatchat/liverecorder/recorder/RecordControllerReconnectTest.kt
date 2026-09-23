package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 2-2d 断流重连行为测试：
 * 模拟断流（download 失败）→ 指数退避 → 恢复后继续录制（新分段文件）→ 房间关播 Finished。
 */
class RecordControllerReconnectTest {

    /** 可编程假下载器：按队列依次返回每段结果（true=流正常 EOF）。 */
    private class FakeDownloader(private val results: MutableList<Boolean>) : StreamDownloader() {
        val calls = mutableListOf<String>()
        override suspend fun download(
            sourceUrl: String,
            saveFile: File,
            headers: Map<String, String>,
            proxyAddr: String?,
            onProgress: suspend (bytes: Long) -> Unit,
        ): Boolean {
            if (results.isEmpty()) error("unexpected extra download call")
            calls.add(sourceUrl)
            val ok = results.removeAt(0)
            saveFile.parentFile?.mkdirs()
            saveFile.writeText("seg")
            onProgress(1024)
            return ok
        }
    }

    private fun info(isLive: Boolean) = DouyinStreamInfo(
        anchorName = "测试主播", isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("rc").toFile()

    @Test
    fun `interrupted stream reconnects with backoff and continues recording`() = runTest {
        // 第 1 段断流 → 退避 2s → 重连；第 2 段 EOF → 再探测仍开播 → 又退避 → 关播 Finished
        val delays = mutableListOf<Long>()
        var resolves = 0
        val downloader = FakeDownloader(mutableListOf(false, true))
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, _ ->
                resolves++
                info(isLive = resolves < 3) // 第 3 次解析（重连后探测）转未开播
            },
            sleep = { delays.add(it) },
        )
        controller.runRecord("u")
        assertEquals(2, downloader.calls.size)
        assertEquals(listOf(2000L, 2000L), delays)
        val state = controller.states.value["u"]!!
        assertTrue(state is RecordController.RecordState.Finished)
        assertTrue((state as RecordController.RecordState.Finished).completed)
        assertEquals(2048L, state.bytes) // 两段累计 1024×2
    }

    @Test
    fun `gives up after max consecutive retries with exponential delays`() = runTest {
        val delays = mutableListOf<Long>()
        val downloader = FakeDownloader(mutableListOf(false, false, false, false, false))
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, _ -> info(isLive = true) }, // 一直开播但流一直断
            sleep = { delays.add(it) },
        )
        controller.runRecord("u")
        assertEquals(5, downloader.calls.size)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 32000L), delays)
        assertTrue(controller.states.value["u"] is RecordController.RecordState.Failed)
    }

    @Test
    fun `resolves offline after failure and finishes with partial file`() = runTest {
        // 断流一次 → 退避 → 重新探测发现已关播 → Finished（保留已录内容）
        val delays = mutableListOf<Long>()
        var resolves = 0
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = FakeDownloader(mutableListOf(false)),
            fetchInfo = { _, _ ->
                resolves++
                info(isLive = resolves == 1)
            },
            sleep = { delays.add(it) },
        )
        controller.runRecord("u")
        assertEquals(2000L, delays.single())
        val state = controller.states.value["u"] as RecordController.RecordState.Finished
        assertTrue(state.completed)
    }

    @Test
    fun `backoff delay doubles and caps at 60s`() {
        val controller = RecordController(baseDir = tempDir())
        assertEquals(listOf(2L, 4L, 8L, 16L, 32L, 60L), (1..6).map { controller.reconnectDelaySec(it) })
    }

    // ---- 2h：stopAll（存储阈值触底时停掉全部活动录制） ----

    @Test
    fun `stopAll cancels all active recordings`() = runTest {
        val started = AtomicInteger(0)
        val blocking = object : StreamDownloader() {
            override suspend fun download(
                sourceUrl: String,
                saveFile: File,
                headers: Map<String, String>,
                proxyAddr: String?,
                onProgress: suspend (bytes: Long) -> Unit,
            ): Boolean {
                started.incrementAndGet()
                awaitCancellation()
            }
        }
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = blocking,
            fetchInfo = { _, _ -> info(isLive = true) },
            scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
            sleep = { },
        )
        controller.start("u1")
        controller.start("u2")
        // Unconfined 调度：runRecord 已推进到下载挂起点
        assertEquals(2, started.get())
        assertTrue(controller.isActive("u1"))
        assertTrue(controller.isActive("u2"))
        // stopAll：全部取消，状态置 Finished（半截文件保留语义）
        controller.stopAll()
        assertFalse(controller.isActive("u1"))
        assertFalse(controller.isActive("u2"))
        val state = controller.states.value["u1"] as RecordController.RecordState.Finished
        assertFalse(state.completed)
    }
}
