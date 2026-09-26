package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.recorder.RecordController

/**
 * 统一状态徽标：录制链路状态（Recording/Reconnecting/Resolving）优先于监控状态。
 * 颜色走 MaterialTheme，深色模式自动适配（R3）。
 */
@Composable
fun StatusBadge(
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
    unhealthy: Boolean = false,
) {
    val (text, color) = when {
        disabled -> "已停用" to MaterialTheme.colorScheme.outline
        recordState is RecordController.RecordState.Recording -> "录制中" to MaterialTheme.colorScheme.error
        recordState is RecordController.RecordState.Reconnecting -> "断流重连" to MaterialTheme.colorScheme.tertiary
        recordState is RecordController.RecordState.Resolving -> "解析中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Live -> "直播中" to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Offline -> "未开播" to MaterialTheme.colorScheme.onSurfaceVariant
        monitorState is MonitorLoop.State.Error && unhealthy -> "失效" to MaterialTheme.colorScheme.outline
        monitorState is MonitorLoop.State.Error -> "错误" to MaterialTheme.colorScheme.error
        else -> "待检测" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}