package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.data.AppSettings
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.ProxySettings
import com.wochatchat.liverecorder.push.PushConfig
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushSettingsDialog(
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


@Composable
fun CookieDialog(
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
