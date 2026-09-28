package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 7c：recordDirect 直存命令构造测试（fake ffmpeg shell 脚本，模式同 RemuxTest）。
 * mkv → -f matroska；mp4 → -f mp4 + aac_adtstoasc；均无 -f segment 分段参数。
 */
class FfmpegRecorderDirectTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("ffdirect").toFile()

    /** fake ffmpeg：最后参数为输出文件；dump 命令行到同目录 cmdline.txt 并产出非空输出。 */
    private fun okBin(dir: File): File = File(dir, "fake-ffmpeg-direct.sh").apply {
        writeText(
            "#!/bin/sh\n" +
            "out=\"\"; prev=\"\"\n" +
            "for a in \"\$@\"; do\n" +
            "  out=\"\$prev\"; prev=\"\$a\"\n" +
            "done\n" +
            "echo \"\$@\" > \"\$(dirname \"\$prev\")/cmdline.txt\"\n" +
            "echo data > \"\$prev\"\n"
        )
        setExecutable(true)
    }

    private fun cmdline(dir: File): String = File(dir, "cmdline.txt").readText()

    @Test
    fun `recordDirect mkv uses matroska without segment muxer`() = runTest {
        val dir = tempDir()
        val out = File(dir, "anchor.mkv")

        val res = FfmpegRecorder(okBin(dir), CoroutineScope(Dispatchers.IO))
            .recordDirect("http://flv/example.flv", out)

        assertEquals(listOf(out), res.segments)
        assertTrue(out.exists() && out.length() > 0)
        val cmd = cmdline(dir)
        assertTrue(cmd.contains("-f matroska"))
        assertFalse(cmd.contains("segment"))
        assertFalse(cmd.contains("aac_adtstoasc"))
    }

    @Test
    fun `recordDirect mp4 uses mp4 muxer with aac bsf`() = runTest {
        val dir = tempDir()
        val out = File(dir, "anchor.mp4")

        val res = FfmpegRecorder(okBin(dir), CoroutineScope(Dispatchers.IO))
            .recordDirect("http://flv/example.flv", out)

        assertEquals(listOf(out), res.segments)
        val cmd = cmdline(dir)
        assertTrue(cmd.contains("-f mp4"))
        assertFalse(cmd.contains("segment"))
        assertTrue(cmd.contains("aac_adtstoasc"))
    }

    @Test
    fun `recordDirect keeps common input args`() = runTest {
        val dir = tempDir()
        FfmpegRecorder(okBin(dir), CoroutineScope(Dispatchers.IO))
            .recordDirect("http://flv/example.flv", File(dir, "a.mkv"), headers = mapOf("Referer" to "https://x"))

        val cmd = cmdline(dir)
        assertTrue(cmd.contains("-re"))
        assertTrue(cmd.contains("-rw_timeout"))
        assertTrue(cmd.contains("Referer: https://x"))
    }
}