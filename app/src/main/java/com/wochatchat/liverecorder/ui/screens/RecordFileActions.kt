/*
 * V3-8：录制文件操作助手——从 RecordsScreen.kt 拆出（徽标/日期/首分片定位/播放/分享）。
 */
package com.wochatchat.liverecorder.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 格式化标签（QW2）：单文件取扩展名；分段目录按 .ts 存在性判定（分段默认 TS）。 */
internal fun formatBadgeText(savePath: String): String? {
    val f = File(savePath)
    if (f.isFile) return f.extension.uppercase().ifBlank { null }
    val children = f.listFiles() ?: return null
    return when {
        children.any { it.name.endsWith(".ts") } -> "TS"
        else -> children.maxByOrNull { it.length() }?.extension?.uppercase()?.ifBlank { null }
    }
}

internal fun formatDate(endTimeMs: Long): String =
    if (endTimeMs <= 0) "--" else
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(endTimeMs))

/** 分段目录取第一个视频文件（OkHttp 单文件直接返回原路径）。 */
internal fun playableFile(savePath: String): File? {
    val f = File(savePath)
    if (f.isFile) return f
    val videoExts = listOf(".mp4", ".ts", ".flv", ".mkv")
    return f.listFiles()
        ?.filter { file -> videoExts.any { file.name.endsWith(it) } }
        ?.maxByOrNull { it.lastModified() }
}

/** 调系统播放器播放录制文件（FileProvider 授权，同 RecordStatusLine 语义）。 */
internal fun openRecording(context: android.content.Context, entry: RecordHistoryEntry) {
    runCatching {
        val file = playableFile(entry.savePath) ?: error(context.getString(R.string.record_file_missing))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_play_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}

/** 系统分享（ACTION_SEND，FileProvider 授权）。 */
internal fun shareRecording(context: android.content.Context, entry: RecordHistoryEntry) {
    runCatching {
        val file = playableFile(entry.savePath) ?: error(context.getString(R.string.record_file_missing))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.share_record_title),
            )
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_share_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}

/** QW6 支持的视频扩展名（分段目录枚举用）。 */
internal val VIDEO_EXTS = listOf(".mp4", ".ts", ".flv", ".mkv")

/** QW6：单个文件播放（FileProvider 授权）。 */
internal fun openFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_play_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}

/** QW6：单个文件分享（FileProvider 授权）。 */
internal fun shareFile(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.share_record_title),
            )
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_share_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}

/** R21 Phase 1.1：批量分享（ACTION_SEND_MULTIPLE，FileProvider 授权）。 */
internal fun shareBatch(context: android.content.Context, entries: List<RecordHistoryEntry>) {
    runCatching {
        val uris = entries.mapNotNull { entry ->
            playableFile(entry.savePath)?.let {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            }
        }
        if (uris.isEmpty()) error(context.getString(R.string.record_file_missing))
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "video/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                context.getString(R.string.share_record_title),
            )
        )
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_share_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}
