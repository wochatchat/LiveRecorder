package com.wochatchat.liverecorder.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wochatchat.liverecorder.MainActivity
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.data.AuthStore
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl

/**
 * 开播/关播事件通知（Phase 2-2e）：独立于监控常驻通知的渠道。
 *
 * Phase 6-6.4 通知聚合（InboxStyle）：同平台连续开播/关播事件聚合进
 * 同一条通知，避免刷屏。
 *
 * 行为：
 * - 每个平台（platformKey）共用一条通知 id，窗口 [windowMs] 内的事件逐行累积；
 *   超出窗口的旧事件自动淘汰，通知内容保持新鲜。
 * - 单事件 → InboxStyle 单行（视觉与原有通知一致，零延迟）。
 * - 多事件 → 聚合标题 + 多行 InboxStyle，展开显示全部事件。
 * - 点击跳转 App 并聚焦最新事件的 url（EXTRA_FOCUS_URL）。
 *
 * 注入 [clock] 便于测试，默认 System.currentTimeMillis()。
 */
class EventNotifier(
    context: Context,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val ctx = context.applicationContext

    // Phase 6-6.4：按平台 key 聚合事件（仅 appScope 单线程调用，非线程安全）
    private val inbox = mutableMapOf<String, MutableList<EventAggregator.Event>>()

    /** 创建事件渠道（App 启动时调用，保证发通知前渠道已存在）。 */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.notif_channel_events),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = ctx.getString(R.string.notif_channel_events_desc) }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 开播通知（聚合：同平台窗口内累积多主播动态）。 */
    fun notifyLive(url: String, anchorName: String, title: String) {
        val now = clock()
        appendAndPost(
            url = url,
            platformKey = platformKeyForUrl(url),
            event = EventAggregator.Event(isLive = true, anchor = anchorName, title = title, ts = now),
            now = now,
        )
    }

    /** 关播通知（聚合：同平台窗口内累积多主播动态）。 */
    fun notifyOffline(url: String, anchorName: String) {
        val now = clock()
        appendAndPost(
            url = url,
            platformKey = platformKeyForUrl(url),
            event = EventAggregator.Event(isLive = false, anchor = anchorName, title = "", ts = now),
            now = now,
        )
    }

    /** 低存储通知（2h）：固定 id（新一轮覆盖旧的），独立语义不走聚合。 */
    fun notifyStorageLow(thresholdGb: Double, freeGb: Double) {
        notifyFixed(
            id = STORAGE_NOTIFICATION_ID,
            title = ctx.getString(R.string.notif_storage_low_title),
            text = ctx.getString(R.string.notif_storage_low_text, thresholdGb, freeGb),
        )
    }

    /** 存储恢复通知：固定 id，独立语义不走聚合。 */
    fun notifyStorageResumed() {
        notifyFixed(
            id = STORAGE_NOTIFICATION_ID,
            title = ctx.getString(R.string.notif_storage_resumed_title),
            text = ctx.getString(R.string.notif_storage_resumed_text),
        )
    }

    /** 账号失效提醒（Phase 5-5.2）：固定 id，独立语义不走聚合。 */
    fun notifyAccountExpired(platformLabel: String) {
        notifyFixed(
            id = ACCOUNT_EXPIRED_NOTIFICATION_ID,
            title = ctx.getString(R.string.notif_account_expired_title),
            text = ctx.getString(R.string.notif_account_expired_text, platformLabel),
        )
    }

    // ---- 聚合核心 ----

    /** 追加事件并发布/更新该平台的聚合通知。 */
    private fun appendAndPost(url: String, platformKey: String, event: EventAggregator.Event, now: Long) {
        if (!hasNotificationPermission()) return

        val list = inbox.getOrPut(platformKey) { mutableListOf() }
        list.clear()
        list.addAll(EventAggregator.evictByWindow(list.toList(), now, windowMs))
        list.add(event)

        val entries = list.toList()
        val (visible, extraCount) = EventAggregator.displayLines(entries)
        val platformLabel = AuthStore.labelOf(platformKey)

        val title = when (EventAggregator.titleKind(entries)) {
            EventAggregator.TITLE_LIVE -> ctx.getString(R.string.notif_event_live_title_agg, platformLabel)
            EventAggregator.TITLE_OFFLINE -> ctx.getString(R.string.notif_event_offline_title_agg, platformLabel)
            else -> ctx.getString(R.string.notif_event_mixed_title, platformLabel)
        }
        val lineOf: (EventAggregator.Event) -> String = { e ->
            if (e.isLive) ctx.getString(R.string.notif_event_agg_live_line, e.anchor, e.title)
            else ctx.getString(R.string.notif_event_agg_offline_line, e.anchor)
        }

        val style = NotificationCompat.InboxStyle()
            .setSummaryText(platformLabel)
        visible.forEach { e -> style.addLine(lineOf(e)) }
        if (extraCount > 0) {
            style.addLine(ctx.getString(R.string.notif_event_agg_more_line, extraCount))
        }

        // 6f R22：点击直达最新事件的监控条目
        val intent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_FOCUS_URL, url)
        val contentIntent = PendingIntent.getActivity(
            ctx, aggregatedId(platformKey), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            // 折叠态：显示最新一条事件的摘要
            .setContentText(lineOf(entries.last()))
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        ctx.getSystemService(NotificationManager::class.java)
            ?.notify(aggregatedId(platformKey), notification)
    }

    // ---- 通用工具 ----

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED

    /** 独立语义通知（存储/账号失效）：BigTextStyle，固定 id。 */
    private fun notifyFixed(id: Int, title: String, text: String) {
        if (!hasNotificationPermission()) return
        val intent = Intent(ctx, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun aggregatedId(platformKey: String): Int =
        EVENT_NOTIFICATION_ID_BASE + (platformKey.hashCode() and 0x7FFFFFFF) % EVENT_NOTIFICATION_ID_RANGE

    companion object {
        const val CHANNEL_ID = "events"

        /** 事件通知 id 区间 [10000, 100000)，避开监控常驻通知 id（1001）。 */
        private const val EVENT_NOTIFICATION_ID_BASE = 10000
        private const val EVENT_NOTIFICATION_ID_RANGE = 90000

        /** 存储事件通知固定 id（低于事件区间，2h）。 */
        private const val STORAGE_NOTIFICATION_ID = 9900

        /** 账号失效提醒固定 id（Phase 5-5.2，与存储事件同区间）。 */
        private const val ACCOUNT_EXPIRED_NOTIFICATION_ID = 9800

        /** 6f R22：点击直达 extra 键（与 MainActivity 约定）。 */
        const val EXTRA_FOCUS_URL = "focus_url"

        /** Phase 6-6.4：聚合窗口，同平台 5 分钟内的事件合并进一条通知。 */
        const val DEFAULT_WINDOW_MS = 5 * 60_000L
    }
}
