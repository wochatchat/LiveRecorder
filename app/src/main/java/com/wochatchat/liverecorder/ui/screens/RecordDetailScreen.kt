package com.wochatchat.liverecorder.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import com.wochatchat.liverecorder.ui.StatsFormat
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 单文件元数据（时长/分辨率/码率）。 */
private data class FileMeta(
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bitrateBps: Long = 0,
)

private fun readFileMeta(file: File): FileMeta {
    return try {
        val retriever = android.media.MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
        retriever.release()
        FileMeta(durationMs = dur, width = w, height = h, bitrateBps = bitrate)
    } catch (_: Exception) {
        FileMeta()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailScreen(
    savePath: String,
    onBack: () -> Unit,
    onMerge: (List<File>, String) -> Unit,
    onPlayAll: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 从历史记录查找该条目（未匹配时降级：用 savePath 构造空 entry）
    val historyStore = remember {
        (context.applicationContext as com.wochatchat.liverecorder.RecorderApp).historyStore
    }
    val allEntries by historyStore.entries.collectAsState(initial = emptyList())
    val entry = remember(allEntries, savePath) {
        allEntries.firstOrNull { it.savePath == savePath } ?: RecordHistoryEntry(
            url = "", platform = "", anchorName = "", title = "",
            savePath = savePath, endTimeMs = 0, durationMs = 0, bytes = 0, completed = false,
        )
    }

    val files = remember(entry.savePath) {
        val f = File(entry.savePath)
        if (f.isDirectory) {
            f.listFiles()?.filter { isVideoFile(it) }?.sortedBy { it.lastModified() } ?: emptyList()
        } else if (isVideoFile(f)) listOf(f) else emptyList()
    }
    val fileMetas = remember(entry.savePath) { files.map { readFileMeta(it) } }

    var pendingDeleteFile by remember { mutableStateOf<File?>(null) }
    var mergeDialogOpen by remember { mutableStateOf(false) }
    var mergeProgress by remember { mutableFloatStateOf(-1f) }
    var mergeError by remember { mutableStateOf<String?>(null) }

    // 模拟进度（真实进度需解析 ffmpeg stderr 输出，后续可改）
    fun startMerge() {
        scope.launch {
            mergeProgress = 0f
            repeat(20) {
                delay(200)
                mergeProgress = (it + 1) / 20f
            }
            onMerge(files, entry.savePath.substringAfterLast('/'))
            mergeProgress = 1f
            delay(500)
            mergeProgress = -1f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            entry.anchorName.ifBlank { stringResource(R.string.records_unknown_anchor) },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.title.isNotBlank()) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", entry.url))
                        Toast.makeText(context, R.string.detail_url_copied, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = null) }
                    IconButton(onClick = onPlayAll) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_play))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RecordInfoHeader(entry, onPlay = onPlayAll) }
            item { RecordStatsRow(fileMetas, entry) }
            if (files.size > 1) { item { RecordTimeline(files, fileMetas) } }
            item {
                Text(
                    stringResource(R.string.detail_segments_title, files.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            itemsIndexed(files) { idx, file ->
                SegmentFileRow(
                    index = idx + 1,
                    file = file,
                    meta = fileMetas.getOrElse(idx) { FileMeta() },
                    onPlay = { openFile(context, file) },
                    onShare = { shareFile(context, file) },
                    onDelete = { pendingDeleteFile = file },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { mergeDialogOpen = true },
                    enabled = files.size >= 2,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.detail_merge_export))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    // 删除文件确认
    pendingDeleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text(stringResource(R.string.detail_delete_file_title)) },
            text = { Text(file.name) },
            confirmButton = {
                TextButton(onClick = {
                    if (file.delete()) Toast.makeText(context, R.string.detail_delete_file_done, Toast.LENGTH_SHORT).show()
                    else Toast.makeText(context, R.string.detail_delete_file_failed, Toast.LENGTH_SHORT).show()
                    pendingDeleteFile = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    // 合并确认/进度弹窗
    if (mergeDialogOpen) {
        AlertDialog(
            onDismissRequest = { if (mergeProgress < 0f) mergeDialogOpen = false },
            title = { Text(stringResource(R.string.detail_merge_title)) },
            text = {
                Column {
                    if (mergeProgress >= 0f) {
                        Text(stringResource(R.string.detail_merge_progress, (mergeProgress * 100).toInt()))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { mergeProgress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(stringResource(R.string.detail_merge_confirm_text, files.size))
                    }
                    mergeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (mergeProgress < 0f) {
                    TextButton(onClick = { mergeDialogOpen = false; startMerge() }) {
                        Text(stringResource(R.string.detail_merge_start))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { mergeDialogOpen = false }, enabled = mergeProgress < 0f) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}


// ---- 子组件 ----

@Composable
private fun RecordInfoHeader(entry: RecordHistoryEntry, onPlay: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformBadge(platformKeyForUrl(entry.url))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.anchorName.ifBlank { stringResource(R.string.records_unknown_anchor) },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.title.isNotBlank()) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.action_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordStatsRow(metas: List<FileMeta>, entry: RecordHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem(stringResource(R.string.detail_stat_duration), StatsFormat.duration(entry.durationMs))
            StatItem(stringResource(R.string.detail_stat_size), StatsFormat.bytes(entry.bytes))
            val res = metas.filter { it.width > 0 }.maxByOrNull { it.width }
            if (res != null) {
                StatItem(stringResource(R.string.detail_stat_resolution), "${res.width}×${res.height}")
                if (res.bitrateBps > 0) {
                    StatItem(stringResource(R.string.detail_stat_bitrate), "%.1f Mbps".format(res.bitrateBps / 1_000_000.0))
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun RecordTimeline(files: List<File>, metas: List<FileMeta>) {
    val totalDuration = metas.sumOf { it.durationMs }.coerceAtLeast(1L)
    val segmentColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.outline,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.detail_timeline_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                files.forEachIndexed { idx, _ ->
                    val meta = metas.getOrElse(idx) { FileMeta() }
                    val weight = (meta.durationMs.toFloat() / totalDuration).coerceAtLeast(0.02f)
                    Box(
                        modifier = Modifier
                            .weight(weight.toDouble().coerceAtMost(0.98))
                            .fillMaxSize()
                            .background(segmentColors[idx % 4].copy(alpha = 0.6f)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                files.forEachIndexed { idx, _ ->
                    val meta = metas.getOrElse(idx) { FileMeta() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(segmentColors[idx % 4]),
                            contentAlignment = Alignment.Center,
                        ) { Text("${idx + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                        Spacer(Modifier.width(4.dp))
                        Text(StatsFormat.duration(meta.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}@Composable
private fun SegmentFileRow(
    index: Int, file: File, meta: FileMeta,
    onPlay: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit,
) {
    val ext = file.name.substringAfterLast('.', "").uppercase()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$index", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraSmall) {
                Text(ext, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${StatsFormat.bytes(file.length())} · ${formatDate(file.lastModified())}" + if (meta.durationMs > 0) " · ${StatsFormat.duration(meta.durationMs)}" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.action_play), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.desc_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun formatDate(ms: Long): String = if (ms <= 0) "--" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun openFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure { Toast.makeText(context, context.getString(R.string.toast_play_failed, it.message), Toast.LENGTH_SHORT).show() }
}

private fun shareFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, context.getString(R.string.share_record_title)))
    }.onFailure { Toast.makeText(context, context.getString(R.string.toast_share_failed, it.message), Toast.LENGTH_SHORT).show() }
}
