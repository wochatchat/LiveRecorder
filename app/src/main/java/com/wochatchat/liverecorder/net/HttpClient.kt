package com.wochatchat.liverecorder.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 直播平台 HTTP 客户端层（对应上游 http_clients/async_http.py 的 async_req 语义）。
 *
 * - HTTP/2：OkHttp 对 https 自动 ALPN 协商，无需额外配置
 * - 默认禁用系统代理（对齐上游 opener no_proxy_handler）；per-request 传 [proxyAddr] 覆盖
 * - [trustAll]：verify=False 对应项，仅对自签证书平台按需构建独立 client（见 docs/04-risks.md，
 *   不做全局关闭校验）
 * - 超时默认 20s，对齐上游 async_req(timeout=20)
 */
open class LiveHttpClient(
    proxyAddr: String? = null,
    trustAll: Boolean = false,
    timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
) {
    open val client: OkHttpClient = buildClient(proxyAddr, trustAll, timeoutSec)

    data class HttpResult(
        val code: Int,
        val text: String,
        val finalUrl: String,
        val cookies: Map<String, String>,
    ) {
        val isSuccess: Boolean get() = code in 200..299
    }

    /** GET。headers 为 per-platform 注入点（UA 伪装等由调用方按平台传入，缺省用默认 UA）。 */
    open suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toOkHttpHeaders())
            .build()
        execute(request, timeoutSec)
    }

    /** HEAD 探测（对齐上游 get_response_status：follow redirects，10s 超时，异常/非 200 均为 false）。 */
    suspend fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 10L,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .headers(headers.toOkHttpHeaders())
                .head()
                .build()
            execute(request, timeoutSec).code == 200
        } catch (e: Exception) {
            false
        }
    }

    /** POST 表单（对齐上游 sync_req data=urlencode 语义）。 */
    open suspend fun postForm(
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String>,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): HttpResult = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        post(url, headers, body, timeoutSec)
    }

    /** POST 原始 body（json 字符串 / bytes 由调用方构造）。 */
    open suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): HttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toOkHttpHeaders())
            .post(body)
            .build()
        execute(request, timeoutSec)
    }

    private fun execute(request: Request, timeoutSec: Long): HttpResult {
        val call = if (timeoutSec == DEFAULT_TIMEOUT_SEC) client.newCall(request)
        else client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
            .newCall(request)
        call.execute().use { resp ->
            val cookies = resp.headers("Set-Cookie")
                .mapNotNull { Cookie.parse(resp.request.url, it) }
                .associate { it.name to it.value }
            return HttpResult(
                code = resp.code,
                text = resp.body?.string().orEmpty(),
                finalUrl = resp.request.url.toString(),
                cookies = cookies,
            )
        }
    }

    internal fun Map<String, String>.toOkHttpHeaders(): okhttp3.Headers =
        okhttp3.Headers.Builder().apply {
            set("User-Agent", DEFAULT_UA) // UA 伪装缺省值，调用方可覆盖
            forEach { (k, v) -> set(k, v) }
        }.build()

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 20L

        /** 缺省 UA（与 docs/06-verification.md 对照向量中的 UA 一致，便于 ab_sign 对照）。 */
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"

        /**
         * 代理地址解析："host:port" / "http://host:port" / "socks5://host:port"。
         * 非法格式抛 IllegalArgumentException（对齐上游 ProxyInfo.__post_init__ 的校验语义）。
         */
        fun parseProxy(addr: String): Proxy {
            val (type, hostPort) = when {
                addr.startsWith("socks5://") -> Proxy.Type.SOCKS to addr.removePrefix("socks5://")
                addr.startsWith("http://") -> Proxy.Type.HTTP to addr.removePrefix("http://")
                addr.startsWith("https://") -> Proxy.Type.HTTP to addr.removePrefix("https://")
                else -> Proxy.Type.HTTP to addr
            }
            val parts = hostPort.split(":")
            require(parts.size == 2) { "invalid proxy addr: $addr" }
            val port = parts[1].toIntOrNull()
            require(port != null && port in 1..65535) { "invalid proxy port: $addr" }
            return Proxy(type, InetSocketAddress(parts[0], port))
        }

        private fun buildClient(proxyAddr: String?, trustAll: Boolean, timeoutSec: Long): OkHttpClient {
            val builder = OkHttpClient.Builder()
                // 默认禁用系统代理（对齐上游 no_proxy opener；ProxySelector.of 是 JDK9+ API，Android 不可用）
                .proxy(proxyAddr?.let { parseProxy(it) } ?: Proxy.NO_PROXY)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .followRedirects(true)
            proxyAddr?.let { builder.proxy(parseProxy(it)) }
            if (trustAll) {
                builder.sslSocketFactory(relaxedSslContext().socketFactory, RelaxedTrustManager)
            }
            return builder.build()
        }

        private fun relaxedSslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(RelaxedTrustManager), SecureRandom())
        }
    }

    /** 仅关闭证书链与主机名校验的自签证书兜底（使用面见 docs/04-risks.md：仅指定平台 client）。 */
    private object RelaxedTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}

