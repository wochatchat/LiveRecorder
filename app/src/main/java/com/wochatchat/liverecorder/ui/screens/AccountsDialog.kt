/*
 * AccountsDialog — Phase 11-11.2：平台多账号管理对话框。
 * 列出额外账号（默认账号在 CookieDialog 编辑，不在此列），支持添加（昵称 + Cookie）
 * 与删除。从 CookieManagementScreen 每行的「多账号」按钮进入。
 */
package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.Account
import com.wochatchat.liverecorder.data.AuthStore

@Composable
fun AccountsDialog(
    platformKey: String,
    accounts: List<Account>,
    onAdd: (nickname: String, cookie: String) -> Unit,
    onDelete: (accountId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.accounts_dialog_title, AuthStore.labelOf(platformKey))) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (accounts.isEmpty()) {
                    Text(
                        stringResource(R.string.accounts_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                accounts.forEach { acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(acc.nickname, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                acc.cookie.take(3) + "***",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDelete(acc.id) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text(stringResource(R.string.accounts_nickname_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    label = { Text(stringResource(R.string.accounts_cookie_label)) },
                    placeholder = { Text(stringResource(R.string.cookie_paste_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(nickname, cookie)
                    nickname = ""
                    cookie = ""
                },
                enabled = cookie.isNotBlank(),
            ) { Text(stringResource(R.string.accounts_action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
