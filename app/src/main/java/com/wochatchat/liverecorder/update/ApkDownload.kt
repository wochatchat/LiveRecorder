package com.wochatchat.liverecorder.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** APK 下载由应用自己写缓存；系统 DownloadManager 无权写内部 cacheDir。 */
internal object ApkDownload {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun download(
        directory: File,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): UpdateDownloader.DownloadResult = suspendCancellableCoroutine { continuation ->
        val token = UUID.randomUUID().toString()
        val partial = File(directory, "$token.part")
        val destination = File(directory, "$token.apk")
        val call = try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
            client.newCall(Request.Builder().url(url).build())
        } catch (e: Exception) {
            continuation.resume(UpdateDownloader.DownloadResult(null, e.message))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                partial.delete()
                if (continuation.isActive) {
                    continuation.resume(UpdateDownloader.DownloadResult(null, e.message))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                var complete = false
                try {
                    response.use {
                        check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                        val body = response.body ?: error("下载响应为空")
                        val total = body.contentLength()
                        var downloaded = 0L
                        var lastReport = 0L
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    if (!continuation.isActive) throw CancellationException()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                    downloaded += count
                                    val now = System.nanoTime()
                                    if (now - lastReport >= 200_000_000L) {
                                        onProgress(downloaded, total)
                                        lastReport = now
                                    }
                                }
                            }
                        }
                        check(downloaded > 0) { "下载的安装包为空" }
                        check(total < 0 || downloaded == total) { "安装包下载不完整，请重试" }
                        if (!continuation.isActive) throw CancellationException()
                        check(partial.renameTo(destination)) { "无法保存安装包" }
                        onProgress(downloaded, total)
                        if (!continuation.isActive) throw CancellationException()
                        continuation.resume(UpdateDownloader.DownloadResult(destination))
                        complete = true
                    }
                } catch (e: Exception) {
                    partial.delete()
                    destination.delete()
                    if (continuation.isActive) {
                        continuation.resume(UpdateDownloader.DownloadResult(null, e.message ?: "下载失败"))
                    }
                } finally {
                    partial.delete()
                    if (!complete) destination.delete()
                }
            }
        })
    }
}
