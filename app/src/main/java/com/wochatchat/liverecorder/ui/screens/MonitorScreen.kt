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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.platform.PlatformRouter
import com.wochatchat.liverecorder.ui.MonitorViewModel
import com.wochatchat.liverecorder.ui.components.MonitorCard

/** 监控主页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel = viewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val urls by viewModel.urls.collectAsState()
    val recordStates by viewModel.recordStates.collectAsState()
    val monitorStates by viewModel.monitorStates.collectAsState()
    val unhealthyUrls by viewModel.unhealthyUrls.collectAsState()
    val disabledUrls by viewModel.disabledUrls.collectAsState()
    val monitorEnabled by viewModel.monitorEnabled.collectAsState()
    val roundInfo by viewModel.roundInfo.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var editUrl by remember { mutableStateOf<String?>(null) }

    // R11：操作反馈 Snackbar（添加/删除可撤销，4s 自动消失）
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notifyRemoved(url: String) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "已移除",
                actionLabel = "撤销",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.add(url)
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_monitor_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    // R12/U8：总开关改文字按钮，状态一目了然
                    TextButton(onClick = { viewModel.setMonitorEnabled(!monitorEnabled) }) {
                        Text(
                            if (monitorEnabled) "监控中" else "已暂停",
                            color = if (monitorEnabled) MaterialTheme.colorScheme.primary
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
                    MonitorCard(
                        url = url,
                        recordState = state,
                        monitorState = monitorStates[url],
                        disabled = url in disabledUrls,
                        unhealthy = url in unhealthyUrls,
                        roundInfo = roundInfo,
                        onRemove = {
                            viewModel.remove(url)
                            notifyRemoved(url)
                        },
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
            onConfirm = { urls ->
                urls.forEach { viewModel.add(it) }
                showAddDialog = false
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = if (urls.size == 1) "已添加 ${urls[0]}" else "已添加 ${urls.size} 个直播",
                        actionLabel = "撤销",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) urls.forEach { viewModel.remove(it) }
                }
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
fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    // R14/U10：多行/空格/逗号分隔均可，批量添加
    val urls = text.lines()
        .flatMap { it.split(',', '，', ' ', '\t') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val allSupported = urls.isNotEmpty() && urls.all { PlatformRouter.isSupported(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加直播间") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("粘贴直播间链接\n支持多行/逗号分隔批量添加") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4
                )
                if (urls.isNotEmpty() && !allSupported) {
                    Text(
                        "包含暂不支持的平台链接，点击「添加」仍会加入监控",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (urls.isNotEmpty()) onConfirm(urls) },
                enabled = urls.isNotEmpty()
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
