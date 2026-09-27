package com.wochatchat.liverecorder.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.MonitorViewModel
import com.wochatchat.liverecorder.ui.StatsFormat
import com.wochatchat.liverecorder.ui.components.StatusBadge

/** 监控主页 */
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
                title = { Text(stringResource(R.string.screen_monitor_title)) },
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
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.fab_add_live))
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


@Composable
fun LogDialog(onDismiss: () -> Unit) {
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
                TextButton(onClick = { content = AppLog.readTail() }) { Text(stringResource(R.string.action_refresh)) }
                TextButton(onClick = {
                    AppLog.clear()
                    content = ""
                    cleared = true
                }) { Text(stringResource(R.string.action_clear)) }
                TextButton(onClick = { exportLogs(context) }) { Text(stringResource(R.string.action_export_share)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}


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
        Text(stringResource(R.string.empty_monitor_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_monitor_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


@Composable
fun MonitorItem(
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
fun stateColor(
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
fun describeState(state: RecordController.RecordState?): String = when {
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


@Composable
fun ConfirmDeleteDialog(
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
                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}


@Composable
fun RecordingStatsLine(state: RecordController.RecordState.Recording) {
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


@Composable
fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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


@Composable
fun EditUrlDialog(
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
