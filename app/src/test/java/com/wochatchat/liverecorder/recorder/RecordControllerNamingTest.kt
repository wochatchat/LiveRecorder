package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 5b 文件命名规则（上游 main.py:1117-1146）端到端行为：
 * 目录层级（作者/时间/标题）与文件名（{主播}_{标题_}{时间戳}.flv）。
 */
class RecordControllerNamingTest {

    /** 假下载器：写一个文件即视为一段正常 EOF。 */
    private class FakeDownloader : StreamDownloader() {
        var lastFile: File? = null
        override suspend fun download(
            sourceUrl: String,
            saveFile: File,
            headers: Map<String, String>,
            proxyAddr: String?,
            onProgress: suspend (bytes: Long) -> Unit,
        ): Boolean {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText("seg")
            lastFile = saveFile
            return true
        }
    }

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("rcn").toFile()

    @Test
    fun `naming options drive dir layout and file name`() = runTest {
        val base = tempDir()
        var resolves = 0
        val controller = RecordController(
            baseDir = base,
            downloader = FakeDownloader(),
            fetchInfo = { _, _ ->
                resolves++
                if (resolves < 2) {
                    DouyinStreamInfo(
                        anchorName = "测试主播", isLive = true, title = "直播:标题X",
                        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
                    )
                } else {
                    DouyinStreamInfo(anchorName = "测试主播", isLive = false)
                }
            },
            namingOptions = {
                RecordSource.NamingOptions(
                    folderByTime = true, folderByTitle = true, filenameByTitle = true,
                )
            },
            sleep = {},
        )
        controller.runRecord("u")

        // 第 2 轮探测关播 → Finished（completed），savePath 为录到的文件
        val state = controller.states.value["u"]!!
        assertTrue(state is RecordController.RecordState.Finished)
        val savePath = (state as RecordController.RecordState.Finished).savePath

        // 目录：{base}/抖音直播/{主播}/{日期}/{标题}_{主播}（标题清洗后冒号变 _）
        val file = File(savePath)
        val relPath = file.absolutePath.removePrefix(base.absolutePath).trim('/')
        assertTrue("实际路径: $relPath", relPath.startsWith("抖音直播/测试主播/"))
        assertTrue("含日期层", relPath.split("/").contains(java.time.LocalDate.now().toString()))
        assertEquals("直播_标题X_测试主播", file.parentFile!!.name)

        // 文件名：{主播}_{标题}_{时间戳}.flv
        assertTrue("文件名应含标题: ${file.name}", file.name.matches(
            Regex("测试主播_直播_标题X_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.flv")
        ))
    }
}
