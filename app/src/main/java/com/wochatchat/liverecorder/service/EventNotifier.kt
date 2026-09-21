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

/**
 * 开播/关播事件通知（Phase 2-2e）：独立于监控常驻通知的渠道，
 * 开播/关播各发一条即时通知，点击跳转 App（MainActivity）。
 * 同一 url 复用同一通知 id（新一轮事件覆盖旧的），autoCancel 点击即清除。
 */
class EventNotifier(private val context: Context) {

    /** 创建事件渠道（App 启动时调用，保证发通知前渠道已存在）。 */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_events),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_events_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 开播通知：主播名 + 标题。 */
    fun notifyLive(url: String, anchorName: String, title: String) {
        val text = if (title.isBlank()) anchorName else "$anchorName「$title」"
        notify(
            id = notificationId(url),
            title = context.getString(R.string.notif_event_live_title),
            text = context.getString(R.string.notif_event_live_text, text),
            icon = android.R.drawable.ic_media_play,
        )
    }

    /** 关播通知：主播名已下播。 */
    fun notifyOffline(url: String, anchorName: String) {
        notify(
            id = notificationId(url),
            title = context.getString(R.string.notif_event_offline_title),
            text = context.getString(R.string.notif_event_offline_text, anchorName),
            icon = android.R.drawable.ic_menu_close_clear_cancel,
        )
    }

    /** 低存储通知（2h）：剩余空间低于阈值，监控录制已暂停。固定 id（新一轮覆盖旧的）。 */
    fun notifyStorageLow(thresholdGb: Double, freeGb: Double) {
        notify(
            id = STORAGE_NOTIFICATION_ID,
            title = context.getString(R.string.notif_storage_low_title),
            text = context.getString(R.string.notif_storage_low_text, thresholdGb, freeGb),
            icon = android.R.drawable.ic_dialog_alert,
        )
    }

    /** 存储恢复通知：监控录制自动继续。 */
    fun notifyStorageResumed() {
        notify(
            id = STORAGE_NOTIFICATION_ID,
            title = context.getString(R.string.notif_storage_resumed_title),
            text = context.getString(R.string.notif_storage_resumed_text),
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    private fun notify(id: Int, title: String, text: String, icon: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // 无通知权限时静默跳过
        }
        val contentIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    private fun notificationId(url: String): Int =
        EVENT_NOTIFICATION_ID_BASE + (url.hashCode() and 0x7FFFFFFF) % EVENT_NOTIFICATION_ID_RANGE

    companion object {
        const val CHANNEL_ID = "events"

        /** 事件通知 id 区间 [10000, 100000)，避开监控常驻通知 id（1001）。 */
        private const val EVENT_NOTIFICATION_ID_BASE = 10000
        private const val EVENT_NOTIFICATION_ID_RANGE = 90000

        /** 存储事件通知固定 id（低于事件区间，2h）。 */
        private const val STORAGE_NOTIFICATION_ID = 9900
    }
}
