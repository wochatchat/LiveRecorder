/*
 * RecordingNotifier — Phase 6-6.3：常驻录制进度通知。
 *
 * 每条活动录制一个通知（id 按 url 派生，区间 [2000,3000)，避开监控常驻 1001
 * 与事件通知 10000+），实时显示 主播名+标题+时长+大小，展开含平台/画质，
 * 附「停止录制」Action（6-6.2 通知内快捷操作）。
 * 录制结束/失败后自动撤销对应通知。IMPORTANCE_LOW 静默更新不响铃。
 */
package com.wochatchat.liverecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.wochatchat.liverecorder.MainActivity
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.StatsFormat

class RecordingNotifier(private val context: Context) {

    /** 当前已展示通知的 url 集合（撤销用）。 */
    private val shown = LinkedHashSet<String>()

    /** 按最新录制状态全量刷新（录制中/解析中/重连中展示，其余撤销）。 */
    @Synchronized
    fun update(states: Map<String, RecordController.RecordState>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val active = states.filterValues { s ->
            s is RecordController.RecordState.Resolving ||
                s is RecordController.RecordState.Recording ||
                s is RecordController.RecordState.Reconnecting
        }
        synchronized(shown) {
            (shown - active.keys).forEach { nm.cancel(notificationId(it)) }
            shown.clear()
            shown.addAll(active.keys)
        }
        active.forEach { (url, state) -> nm.notify(notificationId(url), build(url, state)) }
    }

    private fun build(url: String, state: RecordController.RecordState): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, notificationId(url),
            Intent(context, MainActivity::class.java).putExtra("focus_url", url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context, notificationId(url),
            Intent(context, MonitorService::class.java)
                .setAction(MonitorService.ACTION_STOP_RECORDING)
                .putExtra(MonitorService.EXTRA_RECORD_URL, url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notif_action_stop_record), stopIntent)
        when (state) {
            is RecordController.RecordState.Recording -> {
                val who = state.anchorName.ifBlank { url }
                b.setContentTitle(context.getString(R.string.notif_recording_title, who))
                    .setContentText(
                        context.getString(
                            R.string.notif_recording_text,
                            state.title,
                            StatsFormat.duration(state.durationMs),
                            StatsFormat.bytes(state.bytes),
                        )
                    )
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(
                                context.getString(
                                    R.string.notif_recording_big_text,
                                    state.title,
                                    state.platform,
                                    state.quality,
                                    StatsFormat.duration(state.durationMs),
                                    StatsFormat.bytes(state.bytes),
                                )
                            )
                    )
            }
            is RecordController.RecordState.Reconnecting ->
                b.setContentTitle(context.getString(R.string.notif_recording_reconnect_title))
                    .setContentText(
                        context.getString(R.string.notif_recording_reconnect_text, state.attempt, state.message)
                    )
            else ->
                b.setContentTitle(context.getString(R.string.notif_recording_resolving_title))
                    .setContentText(url)
        }
        return b.build()
    }

    private fun notificationId(url: String): Int =
        NOTIFICATION_ID_BASE + (url.hashCode() and 0x7FF)

    companion object {
        const val CHANNEL_ID = "recording"

        /** 通知 id 区间 [2000, 3000)：低于事件区间 10000+，避开监控常驻 1001。 */
        private const val NOTIFICATION_ID_BASE = 2000

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_recording_desc) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
