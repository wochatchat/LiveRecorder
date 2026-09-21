package com.wochatchat.liverecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wochatchat.liverecorder.MainActivity
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.recorder.RecordController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台监控服务（Phase 2-2a 骨架）：dataSync 类型 + 常驻通知。
 *
 * 后续子任务在此 Service 内扩展：
 * - 2b 监控轮询循环（对齐 config.ini 循环时间语义）
 * - 2c/2d 录制任务调度 + 断流重连
 * - 2e 开播/关播通知
 *
 * 当前职责：维持前台存活（Doze 保护），并把录制状态机汇总到常驻通知。
 * 录制状态本身仍由进程级 RecordController 管理，Service 只做展示与保活。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            (application as RecorderApp).recordController.states.collect { states ->
                notifyCompat(buildNotification(states))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val notification = buildNotification(
            (application as RecorderApp).recordController.states.value
        )
        // dataSync 类型 API 29+；ServiceCompat 在低版本自动忽略 type 参数
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
    }

    private fun notifyCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return // 无通知权限时静默跳过，不影响前台服务本身
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(states: Map<String, RecordController.RecordState>): Notification {
        val active = states.values.count {
            it is RecordController.RecordState.Resolving || it is RecordController.RecordState.Recording
        }
        val text = if (active > 0) getString(R.string.notif_monitor_recording, active)
        else getString(R.string.notif_monitor_idle)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notif_monitor_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_monitor),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notif_channel_monitor_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1001

        /** 显式停止服务（无活动录制时由 ViewModel 发出）。 */
        const val ACTION_STOP = "com.wochatchat.liverecorder.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
