package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 3-3h 容器级转换测试：fake ffmpeg shell 脚本替代真实二进制。
 * fake 行为：解析 -i 后的输入，把完整命令行 dump 到输入同目录 cmdline.txt，
 * 正常版 cp 输入到输出（最后参数）；失败版 exit 1。
 */
class FfmpegRecorderRemuxTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("ffremux").toFile()

    private fun recorder(bin: File) = FfmpegRecorder(ffmpegBin = bin, scope = CoroutineScope(Dispatchers.IO))

    private fun tsInput(dir: File): File =
        File(dir, "anchor_2026-09-23_000.ts").apply { writeBytes(ByteArray(2048)) }

    /** 正常版 fake ffmpeg：cp 输入→输出 + dump 命令行到 cmdline.txt。 */
    private fun okBin(dir: File): File {
        val script = File(dir, "fake-ffmpeg-ok.sh").apply {
            writeText(
                "#!/bin/sh\n" +
                "inp=\"\"; prev=\"\"\n" +
                "for a in \"\$@\"; do\n" +
                "  if [ \"\$prev\" = \"-i\" ]; then inp=\"\$a\"; fi\n" +
                "  prev=\"\$a\"\n" +
                "done\n" +
                "out=\"\$prev\"\n" +
                "echo \"\$@\" > \"\$(dirname \"\$inp\")/cmdline.txt\"\n" +
                "cp \"\$inp\" \"\$out\"\n"
            )
            setExecutable(true)
        }
        return script
    }

    private fun failBin(dir: File): File = File(dir, "fake-ffmpeg-fail.sh").apply {
        writeText("#!/bin/sh\necho boom >&2\nexit 1\n")
        setExecutable(true)
    }

    @Test
    fun `remuxToMp4 copies input deletes original and returns output`() {
        val dir = tempDir()
        val input = tsInput(dir)

        val out = recorder(okBin(dir)).remuxToMp4(input, deleteOriginal = true)

        assertEquals("anchor_2026-09-23_000.mp4", out!!.name)
        assertTrue(out.exists() && out.length() == 2048L)
        assertTrue(!input.exists())
    }

    @Test
    fun `remuxToMp4 keeps original when deleteOriginal false`() {
        val dir = tempDir()
        val input = tsInput(dir)

        val out = recorder(okBin(dir)).remuxToMp4(input, deleteOriginal = false)

        assertNotNull(out)
        assertTrue(input.exists())
    }

    @Test
    fun `remux command matches upstream converts_mp4 args`() {
        val dir = tempDir()
        val input = tsInput(dir)
        val recorder = recorder(okBin(dir))

        recorder.remuxToMp4(input, deleteOriginal = true)

        val cmdline = File(dir, "cmdline.txt").readText()
        // 对齐上游 converts_mp4（main.py:236-239）：-c copy 容器级 + -f mp4
        assertTrue(cmdline.contains("-c:v copy"))
        assertTrue(cmdline.contains("-c:a copy"))
        assertTrue(cmdline.contains("-f mp4"))
        assertTrue(cmdline.contains("anchor_2026-09-23_000.mp4"))
        assertTrue(!cmdline.contains("libx264")) // 3h 不做 h264 重编码
    }

    @Test
    fun `extractM4a command matches upstream converts_m4a args`() {
        val dir = tempDir()
        val input = tsInput(dir)

        val out = recorder(okBin(dir)).extractM4a(input, deleteOriginal = false)

        assertEquals("anchor_2026-09-23_000.m4a", out!!.name)
        assertTrue(input.exists()) // deleteOriginal=false 保留
        val cmdline = File(dir, "cmdline.txt").readText()
        assertTrue(cmdline.contains("-vn"))
        assertTrue(cmdline.contains("-c:a aac"))
        assertTrue(cmdline.contains("aac_adtstoasc"))
        assertTrue(cmdline.contains("-ab 320k"))
    }

    @Test
    fun `extractM4a deletes original when requested`() {
        val dir = tempDir()
        val input = tsInput(dir)

        val out = recorder(okBin(dir)).extractM4a(input, deleteOriginal = true)

        assertNotNull(out)
        assertTrue(!input.exists())
    }

    @Test
    fun `failed ffmpeg leaves original intact and returns null`() {
        val dir = tempDir()
        val input = tsInput(dir)

        val out = recorder(failBin(dir)).remuxToMp4(input, deleteOriginal = true)

        assertNull(out)
        assertTrue(input.exists()) // 失败不删原文件
        assertFalse(File(dir, "anchor_2026-09-23_000.mp4").exists())
    }

    @Test
    fun `missing input short-circuits without invoking ffmpeg`() {
        val dir = tempDir()
        val recorder = recorder(okBin(dir))

        assertNull(recorder.remuxToMp4(File(dir, "nope.ts")))
        assertNull(recorder.extractM4a(File(dir, "nope2.ts")))
        // 无命令执行痕迹
        assertFalse(File(dir, "cmdline.txt").exists())
    }
}
