package com.wochatchat.liverecorder.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 子任务 1a 验收：公开 GET 通过 + 代理地址解析校验。
 * 纯 JVM 测试（LiveHttpClient 不依赖 android.*），CI compile-check 跑 testReleaseUnitTest。
 */
class HttpClientTest {

    @Test
    fun `public GET returns 200 and non-empty body`() = runBlocking {
        val client = LiveHttpClient()
        val result = client.get("https://www.baidu.com")
        assertTrue("expected 2xx, got ${result.code}", result.isSuccess)
        assertTrue("body should not be empty", result.text.isNotEmpty())
        assertEquals("https://www.baidu.com/", result.finalUrl)
    }

    @Test
    fun `default UA is applied and caller headers take precedence`() {
        val client = LiveHttpClient()
        val h0 = with(client) { emptyMap<String, String>().toOkHttpHeaders() }
        assertEquals(LiveHttpClient.DEFAULT_UA, h0["User-Agent"])
        val h1 = with(client) { mapOf("User-Agent" to "test-ua", "Cookie" to "ttwid=1").toOkHttpHeaders() }
        assertEquals("test-ua", h1["User-Agent"])
        assertEquals("ttwid=1", h1["Cookie"])
    }

    @Test
    fun `parseProxy accepts host_port and scheme prefixes`() {
        assertEquals(java.net.Proxy.Type.HTTP, LiveHttpClient.parseProxy("127.0.0.1:7890").type())
        assertEquals(java.net.Proxy.Type.HTTP, LiveHttpClient.parseProxy("http://10.0.0.1:8080").type())
        assertEquals(java.net.Proxy.Type.SOCKS, LiveHttpClient.parseProxy("socks5://10.0.0.1:1080").type())
    }

    @Test
    fun `parseProxy rejects invalid formats`() {
        listOf("no-port", "host:0", "host:99999", "host:port", "a:b:c").forEach { bad ->
            try {
                LiveHttpClient.parseProxy(bad)
                fail("expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }
}
