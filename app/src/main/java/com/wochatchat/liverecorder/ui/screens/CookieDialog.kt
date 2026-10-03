package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.data.webLoginUrlFor
import com.wochatchat.liverecorder.R

/**
 * 平台 Cookie / 账密对话框（4b，R15 由设置页「平台认证」入口打开；R16 升级为管理页）。
 * V3-5 R1：支持网页登录的平台显示「网页登录获取」按钮，登录抓取结果经 [prefillCookie] 预填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieDialog(
    cookies: Map<String, String>,
    credentials: Map<String, Pair<String, String>>,
    initialPlatform: String = AuthStore.ALL_PLATFORMS.first().key,
    onDismiss: () -> Unit,
    onSaveCookie: (platform: String, cookie: String) -> Unit,
    onSaveCredential: (platform: String, username: String, password: String) -> Unit,
    /** V3-5 R1：预填 cookie（网页登录返回时非空），一次性填入输入框。 */
    prefillCookie: String = "",
    /** V3-5 R1：发起网页登录（null = 入口不可用，不显示按钮）。参数为当前平台键。 */
    onWebLogin: ((platform: String) -> Unit)? = null,
) {
    // R16：下拉含账密登录平台（原仅 COOKIE_PLATFORMS，登录平台不可达）
    val platforms = (AuthStore.ALL_PLATFORMS + AuthStore.LOGIN_PLATFORMS).distinctBy { it.key }
    var selectedKey by remember { mutableStateOf(initialPlatform) }
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

    // V3-5 R1：网页登录返回的预填 cookie（一次性填入，随后清空触发源由调用方管理）
    LaunchedEffect(prefillCookie) {
        if (prefillCookie.isNotBlank()) cookie = prefillCookie
    }

    val isLoginPlatform = selectedKey in AuthStore.LOGIN_PLATFORMS.map { it.key }
    // V3-5 R1：当前平台是否支持网页登录取 cookie
    val webLoginAvailable = onWebLogin != null && webLoginUrlFor(selectedKey) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cookie_dialog_title)) },
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
                                text = { Text(stringResource(R.string.cookie_platform_item, p.label, p.key)) },
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
                        stringResource(R.string.cookie_cred_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.cookie_account_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.cookie_password_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        stringResource(R.string.cookie_paste_hint),
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
                    // V3-5 R1：网页登录取 cookie（仅支持的平台显示）
                    if (webLoginAvailable) {
                        OutlinedButton(
                            onClick = { onWebLogin?.invoke(selectedKey) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.web_login_button))
                        }
                    }
                }
                Text(
                    stringResource(R.string.cookie_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isLoginPlatform) onSaveCredential(selectedKey, username, password)
                else onSaveCookie(selectedKey, cookie)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
