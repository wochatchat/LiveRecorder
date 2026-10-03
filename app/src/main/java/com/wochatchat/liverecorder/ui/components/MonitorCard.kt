package com.wochatchat.liverecorder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wochatchat.liverecorder.R
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
    url.contains("cc.163.com/") -> "netease"
    url.contains("live.baidu.com/") -> "baidu"
    url.contains("weibo.com/") -> "weibo"
    url.contains("lives.jd.com/") -> "jd"
    url.contains("zhihu.com/") -> "zhihu"
    url.contains("haixiutv.com/") -> "haixiu"
    url.contains("lehaitv.com/") -> "lehaitv"
    url.contains("imkktv.com/") -> "laixiu"
    url.contains("liveme.com/") -> "liveme"
    url.contains("tb.cn") -> "taobao"
    url.contains("xiaohongshu.com/") || url.contains("xhslink.com/") -> "xiaohongshu"
    url.contains("tiktok.com/") -> "tiktok"
    url.contains("twitch.tv/") -> "twitch"
    url.contains("youtube.com/") || url.contains("youtu.be/") -> "youtube"
    url.contains("live.shopee") || url.contains("shp.ee/") -> "shopee"
    url.contains("chzzk.naver.com/") -> "chzzk"
    url.contains("acfun.cn/") -> "acfun"
    url.contains("huajiao.com") -> "huajiao"
    url.contains("7u66.com") -> "liuxing"
    url.contains("inke.cn") -> "inke"
    url.contains("ybw1666.com") -> "yinbo"
    url.contains("sooplive.co.kr/") || url.contains("sooplive.com/") -> "soop"
    url.contains("pandalive.co.kr/") -> "pandatv"
    url.contains("winktv.co.kr/") -> "winktv"
    url.contains("flextv.co.kr/") || url.contains("ttinglive.com/") -> "flextv"
    url.contains("popkontv.com/") -> "popkontv"
    url.contains("missevan.com") -> "maoerfm"
    url.contains("kugou.com/") || url.contains("fanxing.kugou.com") ||
        url.contains("fanxing2.kugou.com") || url.contains("mfanxing.kugou.com") -> "kugou"
    url.contains("tlclw.com/") -> "changliao"
    url.contains("vvxqiu.com/") -> "vvxqiu"
    url.contains("17.live/") -> "live17"
    url.contains("lang.live/") -> "langlive"
    url.contains("weimipopo.com/") || url.contains("catshow168.com/") -> "pplive"
    url.contains("6.cn/") -> "liujianfang"
    url.contains("lailianjie.com/") -> "lianjie"
    url.contains("qiandurebo.com/") -> "qiandurebo"
    url.contains("showroom-live.com/") -> "showroom"
    url.contains("blued.cn/") -> "blued"
    url.contains("twitcasting.tv/") ||
    url.contains("twitcasting.jp/") ||
    url.contains("twitcasting.net/") -> "twitcasting"
    url.contains(".m3u8") || url.contains(".flv") -> "custom"
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
    onSettings: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    /** Phase 4-4.1：是否有单条参数覆盖（用于指示徽标，无覆盖可传 false）。 */
    hasOverride: Boolean = false,
    /** Phase 11-11.2：绑定的录制账号昵称（未绑定/默认账号传 null，不显示徽标）。 */
    boundAccount: String? = null,
    /** V3-1：诊断 DBG 行显示开关（设置页控制，默认关）。 */
    showDiag: Boolean = false,
    /** V3-3 R1：紧凑两行卡片（默认关，由设置页「紧凑模式」控制）。 */
    compact: Boolean = false,
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
        if (compact) {
            // V3-3 R1：紧凑两行卡片（平台色条 + 主播名主视觉 + 摘要/图标行，点击展开标题与 URL）
            CompactCardInner(
                url = url,
                recordState = recordState,
                monitorState = monitorState,
                disabled = disabled,
                unhealthy = unhealthy,
                roundInfo = roundInfo,
                recording = recording,
                boundAccount = boundAccount,
                showDiag = showDiag,
                onEdit = onEdit,
                onSettings = onSettings,
                onToggleEnabled = onToggleEnabled,
                onStart = onStart,
                onStop = onStop,
                hasOverride = hasOverride,
                onDelete = { showConfirmDelete = true },
            )
        } else {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlatformBadge(platformKeyForUrl(url))
                StatusBadge(recordState, monitorState, disabled, unhealthy)
                // Phase 11-11.2：绑定账号徽标（默认账号不显示）
                if (!boundAccount.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.accounts_bound_badge, boundAccount),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 单条启停（2g）：停用后不参与轮询与自动录制（上游 # 注释行语义）
                // 6f-4：无障碍——Switch 必须有 contentDescription
                val switchLabel = if (!disabled) stringResource(R.string.monitor_switch_disable)
                    else stringResource(R.string.monitor_switch_enable)
                Switch(
                    checked = !disabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.semantics { contentDescription = switchLabel }
                )
            }

            Spacer(Modifier.height(4.dp))
            // 主标题：主播名为主（U6）；未解析到时回落平台名
            val live = monitorState as? MonitorLoop.State.Live
            Text(
                text = live?.anchorName?.takeIf { it.isNotBlank() }
                    ?: (PLATFORM_LABELS[platformKeyForUrl(url)]?.let {
                        stringResource(R.string.monitor_fallback_title, it)
                    } ?: url),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (disabled) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.onSurface
            )
            // 直播标题副行
            if (live != null && live.title.isNotBlank()) {
                Text(
                    stringResource(R.string.live_title_quoted, live.title),
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
                RecordStatusLine(recordState, showDiag = showDiag)
            } else {
                RoundSummaryCaption(roundInfo)
            }

            Row {
                IconButton(onClick = if (recording) onStop else onStart, enabled = !disabled) {
                    Icon(
                        if (recording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (recording) stringResource(R.string.desc_stop) else stringResource(R.string.desc_record),
                        tint = if (recording)
                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onEdit, enabled = !disabled) {
                    Icon(
                        Icons.Default.Edit, contentDescription = stringResource(R.string.desc_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Phase 4-4.1：单条录制参数设置
                IconButton(onClick = onSettings, enabled = !disabled) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.desc_settings),
                        tint = if (hasOverride) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
                IconButton(onClick = { showConfirmDelete = true }) {
                    Icon(
                        Icons.Default.Close, contentDescription = stringResource(R.string.desc_delete),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        } // else：完整卡片（旧布局，可从设置页切回）
    }
}

/**
 * V3-3 R1：紧凑两行监控卡片。
 * 行 1：平台徽标 + 主播名（主视觉，加大）+ 账号徽标 + 状态徽标 + 启停开关；
 * 行 2：状态/摘要（录制中 时长·大小 / 空闲 下次检查）+ 紧凑图标组（录制/参数/编辑/删除）。
 * 标题与 URL 收进点击展开区；左侧平台色条辅助区分多条任务。
 */
@Composable
private fun CompactCardInner(
    url: String,
    recordState: RecordController.RecordState?,
    monitorState: MonitorLoop.State?,
    disabled: Boolean,
    unhealthy: Boolean,
    roundInfo: MonitorRoundInfo,
    recording: Boolean,
    boundAccount: String?,
    showDiag: Boolean,
    onEdit: () -> Unit,
    onSettings: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    hasOverride: Boolean,
    onDelete: () -> Unit,
) {
    val pk = platformKeyForUrl(url)
    val barColor = PLATFORM_COLORS[pk] ?: MaterialTheme.colorScheme.outline
    var expanded by remember { mutableStateOf(false) }
    val live = monitorState as? MonitorLoop.State.Live
    // 组合期取好字符串，lambda 内只做纯切换
    val switchLabel = if (!disabled) stringResource(R.string.monitor_switch_disable)
    else stringResource(R.string.monitor_switch_enable)

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        // 平台色条（停用置灰）
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(
                    if (disabled) MaterialTheme.colorScheme.outline.copy(alpha = 0.3f) else barColor
                )
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { expanded = !expanded }
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        ) {
            // 行 1：徽标 + 主播名 + 状态 + 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PlatformBadge(pk)
                Text(
                    text = live?.anchorName?.takeIf { it.isNotBlank() }
                        ?: (PLATFORM_LABELS[pk]?.let {
                            stringResource(R.string.monitor_fallback_title, it)
                        } ?: url),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (disabled) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!boundAccount.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            stringResource(R.string.accounts_bound_badge, boundAccount),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusBadge(recordState, monitorState, disabled, unhealthy)
                // 6f-4：无障碍——Switch 必须有 contentDescription
                Switch(
                    checked = !disabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.semantics { contentDescription = switchLabel }
                )
            }

            // 行 2：摘要 + 紧凑图标组
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    if (recordState != null) {
                        RecordStatusLine(recordState, showDiag = showDiag)
                    } else {
                        RoundSummaryCaption(roundInfo)
                    }
                    // 点击展开：直播标题 + URL（小字）
                    if (expanded) {
                        if (live != null && live.title.isNotBlank()) {
                            Text(
                                stringResource(R.string.live_title_quoted, live.title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (disabled) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    CompactIconButton(
                        icon = if (recording) Icons.Default.Stop else Icons.Default.PlayArrow,
                        desc = if (recording) stringResource(R.string.desc_stop)
                        else stringResource(R.string.desc_record),
                        tint = if (recording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        enabled = !disabled,
                        onClick = if (recording) onStop else onStart,
                    )
                    CompactIconButton(
                        icon = Icons.Default.Edit,
                        desc = stringResource(R.string.desc_edit),
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = !disabled,
                        onClick = onEdit,
                    )
                    CompactIconButton(
                        icon = Icons.Default.Settings,
                        desc = stringResource(R.string.desc_settings),
                        tint = if (hasOverride) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        enabled = !disabled,
                        onClick = onSettings,
                    )
                    CompactIconButton(
                        icon = Icons.Default.Close,
                        desc = stringResource(R.string.desc_delete),
                        tint = MaterialTheme.colorScheme.outline,
                        enabled = true,
                        onClick = onDelete,
                    )
                }
            }
        }
    }
}

/** V3-3 R1：紧凑图标按钮（32dp 触控 + 18dp 图标，比默认 IconButton 省一半高度）。 */
@Composable
private fun CompactIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp)
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(18.dp))
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
    val context = LocalContext.current
    val now = remember(tick, info) { System.currentTimeMillis() }
    val parts = buildList {
        if (info.lastCheckMs > 0) {
            add(context.getString(R.string.roundinfo_last_check, relativeAgo(context, now - info.lastCheckMs)))
        }
        if (info.nextCheckMs > now) {
            add(context.getString(R.string.roundinfo_next, relativeIn(context, info.nextCheckMs - now)))
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

/** 毫秒差 → 「刚刚 / X 分钟前 / X 小时前」（资源化：需 Context）。 */
private fun relativeAgo(context: android.content.Context, deltaMs: Long): String = when {
    deltaMs < 60_000L -> context.getString(R.string.time_just_now)
    deltaMs < 3_600_000L -> context.getString(R.string.time_minutes_ago, deltaMs / 60_000L)
    else -> context.getString(R.string.time_hours_ago, deltaMs / 3_600_000L)
}

/** 毫秒差 → 「1 分钟内 / X 分钟后 / X 小时后」。 */
private fun relativeIn(context: android.content.Context, deltaMs: Long): String = when {
    deltaMs < 60_000L -> context.getString(R.string.time_in_1min)
    deltaMs < 3_600_000L -> context.getString(R.string.time_in_minutes, deltaMs / 60_000L)
    else -> context.getString(R.string.time_in_hours, deltaMs / 3_600_000L)
}
