package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 7c：保存格式直存测试。
 * saveFormat mkv/mp4 → recordDirect()（record 不调用、不触发 TS 转换）；
 * saveFormat ts / 空 → 现有 record() 路径不变。
 */
class RecordControllerSaveFormatTest {

    private class FakeFfmpeg : FfmpegRecorder(File("/nonexistent"), CoroutineScope(UnconfinedTestDispatcher())) {
        var recordCalled = false
        var recordDirectOutput: File? = null

        override suspend fun record(
            sourceUrl: String, outputDir: File, headers: Map<String, String>,
            anchorName: String, fileNameBase: String?, segmentSec: Int,
            onProgress: ProgressCallback,
        ): RecordResult {
            recordCalled = true
            val ts = File(outputDir, "anchor_000.ts").apply { writeText("ts") }
            return RecordResult(listOf(ts), 2048)
        }

        override suspend fun recordDirect(
            sourceUrl: String, outputFile: File,
            headers: Map<String, String>, onProgress: ProgressCallback,
        ): RecordResult {
            recordDirectOutput = outputFile
            outputFile.writeBytes(ByteArray(2048))
            return RecordResult(listOf(outputFile), 2048)
        }
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("rcfmt").toFile()

    private fun info(isLive: Boolean) = DouyinStreamInfo(
        anchorName = "测试主播", isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    /** 首轮开播（录制一轮即断流），二轮探测=已关播 → Finished 收敛（同 ConvertTest 模式）。 */
    private fun newController(fmt: suspend () -> String): Pair<RecordController, FakeFfmpeg> {
        val resolves = AtomicInteger(0)
        val fake = FakeFfmpeg()
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, _ -> info(isLive = resolves.incrementAndGet() < 2) },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            ffmpeg = fake,
            saveFormat = fmt,
            sleep = { },
        )
        return controller to fake
    }

    @Test
    fun `saveFormat mkv routes to recordDirect`() = runTest {
        val (controller, fake) = newController { "mkv" }
        controller.runRecord("u")
        assertEquals("mkv", fake.recordDirectOutput?.extension)
        assertFalse(fake.recordCalled)
        assertTrue(controller.states.value["u"] is RecordController.RecordState.Finished)
    }

    @Test
    fun `saveFormat mp4 calls recordDirect`() = runTest {
        val (controller, fake) = newController { "mp4" }
        controller.runRecord("u")
        assertEquals("mp4", fake.recordDirectOutput?.extension)
        assertFalse(fake.recordCalled)
    }

    @Test
    fun `saveFormat ts keeps record path`() = runTest {
        val (controller, fake) = newController { "ts" }
        controller.runRecord("u")
        assertTrue(fake.recordCalled)
        assertNull(fake.recordDirectOutput)
    }

    @Test
    fun `saveFormat blank or unknown falls back to ts`() = runTest {
        val (controller, fake) = newController { "  " }
        controller.runRecord("u")
        assertTrue(fake.recordCalled)
        assertNull(fake.recordDirectOutput)
    }
}