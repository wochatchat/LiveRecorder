package com.wochatchat.liverecorder.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ApkDownloadTest {
    private lateinit var server: MockWebServer
    private lateinit var directory: File

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        directory = java.nio.file.Files.createTempDirectory("apk-download").toFile()
    }

    @After fun cleanup() {
        server.shutdown()
        directory.deleteRecursively()
    }

    @Test fun `release redirect downloads bytes and reports final progress`() = runBlocking {
        val bytes = ByteArray(150_000) { (it % 256).toByte() }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/asset")))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        var received = 0L
        var total = 0L
        val result = ApkDownload.download(directory, server.url("/release").toString()) { count, size ->
            received = count
            total = size
        }
        assertNull(result.error)
        assertArrayEquals(bytes, result.apkFile!!.readBytes())
        assertEquals(bytes.size.toLong(), received)
        assertEquals(received, total)
        assertFalse(directory.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun `HTTP failure returns an error and leaves no package`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
        val result = ApkDownload.download(directory, server.url("/missing").toString())
        assertNull(result.apkFile)
        assertTrue(result.error!!.contains("404"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `unknown content length still downloads completely`() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("installation-package", 4))
        val result = ApkDownload.download(directory, server.url("/chunked").toString())
        assertNull(result.error)
        assertEquals("installation-package", result.apkFile!!.readText())
    }

    @Test fun `empty package is rejected`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        val result = ApkDownload.download(directory, server.url("/empty").toString())
        assertNull(result.apkFile)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun `cancelling download permits an immediate independent retry`() = runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(128_000)).throttleBody(4096, 1, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>()
        val downloading = async {
            ApkDownload.download(directory, server.url("/slow").toString()) { _, _ -> started.complete(Unit) }
        }
        withTimeout(5000) { started.await() }
        downloading.cancel()
        withTimeout(5000) { downloading.join() }
        server.enqueue(MockResponse().setBody("retry-package"))
        val result = withTimeout(5000) {
            ApkDownload.download(directory, server.url("/retry").toString())
        }
        assertNull(result.error)
        assertEquals("retry-package", result.apkFile!!.readText())
    }
}
