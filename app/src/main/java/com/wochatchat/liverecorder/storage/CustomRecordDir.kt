package com.wochatchat.liverecorder.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.wochatchat.liverecorder.data.AppLog
import java.io.File

/**
 * V3-6：自定义录制目录。
 *
 * 实现方式取「所有文件访问」（MANAGE_EXTERNAL_STORAGE，API 30+）而非 SAF
 * DocumentFile 双轨：授权后 ffmpeg/OkHttp/播放/分享/删除等既有 File 链路
 * 全部直接可用，避免 content:// 全链路改造的回归风险。
 *
 * 目录选择仍走 SAF 目录选择器（ACTION_OPEN_DOCUMENT_TREE），选完把 tree uri
 * 映射回真实文件系统路径（仅支持 externalstorage 提供方，即本机存储/SD 卡）。
 * 未授权或映射失败回落应用私有目录 filesDir/downloads。
 */
object CustomRecordDir {

    private const val TAG = "CustomRecordDir"

    /** 应用私有默认录制目录（与历史行为一致）。 */
    fun defaultDir(context: Context): File = File(context.filesDir, "downloads")

    /**
     * 当前是否具备写自定义目录的权限：
     * API 30+ 看「所有文件访问」；更低版本看 WRITE_EXTERNAL_STORAGE 授权。
     */
    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** 引导用户到系统授权页（所有文件访问）。低版本回落运行时权限由调用方请求。 */
    fun openAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // 尝试下一个
            }
        }
        AppLog.w(TAG, "打开所有文件访问设置页失败")
    }

    /**
     * SAF 目录选择结果 → 真实文件系统路径。
     * 仅支持 externalstorage 提供方（primary:/SD 卡 uuid:）；其余提供方返回 null。
     */
    fun pathFromTreeUri(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val treeId = (uri.lastPathSegment ?: return null).substringAfter("tree/", "").ifEmpty { return null }
        return pathFromTreeId(treeId, Environment.getExternalStorageDirectory()?.absolutePath ?: return null)
    }

    /** treeId（如 "primary:Recordings"/"6236-4123:x/y"）→ 路径；[primaryRoot] 注入便于测试。 */
    internal fun pathFromTreeId(treeId: String, primaryRoot: String): String? {
        val volume = treeId.substringBefore(":", missingDelimiterValue = "").ifEmpty { return null }
        val dir = treeId.substringAfter(":", missingDelimiterValue = "").removeSuffix(":")
        val volumePath = when (volume) {
            "primary" -> primaryRoot
            else -> "/storage/$volume"
        }
        return if (dir.isEmpty()) volumePath else "$volumePath/$dir"
    }

    /**
     * 解析当前生效的录制根目录（纯逻辑，便于测试）。
     * @param customPath 设置中的自定义目录绝对路径（空 = 未启用）
     * @param accessGranted 是否具备外部存储写权限
     * @param defaultDir 应用私有默认目录
     */
    fun resolveOrDefault(customPath: String, accessGranted: Boolean, defaultDir: File): File {
        if (customPath.isBlank() || !accessGranted) return defaultDir
        val dir = File(customPath)
        // 建目录失败（路径非法/只读）时回落默认
        if (!dir.exists() && !dir.mkdirs()) return defaultDir
        return if (dir.isDirectory) dir else defaultDir
    }

    /** 设置页展示用：取路径最后一段；空路径显示默认文案键由调用方处理。 */
    fun labelFor(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
}
