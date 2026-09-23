package com.wochatchat.liverecorder.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.wochatchat.liverecorder.net.LiveHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 路径 A 流下载器（对照上游 main.py:385 direct_download_stream）：
 * OkHttp 流式 GET → 16KB 分块写文件。
 *
 * 上游语义：
 * - httpx.Client(timeout=None)：读超时不限；**移动端改为 [readTimeoutSec]（默认 60s）读超时，
 *   作为断流探测——连接假死无数据时抛 SocketTimeoutException 视为中断，交由
 *   RecordController 重连（上游 timeout=None 在移动端会永久挂死）**
 * - follow_redirects=True（OkHttp 默认）
 * - 非 200 → 失败
 * - 中断（协程取消 = 上游 exit_recording / url_comments）→ 失败并**保留半截文件**（上游同语义）
 * - 返回 true = 正常下载到流结束（直播流通常由服务端断开而结束）
 */
open class StreamDownloader(
    private val client: OkHttpClient = defaultClient(),
) {
    /** 下载单个分块字节数（上游 chunk_size = 1024 * 16）。 */
    companion object {
        const val CHUNK_SIZE = 16 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 读超时 = 断流探测：60s 无任何字节视为连接假死（正常直播流不可能 60s 零字节）
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 流式下载 [sourceUrl] 到 [saveFile]（父目录自动创建）。
     * @param proxyAddr 非空时该次下载经代理（4a：对齐上游 direct_download_stream 的 proxies 透传；
     *   上游录制下载与房间探测用同一 proxy_address）
     * @param onProgress 每收到一个 chunk 回调一次（累计字节数，IO 线程）
     * @return true=下载到流结束；false=非 200 / 网络异常
     * @throws kotlinx.coroutines.CancellationException 协程被取消（停止录制）
     */
    open suspend fun download(
        sourceUrl: String,
        saveFile: File,
        headers: Map<String, String> = emptyMap(),
        proxyAddr: String? = null,
        onProgress: suspend (bytes: Long) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(sourceUrl)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        val call = proxyAddr?.let { p ->
            client.newBuilder().proxy(LiveHttpClient.parseProxy(p)).build()
        }?.newCall(requestBuilder.build()) ?: client.newCall(requestBuilder.build())

        try {
            saveFile.parentFile?.mkdirs()
            call.execute().use { response ->
                if (response.code != 200) return@withContext false
                val body = response.body ?: return@withContext false
                var downloaded = 0L
                body.byteStream().use { input ->
                    saveFile.outputStream().use { output ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (n > 0) {
                                output.write(buffer, 0, n)
                                downloaded += n
                                onProgress(downloaded)
                            }
                        }
                    }
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            call.cancel()
        }
    }
}
