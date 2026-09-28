package com.wochatchat.liverecorder.ui.screens

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
            title = { Text("删除录制文件？") },
            text = {
                Text(
                    "${entry.savePath.substringAfterLast('/')}\n\n" +
                        "删除后无法恢复（含分段目录内全部文件）。",
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
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
            "剩余 ${"%.1f".format(usage.freeGb)} GB / 共 ${"%.1f".format(usage.totalGb)} GB",
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
            label = { Text("全部") },
        )
        FilterChip(
            selected = filter.todayOnly,
            onClick = { viewModel.setTodayOnly(!filter.todayOnly) },
            label = { Text("今日") },
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
        "今日录制 $todayCount 条 · 合计 ${StatsFormat.bytes(totalBytes)}",
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

/** 单条记录卡片：平台徽标 + 主播名/标题 + 摘要行 + 播放/分享/删除。 */
@Composable
private fun RecordCard(
    entry: RecordHistoryEntry,
    onDelete: (RecordHistoryEntry) -> Unit,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(platformKeyForUrl(entry.url))
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.anchorName.ifBlank { "未知主播" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (entry.completed) Icons.Default.CheckCircle else Icons.Default.StopCircle,
                    contentDescription = if (entry.completed) "已完成" else "已停止",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (entry.title.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                StatsFormat.duration(entry.durationMs) +
                    " · " + StatsFormat.bytes(entry.bytes) +
                    " · " + formatDate(entry.endTimeMs) +
                    " · " + entry.savePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(onClick = { openRecording(context, entry) }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放")
                }
                TextButton(onClick = { shareRecording(context, entry) }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("分享")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
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
        val file = playableFile(entry.savePath) ?: error("文件不存在")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }.onFailure {
        Toast.makeText(context, "无法播放：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

/** 系统分享（ACTION_SEND，FileProvider 授权）。 */
private fun shareRecording(context: android.content.Context, entry: RecordHistoryEntry) {
    runCatching {
        val file = playableFile(entry.savePath) ?: error("文件不存在")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享录制文件",
            )
        )
    }.onFailure {
        Toast.makeText(context, "无法分享：${it.message}", Toast.LENGTH_SHORT).show()
    }
}
