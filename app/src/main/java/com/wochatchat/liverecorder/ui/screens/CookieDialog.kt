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
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.data.AuthStore

/**
 * 平台 Cookie / 账密对话框（4b，R15 由设置页「平台认证」入口打开；R16 升级为管理页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
