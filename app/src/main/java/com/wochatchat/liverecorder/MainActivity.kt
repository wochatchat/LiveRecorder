package com.wochatchat.liverecorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.monitor.MonitorLoop
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
    val disabledUrls by viewModel.disabledUrls.collectAsState()
    val monitorEnabled by viewModel.monitorEnabled.collectAsState()
    val pushConfig by viewModel.pushConfig.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
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
            onDismiss = { showPushDialog = false },
            onConfirm = { enabled, type, api ->
                viewModel.setPushConfig(enabled, type, api)
                showPushDialog = false
            }
        )
    }
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
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = recordState is RecordController.RecordState.Resolving ||
        recordState is RecordController.RecordState.Recording ||
        recordState is RecordController.RecordState.Reconnecting
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(recordState, monitorState, disabled)
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
        Text(
            describeState(recordState),
            style = MaterialTheme.typography.labelSmall,
            color = stateColor(recordState, disabled)
        )
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
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "删除", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private fun describeState(state: RecordController.RecordState?): String = when (state) {
    null -> "未监控"
    is RecordController.RecordState.Resolving -> "解析直播源…"
    is RecordController.RecordState.Recording ->
        "录制中 · ${state.bytes / 1024 / 1024} MB · ${state.savePath.substringAfterLast('/')}"
    is RecordController.RecordState.Reconnecting ->
        "断流重连中(第 ${state.attempt} 次,${state.nextDelaySec}s 后) · ${state.message}"
    is RecordController.RecordState.Finished ->
        if (state.completed) "完成 · ${state.bytes / 1024 / 1024} MB · ${state.savePath.substringAfterLast('/')}"
        else "已停止 · ${state.bytes / 1024 / 1024} MB"
    is RecordController.RecordState.Failed -> "失败: ${state.message}"
}

/** 状态徽标：录制链路状态优先于监控状态，已停用置灰。 */
@Composable
private fun StatusBadge(
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
) {
    val (text, color) = when {
        disabled -> "已停用" to MaterialTheme.colorScheme.outline
        recordState is RecordController.RecordState.Recording -> "录制中" to MaterialTheme.colorScheme.error
        recordState is RecordController.RecordState.Reconnecting -> "断流重连" to MaterialTheme.colorScheme.tertiary
        recordState is RecordController.RecordState.Resolving -> "解析中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Live -> "直播中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Offline -> "未开播" to MaterialTheme.colorScheme.onSurfaceVariant
        monitorState is MonitorLoop.State.Error -> "失效" to MaterialTheme.colorScheme.error
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

/** 状态行颜色（录制链路优先；已停用一律置灰）。 */
private fun stateColor(
    recordState: RecordController.RecordState?,
    disabled: Boolean,
): Color = when {
    disabled -> Color(0xFF9E9E9E)
    recordState is RecordController.RecordState.Recording -> Color(0xFFD32F2F)
    recordState is RecordController.RecordState.Reconnecting -> Color(0xFF1976D2)
    recordState is RecordController.RecordState.Failed -> Color(0xFFD32F2F)
    else -> Color(0xFF666666)
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

/** HTTP 推送设置（2f）：开关 + 类型（ntfy/bark）+ 推送地址（多个用逗号分隔）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PushSettingsDialog(
    initial: PushConfig,
    onDismiss: () -> Unit,
    onConfirm: (enabled: Boolean, type: String, api: String) -> Unit,
) {
    var enabled by remember { mutableStateOf(initial.enabled) }
    var type by remember { mutableStateOf(initial.type) }
    var api by remember { mutableStateOf(initial.apis.joinToString(",")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HTTP 推送") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(enabled, type, api) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
