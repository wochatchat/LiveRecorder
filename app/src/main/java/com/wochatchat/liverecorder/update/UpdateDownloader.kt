package com.wochatchat.liverecorder.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * V3-7：APK 应用内下载器。
 * 走系统 DownloadManager（可断点续传、通知栏可见），
 * 下载完成后经 FileProvider 发 ACTION_VIEW 安装 Intent。
 */
object UpdateDownloader {

    private const val APK_DIR = "apk_cache"

    /** 下载结果：成功返回本地 File，失败返回错误信息。 */
    data class DownloadResult(val apkFile: File?, val error: String? = null)

    /**
     * 下载 APK 到 cache/apk_cache/，挂起直至完成或失败。
     * @param onEnqueued 任务入队后回调 downloadId，用于取消
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onEnqueued: (Long) -> Unit = {},
    ): DownloadResult =
        suspendCancellableCoroutine { cont ->
            val fileName = "liverecorder-update.apk"
            val dir = File(context.cacheDir, APK_DIR).apply { mkdirs() }
            // 清理旧包，避免堆积
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, fileName)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("LiveRecorder 更新包")
                setDescription("正在下载新版本安装包")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                setDestinationUri(Uri.fromFile(dest))
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = try {
                dm.enqueue(request)
            } catch (e: Exception) {
                cont.resume(DownloadResult(null, e.message ?: "下载请求失败"))
                return@suspendCancellableCoroutine
            }
            onEnqueued(downloadId)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                    val result = queryResult(context, downloadId, dest)
                    context.unregisterReceiver(this)
                    if (cont.isActive) cont.resume(result)
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }

    /** 查询下载结果：成功返回文件，失败返回错误信息。 */
    private fun queryResult(context: Context, id: Long, dest: File): DownloadResult {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        if (dest.exists() && dest.length() > 0) {
                            DownloadResult(dest)
                        } else {
                            DownloadResult(null, "下载文件不存在")
                        }
                    }
                    else -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_REASON))
                        DownloadResult(null, "下载失败（状态 $status，原因 $reason）")
                    }
                }
            }
        }
        return DownloadResult(null, "下载任务丢失")
    }

    private const val COLUMN_REASON = "reason"

    /**
     * 发起安装：经 FileProvider 授权 content://，系统包安装器接管。
     * 未授予「安装未知应用」权限时返回 false，由调用方引导用户去设置。
     */
    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 引导用户到「安装未知应用」授权页。 */
    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }

    /** 取消下载任务并清理残留文件。 */
    fun cancel(context: Context, downloadId: Long) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.remove(downloadId)
        }
    }

    /** 清理缓存中的安装包。 */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, APK_DIR).listFiles()?.forEach { it.delete() } }
    }
}
