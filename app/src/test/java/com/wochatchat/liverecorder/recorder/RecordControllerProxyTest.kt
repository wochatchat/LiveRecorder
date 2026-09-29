package com.wochatchat.liverecorder.recorder

import com.wochatchat.liverecorder.platform.douyin.DouyinStreamInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 4a：代理判定透传测试。
 * resolveProxy 结果应同时用于源解析（fetchInfo 第 2 参）与流下载（download proxyAddr），
 * 对齐上游 start_record 内 check/record 共用同一 proxy_address。
 */
class RecordControllerProxyTest {

    private class CapturingDownloader : StreamDownloader() {
        val proxies = mutableListOf<String?>()
        override suspend fun download(
            sourceUrl: String,
            saveFile: File,
            headers: Map<String, String>,
            proxyAddr: String?,
            onProgress: suspend (bytes: Long) -> Unit,
        ): Boolean {
            proxies.add(proxyAddr)
            saveFile.parentFile?.mkdirs()
            saveFile.writeText("seg")
            return false // 一次段结束后重连
        }
    }

    private fun info(isLive: Boolean) = DouyinStreamInfo(
        anchorName = "主播", isLive = isLive, quality = "HD",
        flvUrl = "http://flv/example.flv", recordUrl = "http://flv/example.flv",
    )

    private fun tempDir(): File = Files.createTempDirectory("rcproxy").toFile()

    @Test
    fun `resolved proxy is passed to both fetchInfo and download`() = runTest {
        val proxy = "socks5://10.0.0.1:1080"
        val seenFetch = mutableListOf<String?>()
        val downloader = CapturingDownloader()
        var resolves = 0
        val controller = RecordController(
            baseDir = tempDir(),
            downloader = downloader,
            fetchInfo = { _, p ->
                seenFetch.add(p) // 记录 controller 实际传入的代理值，防误配静默漏检
                info(isLive = resolves++ == 0) // 首轮开播，二轮关播收敛 Finished
            },
            resolveProxy = { proxy },
            sleep = { },
        )
        controller.runRecord("https://www.tiktok.com/@user")
        // 解析与下载收到同一代理地址（上游 proxy_address 单值语义）
        assertEquals(listOf(proxy, proxy), seenFetch)
        assertEquals(listOf(proxy), downloader.proxies)
    }

    @Test
    fun `no proxy configured passes null end to end`() = runTest {
        val seenFetch = mutableListOf<String?>()
        var resolves = 0
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, proxy ->
                seenFetch.add(proxy)
                info(isLive = resolves++ == 0)
            },
            sleep = { },
        )
        controller.runRecord("https://live.douyin.com/123")
        assertEquals(listOf<String?>(null, null), seenFetch)
    }

    @Test
    fun `resolveProxy throwing falls back to direct connection`() = runTest {
        var seen: String? = "sentinel"
        val controller = RecordController(
            baseDir = tempDir(),
            fetchInfo = { _, proxy ->
                seen = proxy
                DouyinStreamInfo(anchorName = "主播", isLive = false)
            },
            resolveProxy = { error("store boom") },
            sleep = { },
        )
        controller.runRecord("u")
        assertNull(seen)
    }
}
