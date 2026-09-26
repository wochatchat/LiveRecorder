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
import com.wochatchat.liverecorder.data.MonitorStore
import com.wochatchat.liverecorder.monitor.MonitorLoop
import com.wochatchat.liverecorder.recorder.RecordController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台监控服务：dataSync 类型 + 常驻通知 + 监控轮询循环。
 *
 * 职责边界：
 * - 保活 + 展示：录制状态机（RecordController）与监控状态（MonitorLoop）各自管理，
 *   Service 只做汇总展示与前台存活（Doze 保护）。
 * - 2c/2d（录制调度、断流重连）、2e（开播/关播通知）后续在此扩展。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            (application as RecorderApp).recordController.states.collect { _ ->
                notifyCompat(buildNotification())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_MONITOR -> {
                startInForeground()
                startMonitoring()
            }
            else -> {
                // 录制拉起 / START_STICKY 重启（intent=null）：按开关决定是否恢复轮询
                startInForeground()
                if (intent == null) {
                    scope.launch {
                        if (MonitorStore(application).monitorEnabled.first()) startMonitoring()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitoring() {
        val app = application as RecorderApp
        val store = MonitorStore(application)
        // 5a：循环时间(秒)（上游 config.ini，默认 300）
        scope.launch {
            val intervalSec = runCatching { app.appSettings.settings.first().loopIntervalSec }
                .getOrDefault(MonitorLoop.DEFAULT_INTERVAL_SEC)
            app.monitorLoop.start(scope, urls = { store.enabledUrls.first() }, intervalSec = intervalSec)
        }
    }

    private fun stopMonitoring() {
        (application as RecorderApp).monitorLoop.stop()
    }

    private fun startInForeground() {
        val notification = buildNotification()
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

    private fun buildNotification(): Notification {
        val app = application as RecorderApp
        val recordStates = app.recordController.states.value
        val monitorStates = app.monitorLoop.states.value
        val active = recordStates.values.count {
            it is RecordController.RecordState.Resolving || it is RecordController.RecordState.Recording ||
                it is RecordController.RecordState.Reconnecting
        }
        val live = monitorStates.values.count { it is MonitorLoop.State.Live }
        val text = when {
            active > 0 -> getString(R.string.notif_monitor_recording, active)
            monitorStates.isNotEmpty() -> getString(R.string.notif_monitor_watching, live, monitorStates.size)
            else -> getString(R.string.notif_monitor_idle)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
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

        /** 启动监控轮询（监控开关打开时由 ViewModel 发出）。 */
        const val ACTION_RUN_MONITOR = "com.wochatchat.liverecorder.action.RUN_MONITOR"

        /** 仅拉起前台服务（录制中保活，不启动轮询）。 */
        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 拉起前台服务并启动监控轮询。 */
        fun startMonitor(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_RUN_MONITOR }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MonitorService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
