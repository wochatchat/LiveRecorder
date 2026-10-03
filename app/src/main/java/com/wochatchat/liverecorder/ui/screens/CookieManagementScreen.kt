package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.AccountHealth
import com.wochatchat.liverecorder.data.Accounts
import com.wochatchat.liverecorder.ui.SettingsViewModel
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import com.wochatchat.liverecorder.R

/**
 * Cookie 管理页（6d R16 / U11）：全部平台列表，配置状态一目了然；
 * 单条编辑（复用 CookieDialog）/ 清除，多选批量清除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieManagementScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val cookies by viewModel.cookies.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    // Phase 11-11.2：多账号（含默认账号的完整列表）+ 管理对话框状态
    val accounts by viewModel.accounts.collectAsState()
    var managingAccounts by remember { mutableStateOf<String?>(null) }
    // Phase 5-5.2：账号健康状态（✅/❌/⏳）
    val accountHealth by viewModel.accountHealth.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    // V3-5 R1：网页登录取 cookie——页内全屏覆盖（不走导航，保住对话框 remember 状态）
    var webLoginPlatform by remember { mutableStateOf<String?>(null) }
    var prefillCookie by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    val selection = remember { mutableStateListOf<String>() }

    val platforms = (AuthStore.ALL_PLATFORMS + AuthStore.LOGIN_PLATFORMS).distinctBy { it.key }
    val loginKeys = AuthStore.LOGIN_PLATFORMS.map { it.key }.toSet()
    val configuredKeys = (cookies.filterKeys { it.isNotBlank() }.keys +
        credentials.filterValues { it.first.isNotBlank() || it.second.isNotBlank() }.keys)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selection.isNotEmpty()) stringResource(R.string.cookies_selected_count, selection.size)
                        else stringResource(R.string.cookies_page_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selection.isNotEmpty()) selection.clear() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                actions = {
                    if (selecting) {
                        if (selection.isNotEmpty()) {
                            TextButton(onClick = {
                                selection.forEach { clearPlatform(viewModel, it, loginKeys) }
                                selection.clear()
                            }) {
                                Text(stringResource(R.string.cookies_clear_selected), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(onClick = { selection.clear(); selecting = false }) { Text(stringResource(R.string.action_cancel)) }
                    } else {
                        TextButton(onClick = { selecting = true }) { Text(stringResource(R.string.action_multiselect)) }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                if (configuredKeys.isNotEmpty())
                    stringResource(R.string.cookies_summary_configured, configuredKeys.size, platforms.size)
                else stringResource(R.string.cookies_summary_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(platforms, key = { it.key }) { p ->
                    val hasCookie = cookies[p.key]?.isNotBlank() == true
                    val hasCred = credentials[p.key]?.first?.isNotBlank() == true
                    val configured = hasCookie || hasCred
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selecting) {
                                    if (p.key in selection) selection.remove(p.key) else selection.add(p.key)
                                } else editing = p.key
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selecting) {
                            Checkbox(
                                checked = p.key in selection,
                                onCheckedChange = { checked ->
                                    if (checked) selection.add(p.key) else selection.remove(p.key)
                                }
                            )
                        }
                        PlatformBadge(p.key, text = p.label)
                        // Phase 5-5.2：账号健康状态（仅已配置认证的平台显示）
                        if (configured) {
                            Text(
                                when (AccountHealth.statusOf(accountHealth, p.key)) {
                                    AccountHealth.STATUS_OK -> "✅"
                                    AccountHealth.STATUS_EXPIRED -> "❌"
                                    else -> "⏳"
                                },
                                modifier = Modifier.padding(start = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    hasCookie -> stringResource(R.string.cookies_state_cookie, mask(cookies[p.key].orEmpty()))
                                    hasCred -> stringResource(R.string.cookies_state_cred, credentials[p.key]?.first ?: "")
                                    else -> stringResource(R.string.cookies_state_none)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (configured) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (p.key in loginKeys) {
                                Text(
                                    stringResource(R.string.cookies_cred_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Phase 11-11.2：额外账号数量提示（默认账号已在主行显示）
                            val extraCount = accounts[p.key]?.count { it.id != Accounts.DEFAULT_ID } ?: 0
                            if (extraCount > 0) {
                                Text(
                                    stringResource(R.string.accounts_extra_count, extraCount),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // Phase 11-11.2：多账号管理入口
                        IconButton(onClick = { managingAccounts = p.key }, enabled = !selecting) {
                            Icon(
                                Icons.Default.Group,
                                contentDescription = stringResource(R.string.accounts_manage)
                            )
                        }
                        IconButton(onClick = { editing = p.key }, enabled = !selecting) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.desc_edit))
                        }
                        IconButton(
                            onClick = { clearPlatform(viewModel, p.key, loginKeys) },
                            enabled = configured && !selecting
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.desc_clear),
                                tint = if (configured) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    // V3-5 R1：登录覆盖层存在时不渲染对话框（AlertDialog 是独立窗口，会浮在 WebView 之上）
    if (editing != null && webLoginPlatform == null) editing?.let { key ->
        CookieDialog(
            cookies = cookies,
            credentials = credentials,
            initialPlatform = key,
            onDismiss = { editing = null; prefillCookie = "" },
            onSaveCookie = { platform, cookie ->
                viewModel.setCookie(platform, cookie)
                editing = null; prefillCookie = ""
            },
            onSaveCredential = { platform, user, pass ->
                viewModel.setCredential(platform, user, pass)
                editing = null
            },
            prefillCookie = prefillCookie,
            // V3-5 R1：支持网页登录的平台打开页内全屏 WebView
            onWebLogin = { platform -> webLoginPlatform = platform },
        )
    }

    // V3-5 R1：网页登录取 cookie 全屏覆盖（对话框状态保留在组合外不受影响）
    webLoginPlatform?.let { platform ->
        WebLoginScreen(
            platformKey = platform,
            onDone = { cookie ->
                prefillCookie = cookie
                webLoginPlatform = null
            },
            onCancel = { webLoginPlatform = null },
        )
    }

    // Phase 11-11.2：多账号管理（额外账号增删；默认账号在 CookieDialog）
    managingAccounts?.let { key ->
        AccountsDialog(
            platformKey = key,
            accounts = accounts[key].orEmpty().filter { it.id != Accounts.DEFAULT_ID },
            onAdd = { nick, cookie -> viewModel.addAccount(key, nick, cookie) },
            onDelete = { accId -> viewModel.removeAccount(key, accId) },
            onDismiss = { managingAccounts = null },
        )
    }
}

/** 敏感值掩码：前 3 字符 + ***（R16 要求）。 */
private fun mask(value: String): String =
    if (value.length <= 3) "***" else value.take(3) + "***"

/** 清除单平台认证：cookie 与账密一并置空。 */
private fun clearPlatform(viewModel: SettingsViewModel, key: String, loginKeys: Set<String>) {
    viewModel.setCookie(key, "")
    if (key in loginKeys) viewModel.setCredential(key, "", "")
}
