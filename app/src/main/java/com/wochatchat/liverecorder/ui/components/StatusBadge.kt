package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.R

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
        disabled -> stringResource(R.string.badge_disabled) to MaterialTheme.colorScheme.outline
        recordState is RecordController.RecordState.Recording -> stringResource(R.string.badge_recording) to MaterialTheme.colorScheme.error
        recordState is RecordController.RecordState.Reconnecting -> stringResource(R.string.badge_reconnecting) to MaterialTheme.colorScheme.tertiary
        recordState is RecordController.RecordState.Resolving -> stringResource(R.string.badge_resolving) to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Live -> stringResource(R.string.badge_live) to MaterialTheme.colorScheme.primary
        monitorState is MonitorLoop.State.Offline -> stringResource(R.string.badge_offline) to MaterialTheme.colorScheme.onSurfaceVariant
        monitorState is MonitorLoop.State.Error && unhealthy -> stringResource(R.string.badge_unhealthy) to MaterialTheme.colorScheme.outline
        monitorState is MonitorLoop.State.Error -> stringResource(R.string.badge_error) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.badge_pending) to MaterialTheme.colorScheme.onSurfaceVariant
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