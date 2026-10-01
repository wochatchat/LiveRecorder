package com.wochatchat.liverecorder.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.ui.RecordsViewModel
import com.wochatchat.liverecorder.ui.StatsFormat
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import com.wochatchat.liverecorder.ui.components.PLATFORM_LABELS
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录制记录页（6e R19 / U12）：筛选（全部/今日/平台）+ 记录卡片列表，
 * 每条支持播放（FileProvider）/分享/删除（确认弹窗）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(viewModel: RecordsViewModel = viewModel()) {
    val filtered by viewModel.filtered.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val deleteResult by viewModel.deleteResult.collectAsState()
    // R20：存储占比进度条
    val storageUsage by viewModel.storageUsage.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<RecordHistoryEntry?>(null) }

    LaunchedEffect(deleteResult) {
        deleteResult?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeDeleteResult()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_records_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StorageUsageBar(storageUsage)
            FilterRow(filter, viewModel)
            StatsLine(stats.todayCount, stats.totalBytes)
            if (filtered.isEmpty()) {
                EmptyRecords()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.savePath + it.endTimeMs }) { entry ->
                        RecordCard(
                            entry = entry,
                            modifier = Modifier.animateItem(),
                            onDelete = { pendingDelete = entry },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_record_title)) },
            text = {
                Text(
                    entry.savePath.substringAfterLast('/') + "\n\n" +
                        stringResource(R.string.delete_record_text),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** 存储用量条（R20）：进度 + 剩余/总容量 caption。 */
@Composable
private fun StorageUsageBar(usage: com.wochatchat.liverecorder.storage.StorageUsage) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        usage.usedFraction?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            stringResource(
                R.string.records_storage_line,
                "%.1f".format(usage.freeGb), "%.1f".format(usage.totalGb),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 筛选行：全部/今日 + 平台 chips（单选语义，可再点取消）。 */
@Composable
private fun FilterRow(filter: RecordsViewModel.RecordFilter, viewModel: RecordsViewModel) {
    // 平台 chips 来自当前全部记录（不随筛选收窄，避免选项跳变）
    val allEntries by viewModel.allEntries.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = !filter.todayOnly,
            onClick = { viewModel.setTodayOnly(false) },
            label = { Text(stringResource(R.string.filter_all)) },
        )
        FilterChip(
            selected = filter.todayOnly,
            onClick = { viewModel.setTodayOnly(!filter.todayOnly) },
            label = { Text(stringResource(R.string.filter_today)) },
        )
        allEntries.map { platformKeyForUrl(it.url) }.distinct().forEach { key ->
            FilterChip(
                selected = filter.platformKey == key,
                onClick = {
                    viewModel.setPlatformKey(if (filter.platformKey == key) null else key)
                },
                label = { Text(PLATFORM_LABELS[key] ?: key) },
            )
        }
    }
}

/** 统计行：今日 X 条 · 合计 Y。 */
@Composable
private fun StatsLine(todayCount: Int, totalBytes: Long) {
    Text(
        stringResource(R.string.records_today_summary, todayCount, StatsFormat.bytes(totalBytes)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** 空态（6f R23：图标 + 快捷入口）。 */
@Composable
private fun EmptyRecords() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_records_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_records_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.empty_records_hint2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单条记录卡片（QW2/3/5/6）：平台徽标 + 主播名 + 标题 + 时长大字 + 格式标签 + 操作。 */
@Composable
private fun RecordCard(
    entry: RecordHistoryEntry,
    onDelete: (RecordHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showSegments by remember { mutableStateOf(false) }
    val isDir = File(entry.savePath).isDirectory
    val formatBadge = remember(entry.savePath) { formatBadgeText(entry.savePath) }

    if (showSegments) {
        RecordSegmentsSheet(savePath = entry.savePath, onDismiss = { showSegments = false })
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(platformKeyForUrl(entry.url))
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.anchorName.ifBlank { stringResource(R.string.records_unknown_anchor) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                // QW5：时长大字（醒目主色）
                Text(
                    StatsFormat.duration(entry.durationMs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // QW2：格式标签（MP4/TS/FLV/MKV）
                if (formatBadge != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            formatBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (entry.completed) Icons.Default.CheckCircle else Icons.Default.StopCircle,
                    contentDescription = if (entry.completed) stringResource(R.string.desc_completed) else stringResource(R.string.desc_stopped),
                    tint = if (entry.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (entry.title.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // QW3：复制直播标题
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("title", entry.title))
                            Toast.makeText(context, R.string.records_title_copied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.desc_copy_title),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (isDir) {
                // QW6：分段目录——文件数入口（点开 BottomSheet）+ 大小/时间
                val segCount = remember(entry.savePath) {
                    File(entry.savePath).listFiles()?.count { it.isFile } ?: 0
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.records_segments_count, segCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showSegments = true },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        StatsFormat.bytes(entry.bytes) + " · " + formatDate(entry.endTimeMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    StatsFormat.bytes(entry.bytes) + " · " + formatDate(entry.endTimeMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { openRecording(context, entry) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_play), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_play))
                }
                TextButton(onClick = { shareRecording(context, entry) }) {
                    Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_share))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.desc_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** QW6：分段录制目录 BottomSheet——全文件列表（文件名/大小/时间），点击播放、可分享。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSegmentsSheet(savePath: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember(savePath) {
        File(savePath).listFiles()
            ?.filter { it.isFile && VIDEO_EXTS.any { ext -> it.name.endsWith(ext) } }
            ?.sortedBy { it.name }
            ?: emptyList()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.records_segments_title, files.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            if (files.isEmpty()) {
                Text(stringResource(R.string.records_segments_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                files.forEachIndexed { idx, file ->
                    val ext = file.name.substringAfterLast('.', "").uppercase()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openFile(context, file) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${idx + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp),
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                ext,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                StatsFormat.bytes(file.length()) + " · " + formatDate(file.lastModified()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { shareFile(context, file) }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) { Text(stringResource(R.string.action_close)) }
        }
    }
}

/** 格式化标签（QW2）：单文件取扩展名；分段目录按 .ts 存在性判定（分段默认 TS）。 */
private fun formatBadgeText(savePath: String): String? {
    val f = File(savePath)
    if (f.isFile) return f.extension.uppercase().ifBlank { null }
    val children = f.listFiles() ?: return null
    return when {
        children.any { it.name.endsWith(".ts") } -> "TS"
        else -> children.maxByOrNull { it.length() }?.extension?.uppercase()?.ifBlank { null }
    }
}

private fun formatDate(endTimeMs: Long): String =
    if (endTimeMs <= 0) "--" else
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(endTimeMs))

/** 分段目录取第一个视频文件（OkHttp 单文件直接返回原路径）。 */
private fun playableFile(savePath: String): File? {
    val f = File(savePath)
    if (f.isFile) return f
    val videoExts = listOf(".mp4", ".ts", ".flv", ".mkv")
    return f.listFiles()
        ?.filter { file -> videoExts.any { file.name.endsWith(it) } }
        ?.maxByOrNull { it.lastModified() }
}

/** 调系统播放器播放录制文件（FileProvider 授权，同 RecordStatusLine 语义）。 */
private fun openRecording(context: android.content.Context, entry: RecordHistoryEntry) {
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
private fun shareRecording(context: android.content.Context, entry: RecordHistoryEntry) {
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
private val VIDEO_EXTS = listOf(".mp4", ".ts", ".flv", ".mkv")

/** QW6：单个文件播放（FileProvider 授权）。 */
private fun openFile(context: android.content.Context, file: File) {
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
private fun shareFile(context: android.content.Context, file: File) {
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
