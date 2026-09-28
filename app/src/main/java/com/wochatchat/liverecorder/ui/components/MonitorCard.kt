package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.monitor.MonitorRoundInfo
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.screens.ConfirmDeleteDialog

/** 根据 URL 域名推断平台键（PLATFORM_LABELS 映射键，未单独接入的回落 douyin）。 */
fun platformKeyForUrl(url: String): String = when {
    url.contains("douyu.com/") -> "douyu"
    url.contains("live.kuaishou.com/") -> "kuaishou"
    url.contains("huya.com/") -> "huya"
    url.contains("live.bilibili.com/") -> "bilibili"
    url.contains("www.yy.com/") -> "yy"
    url.contains("bigo.tv/") || url.contains("bigovideo.tv/") -> "bigo"
    url.contains("xiaohongshu.com/") || url.contains("xhslink.com/") -> "xiaohongshu"
    url.contains("tiktok.com/") -> "tiktok"
    url.contains("twitch.tv/") -> "twitch"
    url.contains("youtube.com/") || url.contains("youtu.be/") -> "youtube"
    else -> "douyin"
}

/**
 * 监控卡片（R10 / U6）：Surface 卡片包装，平台徽标 + 主播名大字标题 + URL 降级 caption。
 * 状态区：录制中 → 大字时长 + 进度条；其余状态 → 描述行（R13 由 RecordStatusLine 统一接管）。
 */
@Composable
fun MonitorCard(
    url: String,
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
    unhealthy: Boolean = false,
    roundInfo: MonitorRoundInfo = MonitorRoundInfo(),
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val recording = recordState is RecordController.RecordState.Resolving ||
        recordState is RecordController.RecordState.Recording ||
        recordState is RecordController.RecordState.Reconnecting
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        ConfirmDeleteDialog(
            url = url,
            willStopRecording = recording,
            onConfirm = {
                onRemove()
                showConfirmDelete = false
            },
            onDismiss = { showConfirmDelete = false }
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 1.dp,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlatformBadge(platformKeyForUrl(url))
                StatusBadge(recordState, monitorState, disabled, unhealthy)
                Spacer(Modifier.weight(1f))
                // 单条启停（2g）：停用后不参与轮询与自动录制（上游 # 注释行语义）
                Switch(checked = !disabled, onCheckedChange = onToggleEnabled)
            }

            Spacer(Modifier.height(4.dp))
            // 主标题：主播名为主（U6）；未解析到时回落平台名
            val live = monitorState as? MonitorLoop.State.Live
            Text(
                text = live?.anchorName?.takeIf { it.isNotBlank() }
                    ?: (PLATFORM_LABELS[platformKeyForUrl(url)]?.plus("直播") ?: url),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (disabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface
            )
            // 直播标题副行
            if (live != null && live.title.isNotBlank()) {
                Text(
                    "「${live.title}」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // URL 降级为 caption（U6）
            Text(
                url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (disabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))
            if (recordState != null) {
                RecordStatusLine(recordState)
            } else {
                RoundSummaryCaption(roundInfo)
            }

            Row {
                IconButton(onClick = if (recording) onStop else onStart, enabled = !disabled) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (recording) "停止" else "录制",
                        tint = if (recording)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onEdit, enabled = !disabled) {
                    Icon(
                        Icons.Default.Edit, contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showConfirmDelete = true }) {
                    Icon(
                        Icons.Default.Close, contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

/** 空闲卡片轮次摘要：上次检查 + 下一轮相对时间（30s 自刷新，6c-4）。 */
@Composable
private fun RoundSummaryCaption(info: MonitorRoundInfo) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tick++
        }
    }
    val now = remember(tick, info) { System.currentTimeMillis() }
    val parts = buildList {
        if (info.lastCheckMs > 0) {
            add("上次检查 " + relativeAgo(now - info.lastCheckMs))
        }
        if (info.nextCheckMs > now) {
            add("下轮 " + relativeIn(info.nextCheckMs - now))
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/** 毫秒差 → 「刚刚 / X 分钟前 / X 小时前」。 */
private fun relativeAgo(deltaMs: Long): String = when {
    deltaMs < 60_000L -> "刚刚"
    deltaMs < 3_600_000L -> "${deltaMs / 60_000L} 分钟前"
    else -> "${deltaMs / 3_600_000L} 小时前"
}

/** 毫秒差 → 「1 分钟内 / X 分钟后 / X 小时后」。 */
private fun relativeIn(deltaMs: Long): String = when {
    deltaMs < 60_000L -> "1 分钟内"
    deltaMs < 3_600_000L -> "${deltaMs / 60_000L} 分钟后"
    else -> "${deltaMs / 3_600_000L} 小时后"
}
