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
            RecordingGroup(settings, convertMp4, viewModel)
            Spacer(Modifier.height(12.dp))
            StorageGroup(storageUsage, diskLimitGb, viewModel)
            Spacer(Modifier.height(12.dp))
            PushGroup(
                pushConfig, settings, viewModel,
                onTestPush = { viewModel.sendTestPush() }
            )
            Spacer(Modifier.height(12.dp))
            NamingGroup(settings, viewModel)
            Spacer(Modifier.height(12.dp))
            ProxyGroup(proxy, viewModel)
            Spacer(Modifier.height(12.dp))
            AuthGroup(cookies.size + credentials.size, onOpenCookies)
            Spacer(Modifier.height(12.dp))
            MaintenanceGroup { showLogDialog = true }
        }
    }

    if (showLogDialog) {
        LogDialog(onDismiss = { showLogDialog = false })
    }
}

/** 分组卡片：组标题 + Surface 卡片容器。 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
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

/** 开关行：标题 + 说明 + Switch，即改即存。 */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
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

/** 选项 chips 行（画质 / 推送类型）。 */
@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
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
) {
    SettingsGroup(stringResource(R.string.settings_group_storage)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            usage.usedFraction?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "已用 ${"%.1f".format(usage.usedGb)} GB · 共 ${"%.1f".format(usage.totalGb)} GB" +
                    " · 剩余 ${"%.1f".format(usage.freeGb)} GB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SaveOnFocusLostField(
            initial = diskLimitGb.toString(),
            label = "存储剩余告警阈值 (GB)",
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
) {
    SettingsGroup(stringResource(R.string.settings_group_recording)) {
        ChipRow(
            label = "录制画质",
            options = listOf("原画", "超清", "高清", "标清", "流畅"),
            selected = settings.quality,
            onSelect = { viewModel.setAppSettings(settings.copy(quality = it)) }
        )
        LoopIntervalField(settings, viewModel)
        SwitchSettingRow(
            title = "分段录制",
            subtitle = "关闭时 FLV 直下为单文件（不支持 m3u8）",
            checked = settings.segmented,
            onChange = { viewModel.setAppSettings(settings.copy(segmented = it)) }
        )
        if (settings.segmented) {
            SegmentTimeField(settings, viewModel)
        }
        SwitchSettingRow(
            title = "强制启用 https 录制",
            subtitle = "直播源 http:// 强制改写为 https://",
            checked = settings.forceHttps,
            onChange = { viewModel.setAppSettings(settings.copy(forceHttps = it)) }
        )
        SwitchSettingRow(
            title = "录制完成后自动转 MP4",
            subtitle = "TS 分片转 mp4（无需重编码）",
            checked = convertMp4,
            onChange = { viewModel.setAutoConvertMp4(it) }
        )
        if (convertMp4) {
            SwitchSettingRow(
                title = "转码后删除原 TS 分片",
                subtitle = "对齐上游「追加格式后删除原文件」",
                checked = settings.deleteOriginalOnConvert,
                onChange = { viewModel.setAppSettings(settings.copy(deleteOriginalOnConvert = it)) }
            )
        }
        SwitchSettingRow(
            title = "只推送通知不录制",
            subtitle = "开播时仅推送，不自动录制",
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
) {
    SettingsGroup(stringResource(R.string.settings_group_push)) {
        Text(
            "开播/关播时推送到 ntfy 或 bark。地址支持多个，用逗号分隔。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val apiCsv = pushConfig.apis.joinToString(",")
        SwitchSettingRow(
            title = "启用推送",
            checked = pushConfig.enabled,
            onChange = { viewModel.setPushConfig(it, pushConfig.type, apiCsv) }
        )
        if (pushConfig.enabled) {
            ChipRow(
                label = "推送类型",
                options = listOf("ntfy", "bark"),
                selected = pushConfig.type,
                onSelect = { viewModel.setPushConfig(pushConfig.enabled, it, apiCsv) }
            )
            SaveOnFocusLostField(
                initial = apiCsv,
                label = "推送地址",
                placeholder = if (pushConfig.type == "bark") "https://api.day.app/你的Key"
                else "https://ntfy.sh/你的主题",
                validate = { it.isNotBlank() },
                onSave = { viewModel.setPushConfig(pushConfig.enabled, pushConfig.type, it.trim()) }
            )
            SwitchSettingRow(
                title = "开播推送",
                checked = settings.pushOnLive,
                onChange = { viewModel.setAppSettings(settings.copy(pushOnLive = it)) }
            )
            SwitchSettingRow(
                title = "关播推送",
                checked = settings.pushOnOffline,
                onChange = { viewModel.setAppSettings(settings.copy(pushOnOffline = it)) }
            )
            OutlinedButton(
                onClick = onTestPush,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("发送测试通知")
            }
        }
    }
}

/** 命名规则（5b 五项）。 */
@Composable
private fun NamingGroup(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.settings_group_naming)) {
        SwitchSettingRow(
            title = "保存文件夹以作者区分",
            subtitle = "下载/{平台}/{主播}/…（默认开）",
            checked = settings.folderByAuthor,
            onChange = { viewModel.setAppSettings(settings.copy(folderByAuthor = it)) }
        )
        SwitchSettingRow(
            title = "保存文件夹以时间区分",
            subtitle = "追加一层当天日期（2026-09-24）",
            checked = settings.folderByTime,
            onChange = { viewModel.setAppSettings(settings.copy(folderByTime = it)) }
        )
        SwitchSettingRow(
            title = "保存文件夹以标题区分",
            subtitle = "再按直播标题/日期+标题建目录",
            checked = settings.folderByTitle,
            onChange = { viewModel.setAppSettings(settings.copy(folderByTitle = it)) }
        )
        SwitchSettingRow(
            title = "文件名包含标题",
            subtitle = "{主播}_{标题}_{时间}.flv",
            checked = settings.filenameByTitle,
            onChange = { viewModel.setAppSettings(settings.copy(filenameByTitle = it)) }
        )
        SwitchSettingRow(
            title = "去除名称中的表情符号",
            subtitle = "主播名与标题同步生效（默认开）",
            checked = settings.cleanEmoji,
            onChange = { viewModel.setAppSettings(settings.copy(cleanEmoji = it)) }
        )
    }
}

/** 代理（4a per-platform）。 */
@Composable
private fun ProxyGroup(proxy: ProxySettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.settings_group_proxy)) {
        SwitchSettingRow(
            title = "使用代理录制",
            subtitle = "仅下方平台列表命中的链接走代理（海外平台用）",
            checked = proxy.enabled,
            onChange = { viewModel.setProxySettings(proxy.copy(enabled = it)) }
        )
        if (proxy.enabled) {
            SaveOnFocusLostField(
                initial = proxy.addr,
                label = "代理地址",
                placeholder = "socks5://127.0.0.1:7890 或 http://127.0.0.1:7890",
                validate = { it.isNotBlank() },
                onSave = { viewModel.setProxySettings(proxy.copy(addr = it.trim())) }
            )
            SaveOnFocusLostField(
                initial = proxy.platformsCsv(),
                label = "走代理的平台（逗号分隔关键词）",
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
) {
    SettingsGroup(stringResource(R.string.settings_group_auth)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPage() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("平台 Cookie / 账号密码", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (configuredCount > 0) "已配置 $configuredCount 个平台" else "尚未配置任何平台",
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
private fun MaintenanceGroup(onOpenLogs: () -> Unit) {
    SettingsGroup(stringResource(R.string.settings_group_maintenance)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenLogs() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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
        parsed == null -> "请输入数字"
        parsed < 60 || parsed > 86400 -> "范围 60-86400 秒"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("循环时间(秒) — 每轮检查开播的间隔") },
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
        text.isBlank() -> "不能为空"
        parsed == null -> "请输入数字"
        parsed < 10 || parsed > 86400 -> "范围 10-86400 秒"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("视频分段时间(秒)") },
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


