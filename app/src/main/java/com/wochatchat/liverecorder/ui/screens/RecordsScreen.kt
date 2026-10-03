package com.wochatchat.liverecorder.ui.screens

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import com.wochatchat.liverecorder.data.DailyReport
import com.wochatchat.liverecorder.data.RecordHistoryEntry
import kotlinx.coroutines.flow.StateFlow
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
 * 录制记录页（6e R19 / U12 / R21 Phase 1.1）：筛选（全部/今日/平台）+ 搜索 + 排序
 * + 批量选择（长按多选、批量删除/分享）+ 记录卡片列表，
 * 每条支持播放（FileProvider）/分享/删除（确认弹窗）/打开所在文件夹。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RecordsScreen(
    viewModel: RecordsViewModel = viewModel(),
    onNavigateToDetail: (String) -> Unit = {},
) {
    val filtered by viewModel.filtered.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val deleteResult by viewModel.deleteResult.collectAsState()
    val batchDeleteResult by viewModel.batchDeleteResult.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    // R20：存储占比进度条
    val storageUsage by viewModel.storageUsage.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<RecordHistoryEntry?>(null) }
    // R21 Phase 1.1：批量选择模式（长按进入）
    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<RecordHistoryEntry>()) }
    var pendingBatchDelete by remember { mutableStateOf(false) }

    // V3-1：进入记录页时回收幽灵文件（未入库的落盘视频补写历史）
    LaunchedEffect(Unit) { viewModel.recoverGhosts() }
    LaunchedEffect(deleteResult) {
        deleteResult?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeDeleteResult()
        }
    }
    LaunchedEffect(batchDeleteResult) {
        batchDeleteResult?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeBatchDeleteResult()
        }
    }

    fun toggleSelect(entry: RecordHistoryEntry) {
        selected = if (entry in selected) selected - entry else selected + entry
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.batch_selected_count, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selected = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            selected = if (selected.size == filtered.size) emptySet() else filtered.toSet()
                        }) {
                            Text(
                                if (selected.size == filtered.size) stringResource(R.string.action_deselect_all)
                                else stringResource(R.string.action_select_all),
                            )
                        }
                        IconButton(onClick = { pendingBatchDelete = true }, enabled = selected.isNotEmpty()) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.desc_delete),
                                tint = if (selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        IconButton(
                            onClick = { shareBatch(context = context, entries = selected.toList()) },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.action_share))
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text(stringResource(R.string.screen_records_title)) })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StorageUsageBar(storageUsage)
            // R21 Phase 1.1：搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text(stringResource(R.string.records_search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desc_clear))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            FilterRow(filter, viewModel)
            // R21 Phase 1.1：排序 chips
            SortRow(sortMode, viewModel)
            StatsLine(stats.todayCount, stats.totalBytes)
            // Phase 8-8.2：近 7 天存储用量迷你柱状图
            StorageTrendChart(viewModel.allEntries)
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
                            selectionMode = selectionMode,
                            isSelected = entry in selected,
                            onToggleSelect = { toggleSelect(entry) },
                            onLongPress = {
                                selectionMode = true
                                selected = selected + entry
                            },
                            onDelete = { if (selectionMode) toggleSelect(entry) else pendingDelete = entry },
                            onOpenDetail = {
                                if (!selectionMode) {
                                    onNavigateToDetail(
                                        java.net.URLEncoder.encode(entry.savePath, "UTF-8"),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // R21 Phase 1.1：批量删除确认弹窗
    if (pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.batch_delete_confirm_title, selected.size)) },
            text = { Text(stringResource(R.string.batch_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.batchDelete(selected.toList())
                    selectionMode = false
                    selected = emptySet()
                    pendingBatchDelete = false
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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
        listOf(
            RecordsViewModel.TimeRange.ALL to stringResource(R.string.filter_all),
            RecordsViewModel.TimeRange.TODAY to stringResource(R.string.filter_today),
            RecordsViewModel.TimeRange.THIS_WEEK to stringResource(R.string.filter_this_week),
        ).forEach { (range, label) ->
            FilterChip(
                selected = filter.timeRange == range,
                onClick = { viewModel.setTimeRange(range) },
                label = { Text(label) },
            )
        }
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

/**
 * Phase 8-8.2：近 7 天录制用量迷你柱状图（Canvas，零额外依赖）。
 * 数据源 [entries]（全部记录），末位柱 = 今天并高亮；全零时整图隐藏。
 * Phase 8-8.3：图下方一行带宽估算——近 7 天日均流量 + 近 7 天平均码率。
 */
@Composable
private fun StorageTrendChart(entries: StateFlow<List<RecordHistoryEntry>>) {
    val all by entries.collectAsState()
    val todayStart = remember { DailyReport.dayStartOf(System.currentTimeMillis()) }
    val daily = remember(all, todayStart) {
        DailyReport.dailyBytes(all, todayStart, days = 7)
    }
    if (daily.all { it == 0L }) return

    val avg = remember(daily) { daily.sum() / daily.size }
    // 8.3：近 7 天平均码率（按有数据的条目加权）
    val weekEntries = remember(all, todayStart) {
        all.filter { it.endTimeMs >= todayStart - 6 * DailyReport.DAY_MS && it.durationMs > 0 }
    }
    val avgBitrate = if (weekEntries.isEmpty()) 0.0
    else DailyReport.bitrateMbps(
        weekEntries.sumOf { it.bytes },
        weekEntries.sumOf { it.durationMs },
    )

    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val maxBytes = daily.max().coerceAtLeast(1L)
            daily.forEachIndexed { idx, bytes ->
                val fraction = bytes.toFloat() / maxBytes
                // 柱高按 log 缩放避免单日巨大值压扁其他天（log1p(0)=0 仍为零高度）
                val logFraction = (kotlin.math.ln(fraction * 9f + 1f) / kotlin.math.ln(10f)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((12 + 36 * logFraction).dp)
                        .background(
                            color = if (idx == daily.lastIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(
                R.string.records_week_summary,
                StatsFormat.bytes(avg),
                if (avgBitrate > 0) "%.1f".format(avgBitrate) else "--",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** R21 Phase 1.1：排序行（时间 / 大小 / 时长，单选语义）。 */
@Composable
private fun SortRow(sortMode: RecordsViewModel.SortMode, viewModel: RecordsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            RecordsViewModel.SortMode.TIME to stringResource(R.string.sort_time),
            RecordsViewModel.SortMode.SIZE to stringResource(R.string.sort_size),
            RecordsViewModel.SortMode.DURATION to stringResource(R.string.sort_duration),
        ).forEach { (mode, label) ->
            FilterChip(
                selected = sortMode == mode,
                onClick = { viewModel.setSortMode(mode) },
                label = { Text(label) },
            )
        }
    }
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

/** 单条记录卡片（QW2/3/5/6 / R21 Phase 1.1）：平台徽标 + 主播名 + 标题 + 时长大字 + 格式标签 + 操作，
 * 支持批量选择（复选框 + 长按进入选择模式）与未完成红色标注。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(
    entry: RecordHistoryEntry,
    onDelete: (RecordHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
) {
    val context = LocalContext.current
    var showSegments by remember { mutableStateOf(false) }
    // R21 Phase 1.1：查看文件夹（应用内 BottomSheet，单文件显示其所在目录）
    var folderSheetPath by remember { mutableStateOf<String?>(null) }
    val isDir = File(entry.savePath).isDirectory
    val formatBadge = remember(entry.savePath) { formatBadgeText(entry.savePath) }

    if (showSegments) {
        RecordSegmentsSheet(savePath = entry.savePath, onDismiss = { showSegments = false })
    }
    folderSheetPath?.let { path ->
        RecordSegmentsSheet(savePath = path, onDismiss = { folderSheetPath = null })
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onOpenDetail() },
                onLongClick = onLongPress,
            ),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                    Spacer(Modifier.width(4.dp))
                }
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
                // R21 Phase 1.1：completed=false 红色标注
                if (!entry.completed) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            stringResource(R.string.records_incomplete_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
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
                // R21 Phase 1.1：查看文件夹（应用内 BottomSheet 列出所在目录全部文件）
                IconButton(onClick = {
                    val f = File(entry.savePath)
                    folderSheetPath = if (f.isFile) f.parent else f.absolutePath
                }) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.desc_open_folder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
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

/** R21 Phase 1.1：批量分享（ACTION_SEND_MULTIPLE，FileProvider 授权）。 */
private fun shareBatch(context: android.content.Context, entries: List<RecordHistoryEntry>) {
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
