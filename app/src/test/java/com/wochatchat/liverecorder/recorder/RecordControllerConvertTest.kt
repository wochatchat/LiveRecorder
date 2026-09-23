package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3-3h 会话结束 TS→MP4 转换调度测试：
 * 开关开 → ffmpeg 分段会话结束后逐分片 remux（仅 TS，对齐上游 save_type=='TS'）；
 * 开关关 / 查询异常 → 不转换（异常按关闭处理）。
 */
class RecordControllerConvertTest {

    /** 假录制器：record 写入 1 个 ts + 1 个 flv 分片；remuxToMp4 记录被转换的分片。 */
    private class FakeFfmpeg : FfmpegRecorder(File("/nonexistent"), CoroutineScope(UnconfinedTestDispatcher())) {
        val converted = mutableListOf<File>()

        override suspend fun record(
            sourceUrl: String,
            outputDir: File,
            headers: Map<String, String>,
            anchorName: String,
            segmentSec: Int,
            onProgress: ProgressCallback,
        ): RecordResult {
            val ts = File(outputDir, "anchor_000.ts").apply { writeText("ts") }
            val flv = File(outputDir, "anchor_000.flv").apply { writeText("flv") }
            return RecordResult(listOf(ts, flv), 4096)
        }

        override fun remuxToMp4(input: File, deleteOriginal: Boolean): File? {
            converted.add(input)
            return File(input.parentFile, input.nameWithoutExtension + ".mp4")
        }
    }

    private fun info(isLive: Boolean) = DouyinStreamInfo(
        anchorName = "测试主播", isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("rcconv").toFile()

    /** 首轮开播（会话结束触发转换），二轮探测=已关播 → Finished 收敛。 */
    private fun newController(
        converts: suspend () -> Boolean,
    ): Pair<RecordController, FakeFfmpeg> {
        val resolves = AtomicInteger(0)
        val fake = FakeFfmpeg()
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { info(isLive = resolves.incrementAndGet() < 2) },
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            ffmpeg = fake,
            mp4Convert = converts,
            sleep = { },
        )
        return controller to fake
    }

    @Test
    fun `session end converts ts segments only when enabled`() = runTest {
        val (controller, fake) = newController(converts = { true })

        controller.runRecord("u")

        // 仅 ts 分片被转换，flv 分片不参与（上游仅 TS 保存类型转 mp4，main.py:454）
        assertEquals(1, fake.converted.size)
        assertTrue(fake.converted[0].name.endsWith(".ts"))
        assertTrue(controller.states.value["u"] is RecordController.RecordState.Finished)
    }

    @Test
    fun `no conversion when toggle off`() = runTest {
        val (controller, fake) = newController(converts = { false })

        controller.runRecord("u")

        assertEquals(0, fake.converted.size)
    }

    @Test
    fun `toggle throwing is treated as disabled`() = runTest {
        val (controller, fake) = newController(converts = { error("store gone") })

        controller.runRecord("u")

        assertEquals(0, fake.converted.size)
    }
}
