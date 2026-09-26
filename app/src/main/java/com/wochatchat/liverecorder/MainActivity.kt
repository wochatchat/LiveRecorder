package com.wochatchat.liverecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wochatchat.liverecorder.ui.StatsFormat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.PushConfig
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.MonitorViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MonitorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(viewModel: MonitorViewModel = viewModel()) {
    val urls by viewModel.urls.collectAsState()
    val recordStates by viewModel.recordStates.collectAsState()
    val monitorStates by viewModel.monitorStates.collectAsState()
    val unhealthyUrls by viewModel.unhealthyUrls.collectAsState()
    val disabledUrls by viewModel.disabledUrls.collectAsState()
    val monitorEnabled by viewModel.monitorEnabled.collectAsState()
    val pushConfig by viewModel.pushConfig.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
    var showCookieDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf<String?>(null) }

    // Android 13+ 通知权限：前台服务可无权限运行，但常驻通知需要它（2a/2e 依赖）
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("直播监控") },
                actions = {
                    IconButton(onClick = { showPushDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "推送设置",
                            tint = if (pushConfig.isValid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = { viewModel.setMonitorEnabled(!monitorEnabled) }) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = if (monitorEnabled) "关闭监控" else "开启监控",
                            tint = if (monitorEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加直播")
            }
        }
    ) { padding ->
        if (urls.isEmpty()) {
            EmptyState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(urls, key = { it }) { url ->
                    val state = recordStates[url]
                    MonitorItem(
                        url = url,
                        recordState = state,
                        monitorState = monitorStates[url],
                        disabled = url in disabledUrls,
                        unhealthy = url in unhealthyUrls,
                        onRemove = { viewModel.remove(url) },
                        onEdit = { editUrl = url },
                        onToggleEnabled = { viewModel.setEnabled(url, it) },
                        onStart = { viewModel.startRecord(url) },
                        onStop = { viewModel.stopRecord(url) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                viewModel.add(url)
                showAddDialog = false
            }
        )
    }

    editUrl?.let { old ->
        EditUrlDialog(
            initial = old,
            onDismiss = { editUrl = null },
            onConfirm = { new ->
                viewModel.renameUrl(old, new)
                editUrl = null
            }
        )
    }

    if (showPushDialog) {
        PushSettingsDialog(
            initial = pushConfig,
            initialProxy = viewModel.proxySettings.collectAsState().value,
            initialConvertMp4 = viewModel.autoConvertMp4.collectAsState().value,
            initialSettings = viewModel.appSettings.collectAsState().value,
            onOpenCredentials = { showCookieDialog = true },
            onDismiss = { showPushDialog = false },
            onConfirm = { push, proxy, convertMp4, settings ->
                viewModel.setPushConfig(push.enabled, push.type, push.apis.joinToString(","))
                viewModel.setProxySettings(proxy)
                viewModel.setAutoConvertMp4(convertMp4)
                viewModel.setAppSettings(settings)
                showPushDialog = false
            }
        )
    }

    if (showCookieDialog) {
        CookieDialog(
            cookies = viewModel.cookies.collectAsState().value,
            credentials = viewModel.credentials.collectAsState().value,
            onDismiss = { showCookieDialog = false },
            onSaveCookie = { platform, cookie ->
                viewModel.setCookie(platform, cookie)
                showCookieDialog = false
            },
            onSaveCredential = { platform, user, pass ->
                viewModel.setCredential(platform, user, pass)
                showCookieDialog = false
            }
        )
    }

    if (showLogDialog) {
        LogDialog(onDismiss = { showLogDialog = false })
    }
}

/** 5d：运行日志页——查看日志尾部，支持刷新/清空/导出分享。 */
@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var content by remember { mutableStateOf(AppLog.readTail()) }
    var cleared by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志") },
        text = {
            Column {
                if (cleared) Text("日志已清空", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = content.ifBlank { "(暂无日志)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { content = AppLog.readTail() }) { Text("刷新") }
                TextButton(onClick = {
                    AppLog.clear()
                    content = ""
                    cleared = true
                }) { Text("清空") }
                TextButton(onClick = { exportLogs(context) }) { Text("导出分享") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 5d：导出日志——FileProvider 分享日志文件（验收：导出文件可读）。 */
private fun exportLogs(context: android.content.Context) {
    val files = AppLog.streamgetFiles() + AppLog.playurlFiles()
    if (files.isEmpty()) return
    val uris = ArrayList<Uri>()
    for (f in files) {
        try {
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f))
        } catch (e: Exception) {
            AppLog.e("LogDialog", "导出失败(${f.name}): ${e.message}")
        }
    }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uris[0])
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "分享日志"))
}

@Composable
private fun EmptyState(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有监控的直播间", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "点击右下角 + 添加直播间链接\n支持抖音 / 快手 / 虎牙 / 斗鱼 / B站等",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonitorItem(
    url: String,
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
    unhealthy: Boolean = false,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = recordState is RecordController.RecordState.Resolving ||
        recordState is RecordController.RecordState.Recording ||
        recordState is RecordController.RecordState.Reconnecting
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        ConfirmDeleteDialog(
            url = url,
            willStopRecording = recording,
            onConfirm = {
                onRemove()
                showConfirmDelete = false
            },
            onDismiss = { showConfirmDelete = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(recordState, monitorState, disabled, unhealthy)
            Spacer(Modifier.weight(1f))
            // 单条启停（2g）：停用后不参与轮询与自动录制（上游 # 注释行语义）
            Switch(checked = !disabled, onCheckedChange = onToggleEnabled)
        }
        Text(
            url,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = if (disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
        )
        if (monitorState is MonitorLoop.State.Live) {
            Text(
                "${monitorState.anchorName}「${monitorState.title}」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (recordState is RecordController.RecordState.Recording) {
            // 5c：录制统计行（时长/大小/码率，每秒刷新）
            RecordingStatsLine(recordState)
        } else {
            Text(
                describeState(recordState),
                style = MaterialTheme.typography.labelSmall,
                color = stateColor(recordState, disabled)
            )
        }
        Row {
            IconButton(onClick = if (recording) onStop else onStart, enabled = !disabled) {
                Icon(
                    if (recording) Icons.Default.Stop
                    else Icons.Default.PlayArrow,
                    contentDescription = if (recording) "停止" else "录制",
                    tint = if (recording)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onEdit, enabled = !disabled) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { showConfirmDelete = true }) {
                Icon(Icons.Default.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun stateColor(
    recordState: RecordController.RecordState?,
    disabled: Boolean,
): Color = when {
    disabled -> MaterialTheme.colorScheme.outline
    recordState is RecordController.RecordState.Recording -> MaterialTheme.colorScheme.error
    recordState is RecordController.RecordState.Reconnecting -> MaterialTheme.colorScheme.tertiary
    recordState is RecordController.RecordState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant // Kotlin 2.0: sealed class when as expression requires else
}

@Composable
private fun describeState(state: RecordController.RecordState?): String = when {
    state == null -> "未监控"
    state is RecordController.RecordState.Resolving -> "解析直播源…"
    state is RecordController.RecordState.Recording ->
        "录制中 · ${StatsFormat.duration(state.durationMs)} · ${StatsFormat.bytes(state.bytes)} · ${state.savePath.substringAfterLast('/')}"
    state is RecordController.RecordState.Reconnecting ->
        "断流重连中(第 ${state.attempt} 次,${state.nextDelaySec}s 后) · ${state.message}"
    state is RecordController.RecordState.Finished ->
        if (state.completed)
            "完成 · ${StatsFormat.duration(state.durationMs)} · ${StatsFormat.bytes(state.bytes)} · ${state.savePath.substringAfterLast('/')}"
        else "已停止 · ${StatsFormat.duration(state.durationMs)} · ${StatsFormat.bytes(state.bytes)}"
    state is RecordController.RecordState.Failed -> "失败: ${state.message}"
    else -> "未知状态: $state"
}

/** R2：删除确认对话框——录制中提示停止风险，非录制时确认移除。 */
@Composable
private fun ConfirmDeleteDialog(
    url: String,
    willStopRecording: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (willStopRecording) "停止并移除监控？" else "移除监控？") },
        text = {
            Text(
                if (willStopRecording) {
                    "当前正在录制或解析直播源，立即移除将停止本次录制且无法恢复。确认移除？"
                } else {
                    "将从监控列表移除此直播间。已录制的文件不受影响。"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 5c 录制统计行：时长/大小/平均码率，每秒自刷新（时长剔除解析/重连等待）。 */
@Composable
private fun RecordingStatsLine(state: RecordController.RecordState.Recording) {
    // 两次状态发射之间也保持走秒：在最近快照的 durationMs 基础上累加本秒表
    var extraSec by remember { mutableStateOf(0L) }
    LaunchedEffect(state) {
        extraSec = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            extraSec++
        }
    }
    val shownMs = state.durationMs + extraSec * 1000
    Text(
        "录制中 · ${StatsFormat.duration(shownMs)} · ${StatsFormat.bytes(state.bytes)}" +
            " · ${StatsFormat.bitrate(state.bytes, shownMs)}" +
            " · ${state.savePath.substringAfterLast('/')}",
        style = MaterialTheme.typography.labelSmall,
        color = stateColor(state, disabled = false),
        maxLines = 2,
    )
}

/** 状态徽标：录制链路状态优先于监控状态，已停用置灰；连续失败（4c）置灰「失效」。 */
@Composable
private fun StatusBadge(
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
    unhealthy: Boolean = false,
) {
    val (text, color) = when {
        disabled -> "已停用" to MaterialTheme.colorScheme.outline
        recordState is RecordController.RecordState.Recording -> "录制中" to MaterialTheme.colorScheme.error
        recordState is RecordController.RecordState.Reconnecting -> "断流重连" to MaterialTheme.colorScheme.tertiary
        recordState is RecordController.RecordState.Resolving -> "解析中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Live -> "直播中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Offline -> "未开播" to MaterialTheme.colorScheme.onSurfaceVariant
        // 4c：连续失败达阈值 → 平台失效置灰；偶发错误仍红色「错误」提示
        monitorState is MonitorLoop.State.Error && unhealthy -> "失效" to MaterialTheme.colorScheme.outline
        monitorState is MonitorLoop.State.Error -> "错误" to MaterialTheme.colorScheme.error
        else -> "待检测" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加直播间") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("粘贴直播间链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            IconButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "取消")
            }
        }
    )
}

/** 编辑监控条目（2g）：替换 url，保持列表位置与启停状态。 */
@Composable
private fun EditUrlDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑直播间") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("直播间链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank() && text.trim() != initial
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 设置（2f 推送 + 3h 转码开关 + 5a 全局配置，常用/高级两级）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PushSettingsDialog(
    initial: PushConfig,
    initialProxy: ProxySettings,
    initialConvertMp4: Boolean,
    initialSettings: AppSettings = AppSettings(),
    onOpenCredentials: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (push: PushConfig, proxy: ProxySettings, convertMp4: Boolean, settings: AppSettings) -> Unit,
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var type by remember { mutableStateOf(initial.type) }
    var api by remember { mutableStateOf(initial.apis.joinToString(",")) }
    var convertMp4 by remember { mutableStateOf(initialConvertMp4) }
    var proxyEnabled by remember { mutableStateOf(initialProxy.enabled) }
    var proxyAddr by remember { mutableStateOf(initialProxy.addr) }
    var proxyPlatforms by remember { mutableStateOf(initialProxy.platformsCsv()) }
    // 5a：全局录制设置 + 常用/高级分页
    var settings by remember { mutableStateOf(initialSettings) }
    var tab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("常用", "高级").forEachIndexed { idx, label ->
                        FilterChip(selected = tab == idx, onClick = { tab = idx }, label = { Text(label) })
                    }
                }
                if (tab == 0) {
                    Text(
                        "开播/关播时推送到 ntfy 或 bark。地址支持多个，用逗号分隔。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用推送", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ntfy" to "ntfy", "bark" to "bark").forEach { (value, label) ->
                            FilterChip(
                                selected = type == value,
                                onClick = { type = value },
                                label = { Text(value) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = api,
                        onValueChange = { api = it },
                        placeholder = { Text(if (type == "bark") "https://api.day.app/你的Key" else "https://ntfy.sh/你的主题") },
                        label = { Text("推送地址") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    HorizontalDivider()
                    // 5a：画质（上游「原画|超清|高清|标清|流畅」，经 get_quality_code 映射）
                    Text("录制画质", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("原画", "超清", "高清", "标清", "流畅").forEach { q ->
                            FilterChip(
                                selected = settings.quality == q,
                                onClick = { settings = settings.copy(quality = q) },
                                label = { Text(q) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = if (settings.loopIntervalSec == 0L) "" else settings.loopIntervalSec.toString(),
                        onValueChange = { text ->
                            text.toLongOrNull()?.let {
                                settings = settings.copy(loopIntervalSec = it.coerceIn(60, 86400))
                            }
                        },
                        label = { Text("循环时间(秒) — 每轮检查开播的间隔") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    HorizontalDivider()
                    // 4b：平台 Cookie / 登录账密入口
                    OutlinedButton(
                        onClick = onOpenCredentials,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("平台 Cookie / 账号密码…")
                    }
                } else {
                    // 5a 高级段：对齐上游 config.ini 高级项
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("只推送通知不录制")
                            Text(
                                "开播时仅推送，不自动录制",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.onlyNotify, onCheckedChange = { settings = settings.copy(onlyNotify = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开播推送", modifier = Modifier.weight(1f))
                        Switch(checked = settings.pushOnLive, onCheckedChange = { settings = settings.copy(pushOnLive = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("关播推送", modifier = Modifier.weight(1f))
                        Switch(checked = settings.pushOnOffline, onCheckedChange = { settings = settings.copy(pushOnOffline = it) })
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("分段录制")
                            Text(
                                "关闭时 FLV 直下为单文件（不支持 m3u8）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.segmented, onCheckedChange = { settings = settings.copy(segmented = it) })
                    }
                    OutlinedTextField(
                        value = settings.segmentTimeSec.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let { settings = settings.copy(segmentTimeSec = it.coerceIn(10, 86400)) }
                        },
                        label = { Text("视频分段时间(秒)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("强制启用 https 录制")
                            Text(
                                "直播源 http:// 强制改写为 https://",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.forceHttps, onCheckedChange = { settings = settings.copy(forceHttps = it) })
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("录制完成后自动转 MP4")
                            Text(
                                "TS 分片转 mp4（无需重编码）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = convertMp4, onCheckedChange = { convertMp4 = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("转码后删除原 TS 分片")
                            Text(
                                "对齐上游「追加格式后删除原文件」",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.deleteOriginalOnConvert,
                            onCheckedChange = { settings = settings.copy(deleteOriginalOnConvert = it) }
                        )
                    }
                    HorizontalDivider()
                    // 5b：文件命名规则（上游 config.ini [录制设置] 命名 5 项）
                    Text("文件命名", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("保存文件夹以作者区分")
                            Text(
                                "下载/{平台}/{主播}/…（默认开）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.folderByAuthor, onCheckedChange = { settings = settings.copy(folderByAuthor = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("保存文件夹以时间区分")
                            Text(
                                "追加一层当天日期（2026-09-24）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.folderByTime, onCheckedChange = { settings = settings.copy(folderByTime = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("保存文件夹以标题区分")
                            Text(
                                "再按直播标题/日期+标题建目录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.folderByTitle, onCheckedChange = { settings = settings.copy(folderByTitle = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("文件名包含标题")
                            Text(
                                "{主播}_{标题}_{时间}.flv",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.filenameByTitle, onCheckedChange = { settings = settings.copy(filenameByTitle = it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("去除名称中的表情符号")
                            Text(
                                "主播名与标题同步生效（默认开）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = settings.cleanEmoji, onCheckedChange = { settings = settings.copy(cleanEmoji = it) })
                    }
                } else {
                // 4a：per-platform 代理（对齐上游「是否使用代理ip / 代理地址 / 使用代理录制的平台」）
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("使用代理录制")
                        Text(
                            "仅下方平台列表命中的链接走代理（海外平台用）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = proxyEnabled, onCheckedChange = { proxyEnabled = it })
                }
                OutlinedTextField(
                    value = proxyAddr,
                    onValueChange = { proxyAddr = it },
                    placeholder = { Text("socks5://127.0.0.1:7890 或 http://127.0.0.1:7890") },
                    label = { Text("代理地址") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = proxyPlatforms,
                    onValueChange = { proxyPlatforms = it },
                    placeholder = { Text("tiktok, twitch, ...") },
                    label = { Text("走代理的平台（逗号分隔关键词）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    PushConfig(enabled, type, listOf(api)),
                    ProxySettings(
                        enabled = proxyEnabled,
                        addr = proxyAddr.trim(),
                        platforms = ProxySettings.parsePlatforms(
                            proxyPlatforms,
                            fallback = initialProxy.platforms,
                        ),
                    ),
                    convertMp4,
                    settings,
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 平台 Cookie / 登录账密录入（4b，对齐上游 config.ini [Cookie] + [账号密码] 段）。 */
@Composable
private fun CookieDialog(
    cookies: Map<String, String>,
    credentials: Map<String, Pair<String, String>>,
    onDismiss: () -> Unit,
    onSaveCookie: (platform: String, cookie: String) -> Unit,
    onSaveCredential: (platform: String, username: String, password: String) -> Unit,
) {
    val platforms = AuthStore.ALL_PLATFORMS
    var selectedKey by remember { mutableStateOf(platforms.first().key) }
    var expanded by remember { mutableStateOf(false) }
    var cookie by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // 切换平台时回填当前值（已保存的 cookie / 账密）
    LaunchedEffect(selectedKey) {
        cookie = cookies[selectedKey].orEmpty()
        val cred = credentials[selectedKey]
        username = cred?.first.orEmpty()
        password = cred?.second.orEmpty()
    }

    val isLoginPlatform = selectedKey in AuthStore.LOGIN_PLATFORMS.map { it.key }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("平台 Cookie / 账号密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 平台选择（下拉，含全部 50 平台）
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(AuthStore.labelOf(selectedKey))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        platforms.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.label} (${p.key})") },
                                onClick = {
                                    selectedKey = p.key
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                if (isLoginPlatform) {
                    Text(
                        "该平台使用账密登录，保存后录制时自动登录获取 cookie",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("账号") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "粘贴浏览器登录后的 Cookie 串（key1=v1; key2=v2）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "清空后保存即删除（等价上游配置项置空）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isLoginPlatform) onSaveCredential(selectedKey, username, password)
                else onSaveCookie(selectedKey, cookie)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
