/*
 * PlatformHealthScreen — Phase 5-5.1：平台健康仪表盘。
 *
 * 数据来源：MonitorLoop 每轮每条 URL 的开播检查结果（MonitorStore.checkResultHistory，
 * 环形缓冲 100 条/平台）。展示近 7 天各平台检查成功率，三色语义：
 *   绿 ≥90%（健康）/ 黄 ≥60%（波动）/ 红 <60%（异常）；无数据的平台不显示。
 * 按成功率升序排列（问题平台排前面）。
 */
package com.wochatchat.liverecorder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.CheckResultHistory
import com.wochatchat.liverecorder.data.CheckResultEntry
import com.wochatchat.liverecorder.data.PlatformHealthStats
import com.wochatchat.liverecorder.ui.SettingsViewModel
import com.wochatchat.liverecorder.ui.components.PlatformBadge
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 平台健康三色阈值（绿/黄/红）。 */
private const val HEALTH_GREEN_THRESHOLD = 0.9
private const val HEALTH_YELLOW_THRESHOLD = 0.6

@Composable
fun healthColor(stats: PlatformHealthStats): Color = when {
    stats.total == 0 -> MaterialTheme.colorScheme.outline
    stats.successRate >= HEALTH_GREEN_THRESHOLD -> Color(0xFF4CAF50)
    stats.successRate >= HEALTH_YELLOW_THRESHOLD -> Color(0xFFFFC107)
    else -> MaterialTheme.colorScheme.error
}

/**
 * 平台状态页（Phase 5-1）：各平台近 7 天开播检查成功率仪表。
 */
@Composable
fun PlatformHealthScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {},
) {
    val history by viewModel.checkResultHistory.collectAsState()
    val nowMs = System.currentTimeMillis()

    // 有数据的平台按成功率升序（问题平台排前面），同率按名字排序稳定展示
    val platforms = remember(history, nowMs) {
        history.entries
            .map { (platform, entries) -> platform to CheckResultHistory.stats(entries, nowMs) }
            .filter { it.second.total > 0 }
            .sortedWith(compareBy({ it.second.successRate }, { it.first }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_platform_health_row_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back)
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                stringResource(R.string.platform_health_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (platforms.isEmpty()) {
                Text(
                    stringResource(R.string.platform_health_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.padding(horizontal = 16.dp)) {
                    items(platforms, key = { it.first }) { (platform, stats) ->
                        PlatformHealthRow(platform, stats, history[platform].orEmpty())
                        Spacer(Modifier.size(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformHealthRow(platform: String, stats: PlatformHealthStats, entries: List<CheckResultEntry>) {
    val color = healthColor(stats)
    val fmt = remember(platform, entries.lastOrNull()?.ts) {
        val last = entries.maxByOrNull { it.ts }?.ts
        if (last != null && last > 0) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(last))
        } else ""
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(healthColor(stats), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        PlatformBadge(platformKey = platform)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.platform_health_rate_fmt,
                    (stats.successRate * 100).toInt(),
                    stats.ok,
                    stats.total,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (stats.total > 0) {
                Text(
                    stringResource(R.string.platform_health_last_check, fmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "${(stats.successRate * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}
