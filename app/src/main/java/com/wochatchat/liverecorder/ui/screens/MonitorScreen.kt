package com.wochatchat.liverecorder.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.wochatchat.liverecorder.data.Account
import com.wochatchat.liverecorder.data.Accounts
import com.wochatchat.liverecorder.data.MonitorRow
import com.wochatchat.liverecorder.data.groupMonitorUrls
import com.wochatchat.liverecorder.platform.PlatformRouter
import com.wochatchat.liverecorder.ui.MonitorViewModel
import com.wochatchat.liverecorder.ui.components.MonitorCard
import com.wochatchat.liverecorder.ui.components.FloatingOpPanel
import com.wochatchat.liverecorder.ui.components.PLATFORM_LABELS
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
    // Phase 4-4.1：单条参数覆盖 sheet 状态
    var perUrlSettingsUrl by remember { mutableStateOf<String?>(null) }
    val perUrlOverrides by viewModel.perUrlOverrides.collectAsState()
    // Phase 11-11.2：多账号列表 + 待绑定账号 id（AddUrlDialog 确认时落到该条 URL）
    val accounts by viewModel.accounts.collectAsState()
    // V3-1：诊断 DBG 行开关（设置页控制，默认关）
    val appSettingsState by viewModel.appSettings.collectAsState()
    val showDiag = appSettingsState.diagEnabled
    val accountBindings by viewModel.accountBindings.collectAsState()
    // V3-3 R2：分组 / 置顶 / 首载骨架标记
    val groupByPlatform by viewModel.groupByPlatform.collectAsState()
    val pinnedUrls by viewModel.pinnedUrls.collectAsState()
    val urlsLoaded by viewModel.urlsLoaded.collectAsState()
    var pendingAccountId by remember { mutableStateOf(Accounts.DEFAULT_ID) }
    val appSettings by viewModel.appSettings.collectAsState()
    val perUrlSheetState = androidx.compose.material3.rememberModalBottomSheetState()
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

    // V3-3 R2：分组纯逻辑 → 展示行模型（置顶优先 + 平台分组，空组自动不出现）
    val pinnedLabel = stringResource(R.string.monitor_group_pinned)
    val displayItems = remember(urls, pinnedUrls, groupByPlatform, pinnedLabel) {
        groupMonitorUrls(
            urls = urls,
            pinned = pinnedUrls,
            groupByPlatform = groupByPlatform,
            platformKeyOf = { platformKeyForUrl(it) },
            platformLabelOf = { PLATFORM_LABELS[it] ?: it },
            pinnedLabel = pinnedLabel,
        ).flatMap { g ->
            buildList {
                g.label?.let { add(MonitorRow.Header(it)) }
                g.urls.forEach { add(MonitorRow.UrlRow(it)) }
            }
        }
    }

    // 6f R22：消费 FocusRouter（通知点击直达）——列表就绪后滚动定位并高亮 4s
    val focusUrl by FocusRouter.focusUrl.collectAsState()
    LaunchedEffect(focusUrl, displayItems) {
        val u = focusUrl ?: return@LaunchedEffect
        val idx = displayItems.indexOfFirst { it is MonitorRow.UrlRow && it.url == u }
        if (idx < 0) return@LaunchedEffect // 列表未就绪，effect 将随变化重跑
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
                    // V3-3 R2：按平台分组切换（高亮 = 已开启）
                    IconButton(onClick = { viewModel.toggleGroupByPlatform() }) {
                        Icon(
                            Icons.Default.Category,
                            contentDescription = stringResource(R.string.monitor_toggle_group),
                            tint = if (groupByPlatform) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            when {
                // V3-3 R2：首载骨架（DataStore 未发射前防「空态」闪现）
                !urlsLoaded -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(4) { SkeletonCard() }
                    }
                }
                urls.isEmpty() -> {
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
                }
                else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // V3-3 R2：分组行模型渲染（头行 + 卡片行，key 唯一保 animateItem 生效）
                    items(displayItems, key = { row ->
                        when (row) {
                            is MonitorRow.Header -> "h:${row.label}"
                            is MonitorRow.UrlRow -> row.url
                        }
                    }) { row ->
                        when (row) {
                            is MonitorRow.Header -> Text(
                                row.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 6.dp)
                                    .animateItem(
                                        fadeInSpec = tween(250),
                                        placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        fadeOutSpec = tween(200),
                                    )
                            )
                            is MonitorRow.UrlRow -> {
                        val url = row.url
                        val state = recordStates[url]
                        MonitorCard(
                            url = url,
                            recordState = state,
                            monitorState = monitorStates[url],
                            disabled = url in disabledUrls,
                            unhealthy = url in unhealthyUrls,
                            roundInfo = roundInfo,
                            // 6f-4：列表增删/位移动画（Foundation 1.7 animateItem）
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(250),
                                placementSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                fadeOutSpec = tween(200),
                            ).then(
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
                            onSettings = { perUrlSettingsUrl = url },
                            onToggleEnabled = { viewModel.setEnabled(url, it) },
                            onStart = { viewModel.startRecord(url) },
                            onStop = { viewModel.stopRecord(url) },
                            hasOverride = url in perUrlOverrides,
                            // Phase 11-11.2：绑定账号昵称徽标（默认账号不显示）
                            boundAccount = accounts[
                                Accounts.cookieKeyForPlatform(platformKeyForUrl(url))
                            ]?.firstOrNull { it.id == (accountBindings[url] ?: "") }
                                ?.takeIf { it.id != Accounts.DEFAULT_ID }?.nickname,
                            // V3-1：诊断 DBG 行（设置页开关，默认关）
                            showDiag = showDiag,
                            // V3-3 R1：紧凑模式（设置页「外观」开关，默认开）
                            compact = appSettingsState.compactMonitorCard,
                            // V3-3 R2：置顶（长按切换）
                            pinned = url in pinnedUrls,
                            onTogglePin = { viewModel.togglePinned(url) },
                        )
                            }
                        }
                    }
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
            accounts = accounts,
            onAccountSelected = { pendingAccountId = it },
            onDismiss = { showAddDialog = false; addInitial = ""; pendingAccountId = Accounts.DEFAULT_ID },
            onConfirm = { urls ->
                urls.forEach { viewModel.add(it) }
                // Phase 11-11.2：单条添加且选了非默认账号 → 绑定到该条目
                if (urls.size == 1 && pendingAccountId != Accounts.DEFAULT_ID) {
                    viewModel.setAccountBinding(urls[0], pendingAccountId)
                }
                pendingAccountId = Accounts.DEFAULT_ID
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

    // Phase 4-4.1：单条参数覆盖 sheet
    perUrlSettingsUrl?.let { url ->
        // Phase 11-11.2：该平台账号列表（含默认账号；无账号不渲染切换组）
        val platformAccounts = accounts[
            Accounts.cookieKeyForPlatform(platformKeyForUrl(url))
        ].orEmpty()
        PerUrlSettingsSheet(
            url = url,
            current = perUrlOverrides[url],
            globalSettings = appSettings,
            sheetState = perUrlSheetState,
            accounts = platformAccounts,
            boundAccountId = accountBindings[url] ?: Accounts.DEFAULT_ID,
            onSelectAccount = { accId -> viewModel.setAccountBinding(url, accId) },
            onSave = { settings ->
                viewModel.setPerUrlSettings(url, settings)
                perUrlSettingsUrl = null
            },
            onDismiss = { perUrlSettingsUrl = null },
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
fun AddUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    initial: String = "",
    // Phase 11-11.2：单条添加时可绑定录制账号（多 URL 添加不展示，保持原语义）
    accounts: Map<String, List<Account>> = emptyMap(),
    onAccountSelected: ((String) -> Unit)? = null,
) {
    var text by remember { mutableStateOf(initial) }
    // 11.2：单条受支持 URL 时展示该平台账号选择（默认 + 额外账号），随确认一并绑定；
    // 换链接时重置回默认账号
    var selectedAccountId by remember(text) { mutableStateOf(Accounts.DEFAULT_ID) }
    val trimmedUrl = text.trim()
    val singlePlatform = if (
        trimmedUrl.isNotEmpty() && !trimmedUrl.contains(Regex("\\s")) &&
        PlatformRouter.isSupported(trimmedUrl)
    ) platformKeyForUrl(trimmedUrl) else null
    val platformAccounts = singlePlatform?.let {
        accounts[Accounts.cookieKeyForPlatform(it)].orEmpty()
    }.orEmpty()
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
                // Phase 11-11.2：多账号平台展示「录制账号」选择行（默认 + 额外账号）
                if (onAccountSelected != null && platformAccounts.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.accounts_pick_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        platformAccounts.take(4).forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = acc.id },
                                label = { Text(acc.nickname, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (urls.isNotEmpty()) {
                        // 11.2：仅单条添加时绑定账号；多 URL 批量添加不绑定（保持默认）
                        if (urls.size == 1 && platformAccounts.any { it.id == selectedAccountId }) {
                            onAccountSelected?.invoke(selectedAccountId)
                        }
                        onConfirm(urls)
                    }
                },
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

/** V3-3 R2：监控列表骨架占位卡（首载期间呼吸闪烁，防「空态」闪现）。 */
@Composable
private fun SkeletonCard() {
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            tween(700, easing = LinearEasing),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)
    Surface(
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .background(bg, RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .width(120.dp)
                        .height(18.dp)
                        .background(bg, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .size(30.dp)
                        .background(bg, RoundedCornerShape(15.dp))
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(bg, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .background(bg, RoundedCornerShape(4.dp))
            )
        }
    }
}
