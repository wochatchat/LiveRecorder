package com.wochatchat.liverecorder.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.wochatchat.liverecorder.ui.components.FloatingOpPanel
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl
import com.wochatchat.liverecorder.ui.navigation.FocusRouter
import com.wochatchat.liverecorder.ui.navigation.ShareIntentRouter

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
    // 6f R23：添加对话框预填（空态「示例链接」入口复用同一对话框）
    var addInitial by remember { mutableStateOf("") }
    // 6f R22：通知点击直达——滚动定位 + 高亮当前条目
    var highlightedUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // Phase 2：分享/剪贴板队列——队首 URL 展示确认卡片，处理完后自动取下一条
    var currentPendingUrl by remember { mutableStateOf<String?>(null) }
    // 悬浮卡片中点「添加」后展示的 Snackbar 消息
    var panelSnackbarMsg by remember { mutableStateOf<String?>(null) }

    // 当 ShareIntentRouter 队列非空且当前无展示时，自动取出队首并展示
    val pendingUrls by ShareIntentRouter.pendingUrls.collectAsState()
    LaunchedEffect(pendingUrls) {
        if (currentPendingUrl == null && pendingUrls.isNotEmpty()) {
            currentPendingUrl = ShareIntentRouter.dequeue()
        }
    }

    // R11：操作反馈 Snackbar（添加/删除可撤销，4s 自动消失）
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun notifyRemoved(url: String) {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.snackbar_removed),
                actionLabel = context.getString(R.string.action_undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.add(url)
        }
    }

    // Android 13+ 通知权限：前台服务可无权限运行，但常驻通知需要它（2a/2e 依赖）
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 6f R22：消费 FocusRouter（通知点击直达）——urls 就绪后滚动定位并高亮 4s
    val focusUrl by FocusRouter.focusUrl.collectAsState()
    LaunchedEffect(focusUrl, urls) {
        val u = focusUrl ?: return@LaunchedEffect
        val idx = urls.indexOf(u)
        if (idx < 0) return@LaunchedEffect // 列表未就绪，effect 将随 urls 变化重跑
        listState.animateScrollToItem(idx)
        highlightedUrl = u
        FocusRouter.clear()
        kotlinx.coroutines.delay(4000)
        if (highlightedUrl == u) highlightedUrl = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_monitor_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.desc_settings))
                    }
                    // R12/U8：总开关改文字按钮，状态一目了然
                    TextButton(onClick = { viewModel.setMonitorEnabled(!monitorEnabled) }) {
                        Text(
                            if (monitorEnabled) stringResource(R.string.monitor_state_running) else stringResource(R.string.monitor_state_paused),
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
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            if (urls.isEmpty()) {
                EmptyState(
                    PaddingValues(0.dp),
                    onAdd = {
                        addInitial = ""
                        showAddDialog = true
                    },
                    onAddExample = {
                        addInitial = ONBOARDING_EXAMPLE_LINKS.first().first
                        showAddDialog = true
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
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
                            // 6f-4：列表增删/位移动画（Foundation 1.7 animateItem）
                            modifier = Modifier.animateItem().then(
                                if (url == highlightedUrl) Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp),
                                ) else Modifier
                            ),
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

            // Phase 2：悬浮确认卡片（分享/剪贴板入口），FAB 上方
            currentPendingUrl?.let { pendingUrl ->
                FloatingOpPanel(
                    url = pendingUrl,
                    alreadyMonitored = pendingUrl in urls,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp),
                    onAdd = {
                        viewModel.add(pendingUrl)
                        panelSnackbarMsg = context.getString(R.string.snackbar_added_from_panel, pendingUrl)
                        currentPendingUrl = ShareIntentRouter.dequeue()
                    },
                    onDismiss = {
                        currentPendingUrl = ShareIntentRouter.dequeue()
                    },
                )
            }
        }
    }

    // Phase 2：悬浮卡片添加成功后的 Snackbar 提示（放在 Scaffold 外避免重建）
    LaunchedEffect(panelSnackbarMsg) {
        panelSnackbarMsg?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            panelSnackbarMsg = null
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            initial = addInitial,
            onDismiss = { showAddDialog = false; addInitial = "" },
            onConfirm = { urls ->
                urls.forEach { viewModel.add(it) }
                showAddDialog = false
                addInitial = ""
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = if (urls.size == 1) context.getString(R.string.snackbar_added_one, urls[0])
                        else context.getString(R.string.snackbar_added_many, urls.size),
                        actionLabel = context.getString(R.string.action_undo),
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
        title = { Text(stringResource(R.string.log_dialog_title)) },
        text = {
            Column {
                if (cleared) Text(stringResource(R.string.log_cleared), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = content.ifBlank { stringResource(R.string.log_empty) },
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
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_logs_title)))
}


@Composable
private fun EmptyState(
    padding: PaddingValues,
    onAdd: () -> Unit,
    onAddExample: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(4.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_monitor_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_monitor_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        // 6f R23：空态快捷入口——直达添加 + 示例链接预填
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.height(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.empty_monitor_action))
        }
        TextButton(onClick = onAddExample) {
            Text(stringResource(R.string.empty_monitor_example))
        }
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
        title = {
            Text(
                if (willStopRecording) stringResource(R.string.remove_dialog_title_stop)
                else stringResource(R.string.remove_dialog_title)
            )
        },
        text = {
            Text(
                if (willStopRecording) {
                    stringResource(R.string.remove_dialog_text_recording)
                } else {
                    stringResource(R.string.remove_dialog_text)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}


@Composable
fun AddUrlDialog(onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit, initial: String = "") {
    var text by remember { mutableStateOf(initial) }
    // R14/U10：多行/空格/逗号分隔均可，批量添加
    val urls = text.lines()
        .flatMap { it.split(',', '，', ' ', '\t') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val allSupported = urls.isNotEmpty() && urls.all { PlatformRouter.isSupported(it) }
    // QW1：实时平台预览——解析出的每条链接显示平台徽标（不支持平台显示「不支持」）
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.add_dialog_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4
                )
                if (urls.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        urls.take(4).forEach { u ->
                            val key = platformKeyForUrl(u)
                            val supported = PlatformRouter.isSupported(u)
                            PlatformBadge(
                                platformKey = key,
                                text = if (supported) null else stringResource(R.string.add_dialog_platform_unknown),
                            )
                        }
                        if (urls.size > 4) {
                            Text(
                                stringResource(R.string.add_dialog_more, urls.size - 4),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (urls.isNotEmpty() && !allSupported) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.add_dialog_unsupported_warn),
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
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.edit_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.edit_dialog_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank() && text.trim() != initial
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
