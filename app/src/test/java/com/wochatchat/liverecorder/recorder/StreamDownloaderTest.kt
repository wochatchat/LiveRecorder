package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** StreamDownloader（上游 main.py:385 direct_download_stream）行为测试。 */
class StreamDownloaderTest {

    private lateinit var server: MockWebServer
    private val downloader = StreamDownloader()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun downloadsStreamToFile() {
        val payload = "0123456789".repeat(100)
        server.enqueue(MockResponse().setBody(payload))
        val target = java.nio.file.Files.createTempFile("sdt", ".flv").toFile()
        val ok = runBlocking { downloader.download(server.url("/flv").toString(), target) }
        assertTrue(ok)
        assertEquals(payload.length.toLong(), target.length())
    }

    @Test
    fun non200ReturnsFalse() {
        server.enqueue(MockResponse().setResponseCode(403))
        val target = java.nio.file.Files.createTempFile("sdt2", ".flv").toFile()
        val ok = runBlocking { downloader.download(server.url("/flv").toString(), target) }
        assertFalse(ok)
    }

    @Test
    fun progressCallbackReportsCumulativeBytes() {
        val payload = "x".repeat(64 * 1024) // 4 个 16KB chunk
        server.enqueue(MockResponse().setBody(payload))
        val target = java.nio.file.Files.createTempFile("sdt3", ".flv").toFile()
        val sizes = mutableListOf<Long>()
        val ok = runBlocking {
            downloader.download(server.url("/flv").toString(), target) { b -> sizes.add(b) }
        }
        assertTrue(ok)
        assertEquals(payload.length.toLong(), target.length())
        assertTrue(sizes.isNotEmpty())
        assertEquals(payload.length.toLong(), sizes.last())
    }

    @Test
    fun customHeaderPassedThrough() {
        server.enqueue(MockResponse().setBody("data"))
        val target = java.nio.file.Files.createTempFile("sdt4", ".flv").toFile()
        val ok = runBlocking {
            downloader.download(
                server.url("/flv").toString(),
                target,
                headers = mapOf("origin" to "https://live.shopee.com"),
            )
        }
        assertTrue(ok)
        val request = server.takeRequest()
        assertEquals("https://live.shopee.com", request.getHeader("origin"))
    }

    @Test
    fun cancellationStopsDownloadAndKeepsPartialFile() {
        // 慢速大响应：下载中途取消协程，验证半截文件保留（上游语义）
        val big = "z".repeat(1024 * 1024)
        server.enqueue(
            MockResponse().setBody(big).throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS)
        )
        val target = java.nio.file.Files.createTempFile("sdt5", ".flv").toFile()
        var cancelled = false
        runBlocking {
            withTimeout(10_000) {
                val deferred = async {
                    downloader.download(server.url("/flv").toString(), target)
                }
                kotlinx.coroutines.delay(300)
                deferred.cancel()
                try {
                    deferred.await()
                } catch (e: CancellationException) {
                    cancelled = true
                }
                assertTrue(cancelled)
                assertTrue(target.exists())
                assertTrue(target.length() in 1 until big.length)
            }
        }
    }
}
