package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 真实 Job 取消后，落库回调仍可挂起（DataStore.edit 的行为），并保留分段时长。 */
class RecordStopPersistenceTest {
    @Test fun `cancelled recording persists history after a suspension`() = runTest {
        verifyStop(segmented = false)
    }

    @Test fun `cancelled ffmpeg segment preserves duration and history`() = runTest {
        verifyStop(segmented = true)
    }

    private suspend fun verifyStop(segmented: Boolean) = kotlinx.coroutines.coroutineScope {
        val directory = java.nio.file.Files.createTempDirectory("record-stop").toFile()
        val started = CompletableDeferred<Unit>()
        var time = 0L
        var persisted: RecordController.RecordState.Finished? = null
        val stream = object : StreamDownloader() {
            override suspend fun download(
                sourceUrl: String, saveFile: File, headers: Map<String, String>,
                proxyAddr: String?, onProgress: suspend (Long) -> Unit,
            ): Boolean {
                saveFile.writeBytes(ByteArray(64))
                time = 5000
                onProgress(64)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val ffmpeg = object : FfmpegRecorder(File("/unused"), this) {
            override suspend fun record(
                sourceUrl: String, outputDir: File, headers: Map<String, String>,
                anchorName: String, fileNameBase: String?, segmentSec: Int,
                audioOnly: Boolean, onProgress: ProgressCallback,
            ): RecordResult {
                File(outputDir, "${fileNameBase}_000.flv").writeBytes(ByteArray(64))
                time = 5000
                onProgress(64)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val controller = RecordController(
            baseDir = directory,
            scope = this,
            downloader = stream,
            ffmpeg = if (segmented) ffmpeg else null,
            fetchInfo = { _, _ -> DouyinStreamInfo(
                anchorName = "测试主播", isLive = true, quality = "HD",
                flvUrl = "https://example.com/live.flv", recordUrl = "https://example.com/live.flv",
            ) },
            nowMs = { time },
            onFinished = { _, state ->
                yield()
                currentCoroutineContext().ensureActive()
                persisted = state
            },
        )
        try {
            val recording = launch { controller.runRecord("https://live.douyin.com/1") }
            started.await()
            recording.cancel()
            recording.join()
            assertTrue("取消后仍须完成落库", persisted != null)
            assertEquals(64L, persisted!!.bytes)
            assertEquals(5000L, persisted!!.durationMs)
            assertEquals("抖音直播", persisted!!.platform)
            assertFalse(persisted!!.completed)
        } finally {
            directory.deleteRecursively()
        }
    }
}
