package com.wochatchat.liverecorder.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * V3-7：APK 应用内下载器。
 * 应用内流式下载（真实进度、协程取消），
 * 下载完成后经 FileProvider 发 ACTION_VIEW 安装 Intent。
 */
object UpdateDownloader {

    private const val APK_DIR = "apk_cache"

    /** 下载结果：成功返回本地 File，失败返回错误信息。 */
    data class DownloadResult(val apkFile: File?, val error: String? = null)

    /** 下载至应用私有缓存，取消调用协程即可取消网络请求。 */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult = ApkDownload.download(File(context.cacheDir, APK_DIR), url, onProgress)

    /**
     * 发起安装：经 FileProvider 授权 content://，系统包安装器接管。
     * 未授予「安装未知应用」权限时返回 false，由调用方引导用户去设置。
     */
    fun installApk(context: Context, apk: File): Boolean {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) return false
            require(apk.isFile && apk.length() > 0) { "安装包不存在" }
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

    /** 清理缓存中的安装包。 */
    fun cleanup(context: Context) {
        runCatching { File(context.cacheDir, APK_DIR).listFiles()?.forEach { it.delete() } }
            .onFailure { android.util.Log.w("UpdateDownloader", "APK 缓存清理失败: ${it.message}") }
    }
}
