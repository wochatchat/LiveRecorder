package com.wochatchat.liverecorder.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.StatsFormat
import java.io.File
import com.wochatchat.liverecorder.R

/**
 * 录制状态机统一呈现（R13 / U7）：一套组件处理全部 RecordState。
 * 颜色全部从组件内部走 MaterialTheme（stateColor() 随本轮删除）。
 */
@Composable
fun RecordStatusLine(
    state: RecordController.RecordState,
    modifier: Modifier = Modifier,
    /** V3-1：显示结构化诊断串（设置页开关，默认关）——用户截图即可定位录制断点。 */
    showDiag: Boolean = false,
) {
    when (state) {
        is RecordController.RecordState.Resolving -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.status_resolving),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        is RecordController.RecordState.Recording -> RecordingStatsLine(state, modifier)

        is RecordController.RecordState.Reconnecting -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    R.string.status_reconnecting,
                    state.attempt, state.nextDelaySec, state.message
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        is RecordController.RecordState.Finished -> FinishedLine(state, modifier, showDiag)

        is RecordController.RecordState.Failed -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.status_failed, state.message),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            DiagLine(state.diag, showDiag, Modifier.weight(1f))
        }
    }
}

/** 录制中：大字时长（走秒）+ 不定进度条 + 大小/码率/文件名 caption。 */
@Composable
private fun RecordingStatsLine(
    state: RecordController.RecordState.Recording,
    modifier: Modifier = Modifier,
) {
    // 两次状态发射之间也保持走秒：在最近快照的 durationMs 基础上累加本秒表
    var extraSec by remember { mutableStateOf(0L) }
    LaunchedEffect(state) {
        extraSec = 0
        while (true) {
            kotlinx.coroutines.delay(1000)
            extraSec++
        }
    }
    val shownMs = state.durationMs + extraSec * 1000
    Column(modifier = modifier) {
        Text(
            text = StatsFormat.duration(shownMs),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            strokeCap = StrokeCap.Round
        )
        Text(
            "↓ ${StatsFormat.bytes(state.bytes)} · ${StatsFormat.bitrate(state.bytes, shownMs)}" +
                " · ${state.savePath.substringAfterLast('/')}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 已完成/已停止：结果图标 + 摘要 + 文件名 + 播放入口。 */
@Composable
private fun FinishedLine(
    state: RecordController.RecordState.Finished,
    modifier: Modifier = Modifier,
    showDiag: Boolean = false,
) {
    val context = LocalContext.current
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (state.completed)
                    stringResource(R.string.status_done, StatsFormat.duration(state.durationMs), StatsFormat.bytes(state.bytes))
                else
                    stringResource(R.string.status_stopped, StatsFormat.duration(state.durationMs), StatsFormat.bytes(state.bytes)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.savePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.action_play),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    openRecording(context, state.savePath)
                }
            )
        }
        DiagLine(state.diag, showDiag, Modifier.fillMaxWidth())
    }
}

/** V3-1：诊断 DBG 行（小号 monospace，可截图回传定位录制断点）。 */
@Composable
private fun DiagLine(diag: String, show: Boolean, modifier: Modifier = Modifier) {
    if (!show || diag.isBlank()) return
    Text(
        text = "DBG $diag",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 调系统播放器播放录制文件（FileProvider 授权，无可播放器时 Toast 提示）。 */
private fun openRecording(context: android.content.Context, savePath: String) {
    runCatching {
        val file = File(savePath)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.toast_open_failed, it.message), Toast.LENGTH_SHORT).show()
    }
}
