package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.platform.PlatformRouter

/**
 * Phase 2：悬浮确认卡片——分享/剪贴板检测到直播 URL 后展示。
 * 固定在屏幕底部，FAB 上方。
 *
 * @param url 待确认的直播 URL
 * @param onAdd 确认添加 → 回调触发添加逻辑（Caller 负责清理 pendingUrls）
 * @param onDismiss 忽略 → 回调（Caller 负责清理 pendingUrls）
 * @param alreadyMonitored 当前 URL 是否已在监控列表中
 */
@Composable
fun FloatingOpPanel(
    url: String,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    alreadyMonitored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val platformKey = platformKeyForUrl(url)
    val isSupported = PlatformRouter.isSupported(url)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (alreadyMonitored) {
                        stringResource(R.string.floating_panel_already_added)
                    } else {
                        stringResource(R.string.floating_panel_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isSupported) {
                    PlatformBadge(platformKey = platformKey)
                }
            }

            Spacer(Modifier.height(8.dp))

            // URL 行（单行截断）
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            // 按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onAdd,
                    enabled = !alreadyMonitored && isSupported,
                ) {
                    Text(
                        if (alreadyMonitored) stringResource(R.string.floating_panel_already_added)
                        else stringResource(R.string.action_add_monitor)
                    )
                }
            }
        }
    }
}