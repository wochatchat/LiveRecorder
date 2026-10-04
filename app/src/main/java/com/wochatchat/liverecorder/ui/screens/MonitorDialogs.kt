/*
 * V3-8：监控页对话框——从 MonitorScreen.kt 拆出（日志对话框/导出/添加与编辑监控 URL）。
 */
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
