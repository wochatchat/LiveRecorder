package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 子进程阻塞期间取消，必须退出、写好文件尾，并将实际字节回传给落库层。 */
class FfmpegStopTest {
    @Test fun `stop interrupts process wait and finalizes output`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("ff-stop").toFile()
        val output = File(directory, "video.mp4")
        val binary = File(directory, "fake-ffmpeg.sh").apply {
            writeText("#!/bin/sh\nfor arg in \"\$@\"; do out=\"\$arg\"; done\nprintf body > \"\$out\"\nprintf 'frame= 1 size= 1kB time=00:00:01.00\\n' >&2\nIFS= read -r cmd\nprintf trailer >> \"\$out\"\n")
            setExecutable(true)
        }
        val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        var finalBytes = 0L
        try {
            val recording = launch(Dispatchers.IO) {
                FfmpegRecorder(binary, engineScope).recordDirect("https://example.com/live", output) {
                    finalBytes = it
                    started.complete(Unit)
                }
            }
            withTimeout(5000) { started.await() }
            recording.cancel()
            withTimeout(5000) { recording.join() }
            assertTrue(recording.isCancelled)
            assertEquals("bodytrailer", output.readText())
            assertTrue(finalBytes >= output.length())
        } finally {
            engineScope.cancel()
            directory.deleteRecursively()
        }
    }
}
