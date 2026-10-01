package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.PushConfig
import com.wochatchat.liverecorder.storage.StorageUsage
import com.wochatchat.liverecorder.ui.SettingsViewModel

/**
 * 全屏设置页（6d R15 / U3）：分组卡片（通用录制 / 推送 / 命名规则 / 代理 / 平台认证 / 维护）。
 * 开关与选项即改即存；文本输入失焦时校验保存，非法则回退。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onOpenCookies: () -> Unit = {},
) {
    val pushConfig by viewModel.pushConfig.collectAsState()
    val proxy by viewModel.proxySettings.collectAsState()
    val convertMp4 by viewModel.autoConvertMp4.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val cookies by viewModel.cookies.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    // R20：存储用量 + 告警阈值
    val storageUsage by viewModel.storageUsage.collectAsState()
    val diskLimitGb by viewModel.diskLimitGb.collectAsState()

    var showLogDialog by remember { mutableStateOf(false) }
    // QW8：设置页搜索（非空时各分组按行标题/说明过滤）
    var searchQuery by remember { mutableStateOf("") }
    // QW7：折叠分组状态（组标题 → 是否展开；搜索时全部展开）
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    fun isGroupExpanded(title: String) = searchQuery.isBlank() || title !in collapsedGroups
    fun toggleGroup(title: String) {
        collapsedGroups = if (title in collapsedGroups) collapsedGroups - title else collapsedGroups + title
    }

    // R17：推送测试 Snackbar 反馈
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.pushTestResult.collect { message ->
            if (message != null) {
                snackbarHostState.showSnackbar(message)
                viewModel.consumePushTestResult()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.screen_settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // QW8：搜索栏
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            RecordingGroup(settings, convertMp4, viewModel, searchQuery, ::isGroupExpanded, ::toggleGroup)
            Spacer(Modifier.height(12.dp))
            StorageGroup(storageUsage, diskLimitGb, viewModel, searchQuery, ::isGroupExpanded, ::toggleGroup)
            Spacer(Modifier.height(12.dp))
            PushGroup(
                pushConfig, settings, viewModel,
                onTestPush = { viewModel.sendTestPush() },
                query = searchQuery, isGroupExpanded = ::isGroupExpanded, toggleGroup = ::toggleGroup,
            )
            Spacer(Modifier.height(12.dp))
            NamingGroup(settings, viewModel, searchQuery, ::isGroupExpanded, ::toggleGroup)
            Spacer(Modifier.height(12.dp))
            ProxyGroup(proxy, viewModel, searchQuery, ::isGroupExpanded, ::toggleGroup)
            Spacer(Modifier.height(12.dp))
            AuthGroup(cookies.size + credentials.size, onOpenCookies, searchQuery, ::isGroupExpanded, ::toggleGroup)
            Spacer(Modifier.height(12.dp))
            MaintenanceGroup({ showLogDialog = true }, searchQuery, ::isGroupExpanded, ::toggleGroup)
        }
    }

    if (showLogDialog) {
        LogDialog(onDismiss = { showLogDialog = false })
    }
}

/** 分组卡片：组标题（QW7 可点击折叠/展开）+ Surface 卡片容器。 */
@Composable
private fun SettingsGroup(
    title: String,
    isExpanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onToggle != null) it.clickable { onToggle() } else it }
                .padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (onToggle != null) {
                Text(
                    if (isExpanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isExpanded) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 4.dp)) { content() }
            }
        }
    }
}

/** QW8：搜索命中判断（大小写不敏感；query 空恒匹配）。 */
private fun rowMatchesQuery(text: String?, query: String): Boolean =
    !text.isNullOrBlank() && (query.isBlank() || text.contains(query.trim(), ignoreCase = true))

/** 开关行：标题 + 说明 + Switch，即改即存（QW8：query 非空时按标题/说明过滤）。 */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    query: String = "",
) {
    if (!rowMatchesQuery(title, query) && (subtitle == null || !rowMatchesQuery(subtitle, query))) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 选项 chips 行（画质 / 推送类型）。QW8：query 非空时按 label 过滤。 */
@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    query: String = "",
) {
    if (!rowMatchesQuery(label, query)) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) }
                )
            }
        }
    }
}

// ---- 各分组 ----

/** 存储管理（R20）：用量进度条 + 已用/总容量 + 告警阈值（触底暂停监控录制）。 */
@Composable
private fun StorageGroup(
    usage: StorageUsage,
    diskLimitGb: Double,
    viewModel: SettingsViewModel,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_storage)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            usage.usedFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                stringResource(
                    R.string.settings_storage_line,
                    "%.1f".format(usage.usedGb), "%.1f".format(usage.totalGb), "%.1f".format(usage.freeGb),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SaveOnFocusLostField(
            initial = diskLimitGb.toString(),
            label = stringResource(R.string.settings_threshold_label),
            placeholder = "1.0",
            validate = { (it.toDoubleOrNull() ?: 0.0) > 0 },
            onSave = { it.toDoubleOrNull()?.let(viewModel::setDiskLimitGb) },
        )
    }
}

/** 通用录制：画质 / 循环时间 / 分段 / https / 转 MP4 / 只推送。 */
@Composable
private fun RecordingGroup(
    settings: AppSettings,
    convertMp4: Boolean,
    viewModel: SettingsViewModel,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_recording)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return

    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        ChipRow(
            label = stringResource(R.string.settings_quality_label),
            options = listOf("原画", "超清", "高清", "标清", "流畅"),
            selected = settings.quality,
            onSelect = { viewModel.setAppSettings(settings.copy(quality = it)) },
            query = query,
        )
        // QW4：画质参考码率提示
        if (rowMatchesQuery(stringResource(R.string.settings_quality_bitrate), query)) {
            Text(
                stringResource(R.string.settings_quality_bitrate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // 7c：保存格式（ts=分段默认 / mkv|mp4=直存单文件，对齐上游 save_type）
        val directSave = settings.saveFormat == "mkv" || settings.saveFormat == "mp4"
        ChipRow(
            label = stringResource(R.string.settings_saveformat_label),
            options = listOf("ts", "mkv", "mp4"),
            selected = settings.saveFormat,
            onSelect = { viewModel.setAppSettings(settings.copy(saveFormat = it)) }
        )
        LoopIntervalField(settings, viewModel)
        // mkv/mp4 直存单文件无分段概念，分段/转MP4设置仅 ts 有意义
        if (!directSave) {
            SwitchSettingRow(
                title = stringResource(R.string.settings_segment_title),
                subtitle = stringResource(R.string.settings_segment_subtitle),
                checked = settings.segmented,
                onChange = { viewModel.setAppSettings(settings.copy(segmented = it)) }
            )
            if (settings.segmented) {
                SegmentTimeField(settings, viewModel)
            }
        }
        SwitchSettingRow(
            title = stringResource(R.string.settings_https_title),
            subtitle = stringResource(R.string.settings_https_subtitle),
            checked = settings.forceHttps,
            onChange = { viewModel.setAppSettings(settings.copy(forceHttps = it)) }
        )
        // TS→MP4 后期转换仅对 ts 分片有意义（mkv/mp4 直存已是目标格式）
        if (!directSave) {
            SwitchSettingRow(
                title = stringResource(R.string.settings_mp4_title),
                subtitle = stringResource(R.string.settings_mp4_subtitle),
                checked = convertMp4,
                onChange = { viewModel.setAutoConvertMp4(it) }
            )
        }
        if (convertMp4) {
            SwitchSettingRow(
                title = stringResource(R.string.settings_del_ts_title),
                subtitle = stringResource(R.string.settings_del_ts_subtitle),
                checked = settings.deleteOriginalOnConvert,
                onChange = { viewModel.setAppSettings(settings.copy(deleteOriginalOnConvert = it)) }
            )
        }
        SwitchSettingRow(
            title = stringResource(R.string.settings_onlynotify_title),
            subtitle = stringResource(R.string.settings_onlynotify_subtitle),
            checked = settings.onlyNotify,
            onChange = { viewModel.setAppSettings(settings.copy(onlyNotify = it)) }
        )
    }
}

/** 推送：启用 / 类型 / 地址 / 开关播推送（R17 测试按钮后续并入）。 */
@Composable
private fun PushGroup(
    pushConfig: PushConfig,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onTestPush: () -> Unit,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_push)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        Text(
            stringResource(R.string.settings_push_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val apiCsv = pushConfig.apis.joinToString(",")
        // 7b R38：任何字段保存都携带全量当前值，避免三参调用把模板重置为空
        fun savePush(
            enabled: Boolean = pushConfig.enabled,
            type: String = pushConfig.type,
            api: String = apiCsv,
            title: String = pushConfig.title,
            liveMessage: String = pushConfig.liveMessage,
            offlineMessage: String = pushConfig.offlineMessage,
            barkLevel: String = pushConfig.barkLevel,
            barkSound: String = pushConfig.barkSound,
            ntfyTags: String = pushConfig.ntfyTags,
            ntfyPriority: Int = pushConfig.ntfyPriority,
        ) = viewModel.setPushConfig(enabled, type, api, title, liveMessage, offlineMessage, barkLevel, barkSound, ntfyTags, ntfyPriority)

        SwitchSettingRow(
            title = stringResource(R.string.settings_push_enable),
            checked = pushConfig.enabled,
            onChange = { savePush(enabled = it) }
        )
        if (pushConfig.enabled) {
            ChipRow(
                label = stringResource(R.string.settings_push_type),
                options = listOf("ntfy", "bark"),
                selected = pushConfig.type,
                onSelect = { savePush(type = it) }
            )
            SaveOnFocusLostField(
                initial = apiCsv,
                label = stringResource(R.string.settings_push_addr),
                placeholder = if (pushConfig.type == "bark") stringResource(R.string.settings_push_addr_hint_bark)
                else stringResource(R.string.settings_push_addr_hint_ntfy),
                validate = { it.isNotBlank() },
                onSave = { savePush(api = it.trim()) }
            )
            SwitchSettingRow(
                title = stringResource(R.string.settings_push_live),
                checked = settings.pushOnLive,
                onChange = { viewModel.setAppSettings(settings.copy(pushOnLive = it)) }
            )
            SwitchSettingRow(
                title = stringResource(R.string.settings_push_offline),
                checked = settings.pushOnOffline,
                onChange = { viewModel.setAppSettings(settings.copy(pushOnOffline = it)) }
            )
            // 7b R38：推送明细（标题/文案模板 + bark 级别铃声）
            SaveOnFocusLostField(
                initial = pushConfig.title,
                label = stringResource(R.string.settings_push_title),
                placeholder = stringResource(R.string.settings_push_title_hint),
                validate = { true },
                onSave = { savePush(title = it.trim()) }
            )
            SaveOnFocusLostField(
                initial = pushConfig.liveMessage,
                label = stringResource(R.string.settings_push_live_msg),
                placeholder = stringResource(R.string.settings_push_msg_hint),
                validate = { true },
                onSave = { savePush(liveMessage = it) }
            )
            SaveOnFocusLostField(
                initial = pushConfig.offlineMessage,
                label = stringResource(R.string.settings_push_offline_msg),
                placeholder = stringResource(R.string.settings_push_msg_hint),
                validate = { true },
                onSave = { savePush(offlineMessage = it) }
            )
            if (pushConfig.type == "bark") {
                ChipRow(
                    label = stringResource(R.string.settings_push_bark_level),
                    options = listOf("active", "timeSensitive", "critical", "passive"),
                    selected = pushConfig.barkLevel.ifBlank { "active" },
                    onSelect = { savePush(barkLevel = it) }
                )
                SaveOnFocusLostField(
                    initial = pushConfig.barkSound,
                    label = stringResource(R.string.settings_push_bark_sound),
                    placeholder = stringResource(R.string.settings_push_bark_sound_hint),
                    validate = { true },
                    onSave = { savePush(barkSound = it.trim()) }
                )
            }
            // 7b 收尾：ntfy tags/priority 定制（空/0 → 默认 partying_face / 3）
            if (pushConfig.type != "bark") {
                SaveOnFocusLostField(
                    initial = pushConfig.ntfyTags,
                    label = stringResource(R.string.settings_push_ntfy_tags),
                    placeholder = stringResource(R.string.settings_push_ntfy_tags_hint),
                    validate = { true },
                    onSave = { savePush(ntfyTags = it.trim()) }
                )
                ChipRow(
                    label = stringResource(R.string.settings_push_ntfy_priority),
                    options = listOf("min", "low", "default", "high", "max"),
                    selected = when (pushConfig.ntfyPriority) {
                        1 -> "min"
                        2 -> "low"
                        4 -> "high"
                        5 -> "max"
                        else -> "default"
                    },
                    onSelect = { savePush(ntfyPriority = when (it) {
                        "min" -> 1
                        "low" -> 2
                        "high" -> 4
                        "max" -> 5
                        else -> 3
                    }) }
                )
            }
            OutlinedButton(
                onClick = onTestPush,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.settings_push_test))
            }
        }
    }
}

/** 命名规则（5b 五项）。 */
@Composable
private fun NamingGroup(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_naming)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        SwitchSettingRow(
            title = stringResource(R.string.settings_folder_author),
            subtitle = stringResource(R.string.settings_folder_author_sub),
            checked = settings.folderByAuthor,
            onChange = { viewModel.setAppSettings(settings.copy(folderByAuthor = it)) }
        )
        SwitchSettingRow(
            title = stringResource(R.string.settings_folder_time),
            subtitle = stringResource(R.string.settings_folder_time_sub),
            checked = settings.folderByTime,
            onChange = { viewModel.setAppSettings(settings.copy(folderByTime = it)) }
        )
        SwitchSettingRow(
            title = stringResource(R.string.settings_folder_title_name),
            subtitle = stringResource(R.string.settings_folder_title_sub),
            checked = settings.folderByTitle,
            onChange = { viewModel.setAppSettings(settings.copy(folderByTitle = it)) }
        )
        SwitchSettingRow(
            title = stringResource(R.string.settings_filename_title),
            subtitle = stringResource(R.string.settings_filename_sub),
            checked = settings.filenameByTitle,
            onChange = { viewModel.setAppSettings(settings.copy(filenameByTitle = it)) }
        )
        SwitchSettingRow(
            title = stringResource(R.string.settings_clean_emoji),
            subtitle = stringResource(R.string.settings_clean_emoji_sub),
            checked = settings.cleanEmoji,
            onChange = { viewModel.setAppSettings(settings.copy(cleanEmoji = it)) }
        )
    }
}

/** 代理（4a per-platform）。 */
@Composable
private fun ProxyGroup(
    proxy: ProxySettings,
    viewModel: SettingsViewModel,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_proxy)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        SwitchSettingRow(
            title = stringResource(R.string.settings_proxy_title),
            subtitle = stringResource(R.string.settings_proxy_subtitle),
            checked = proxy.enabled,
            onChange = { viewModel.setProxySettings(proxy.copy(enabled = it)) }
        )
        if (proxy.enabled) {
            SaveOnFocusLostField(
                initial = proxy.addr,
                label = stringResource(R.string.settings_proxy_addr),
                placeholder = stringResource(R.string.settings_proxy_addr_hint),
                validate = { it.isNotBlank() },
                onSave = { viewModel.setProxySettings(proxy.copy(addr = it.trim())) }
            )
            SaveOnFocusLostField(
                initial = proxy.platformsCsv(),
                label = stringResource(R.string.settings_proxy_platforms),
                placeholder = "tiktok, twitch, ...",
                validate = { true },
                onSave = {
                    viewModel.setProxySettings(
                        proxy.copy(
                            platforms = ProxySettings.parsePlatforms(
                                it, fallback = proxy.platforms
                            )
                        )
                    )
                }
            )
        }
    }
}

/** 平台认证：Cookie / 账密入口（R16 升级为独立管理页）。 */
@Composable
private fun AuthGroup(
    configuredCount: Int,
    onOpenPage: () -> Unit,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_auth)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPage() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cookie_dialog_title), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (configuredCount > 0) stringResource(R.string.settings_cookies_configured, configuredCount)
                    else stringResource(R.string.settings_cookies_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 维护：运行日志。 */
@Composable
private fun MaintenanceGroup(
    onOpenLogs: () -> Unit,
    query: String = "",
    isGroupExpanded: (String) -> Boolean = { true },
    toggleGroup: (String) -> Unit = {},
) {
    val title = stringResource(R.string.settings_group_maintenance)
    if (!rowMatchesQuery(title, query) && query.isNotBlank()) return
    SettingsGroup(title, isGroupExpanded(title), { toggleGroup(title) }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLogs() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.log_dialog_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---- 文本输入（失焦保存） ----

/** 循环时间（秒）：支持清空重输；失焦时合法才保存，否则回退当前值。 */
@Composable
private fun LoopIntervalField(settings: AppSettings, viewModel: SettingsViewModel) {
    var text by remember(settings.loopIntervalSec) {
        mutableStateOf(if (settings.loopIntervalSec == 0L) "" else settings.loopIntervalSec.toString())
    }
    val parsed = text.toLongOrNull()
    val error = when {
        text.isBlank() -> null
        parsed == null -> stringResource(R.string.validation_number)
        parsed < 60 || parsed > 86400 -> stringResource(R.string.validation_range_loop)
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.settings_loop_interval_label)) },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, color = MaterialTheme.colorScheme.error) }
        } else null,
        modifier = saveOnFocusModifier(
            enabled = error == null && parsed != null && parsed != settings.loopIntervalSec
        ) {
            viewModel.setAppSettings(settings.copy(loopIntervalSec = parsed!!))
        }
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true
    )
}

/** 视频分段时间（秒）。 */
@Composable
private fun SegmentTimeField(settings: AppSettings, viewModel: SettingsViewModel) {
    var text by remember(settings.segmentTimeSec) { mutableStateOf(settings.segmentTimeSec.toString()) }
    val parsed = text.toIntOrNull()
    val error = when {
        text.isBlank() -> stringResource(R.string.validation_empty)
        parsed == null -> stringResource(R.string.validation_number)
        parsed < 10 || parsed > 86400 -> stringResource(R.string.validation_range_segment)
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.settings_segment_time_label)) },
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, color = MaterialTheme.colorScheme.error) }
        } else null,
        modifier = saveOnFocusModifier(
            enabled = error == null && parsed != null && parsed != settings.segmentTimeSec
        ) {
            viewModel.setAppSettings(settings.copy(segmentTimeSec = parsed!!))
        }
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true
    )
}

/** 通用文本字段：失焦时若内容有效且变更则保存。 */
@Composable
private fun SaveOnFocusLostField(
    initial: String,
    label: String,
    placeholder: String,
    validate: (String) -> Boolean,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier
            .onFocusChanged {
                val wasFocused = focused
                focused = it.isFocused
                if (wasFocused && !it.isFocused && text.trim() != initial && validate(text)) {
                    onSave(text)
                }
            }
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true
    )
}

/** 失焦保存 modifier：聚焦离开且 [enabled] 时执行 [onSave]。 */
private fun saveOnFocusModifier(enabled: Boolean, onSave: () -> Unit): Modifier =
    Modifier.onFocusChanged {
        if (!it.isFocused && enabled) onSave()
    }


