package com.wochatchat.liverecorder.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.UpdateChecker.UpdateInfo
import com.wochatchat.liverecorder.update.UpdateDownloader
import kotlinx.coroutines.launch
import java.io.File

/**
 * V3-7：应用内更新弹窗。
 *
 * 三种状态：
 * 1. IDLE — 显示「下载安装」按钮（初始 / 下载失败后重试）
 * 2. DOWNLOADING — 显示进度条 + 下载百分比 + 取消按钮
 * 3. READY — 显示「安装」按钮（下载完成）+ 可重试 + 忽略
 *
 * @param info 更新信息
 * @param onIgnore 用户点「忽略此版本」
 * @param onDismiss 用户关闭弹窗（等价于忽略）
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember(info) { mutableStateOf(DownloadState.IDLE) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var downloadId by remember { mutableStateOf(-1L) }

    // 触发下载
    fun startDownload() {
        if (downloadState == DownloadState.DOWNLOADING) return
        downloadState = DownloadState.DOWNLOADING
        errorMsg = null
        scope.launch {
            val result = UpdateDownloader.downloadApk(context, info.apkUrl) { downloadId = it }
            // 已被用户取消则忽略结果
            if (downloadState != DownloadState.DOWNLOADING) return@launch
            if (result.apkFile != null) {
                downloadedFile = result.apkFile
                downloadState = DownloadState.READY
            } else {
                errorMsg = result.error
                downloadState = DownloadState.IDLE
            }
        }
    }

    // 安装 APK，未授权时引导用户去设置
    fun install() {
        val apk = downloadedFile ?: return
        val success = UpdateDownloader.installApk(context, apk)
        if (!success) {
            UpdateDownloader.openInstallPermissionSettings(context)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (downloadState == DownloadState.DOWNLOADING) return@AlertDialog
            onDismiss()
        },
        icon = {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                stringResource(R.string.update_dialog_title, info.latestVersion),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.update_dialog_current_version, info.currentVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (info.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.update_dialog_changelog_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        Text(
                            info.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 下载状态区域
                if (downloadState == DownloadState.DOWNLOADING) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.update_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 错误提示
                if (errorMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.update_download_failed, errorMsg!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            AnimatedContent(
                targetState = downloadState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "update_action",
            ) { state ->
                when (state) {
                    DownloadState.IDLE -> {
                        Button(onClick = { startDownload() }) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_action_download))
                        }
                    }
                    DownloadState.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = { downloadState = DownloadState.IDLE },
                            enabled = false,
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_action_downloading))
                        }
                    }
                    DownloadState.READY -> {
                        Button(onClick = { install() }) {
                            Icon(Icons.Default.InstallMobile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.update_action_install))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (downloadState == DownloadState.DOWNLOADING) {
                        UpdateDownloader.cancel(context, downloadId)
                        downloadState = DownloadState.IDLE
                    } else {
                        onIgnore()
                    }
                },
            ) {
                Text(
                    when (downloadState) {
                        DownloadState.DOWNLOADING -> stringResource(R.string.update_action_cancel_download)
                        else -> stringResource(R.string.update_action_ignore)
                    },
                )
            }
        },
    )
}

private enum class DownloadState {
    IDLE,
    DOWNLOADING,
    READY,
}
