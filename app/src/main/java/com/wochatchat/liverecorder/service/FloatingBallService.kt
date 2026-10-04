package com.wochatchat.liverecorder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wochatchat.liverecorder.MainActivity
import com.wochatchat.liverecorder.R
import com.wochatchat.liverecorder.RecorderApp
import com.wochatchat.liverecorder.data.AppLog
import com.wochatchat.liverecorder.recorder.RecordController
import com.wochatchat.liverecorder.ui.navigation.TabRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 悬浮球几何纯函数（V3-4 R1，独立出来便于单测）。
 */
internal object FloatingBallMath {

    /** 拖动时把坐标限制在屏幕内（不允许拖出可视区域）。 */
    fun clamp(x: Float, min: Float, max: Float): Float = when {
        x < min -> min
        x > max -> max
        else -> x
    }

    /** 松手贴边吸附：球心在屏幕左半 → 吸到左缘，否则吸到右缘。 */
    fun snapTargetX(rawX: Float, screenWidth: Int, viewWidth: Int): Float {
        val maxX = (screenWidth - viewWidth).toFloat()
        return if (rawX + viewWidth / 2f <= screenWidth / 2f) 0f else maxX
    }

    /** 判断触摸位移是否超过判定阈值（超过 = 拖动，未超过 = 点击）。 */
    fun exceededSlop(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dx) > slop || abs(dy) > slop
}

/**
 * V3-4 R1：跨 app 真悬浮球（前台服务 + WindowManager TYPE_APPLICATION_OVERLAY）。
 *
 * 职责：
 * - 常驻小浮标：可拖动、贴边吸附、半透明；点击展开/收起快捷面板。
 * - 面板：录制中条数 + 监控条数概要，快捷入口（去记录/去监控）。
 * - 常驻通知作为开关入口（低优先级），含「关闭悬浮球」Action。
 *
 * 生命周期：开关在设置页（AppSettings.floatingBallEnabled），
 * RecorderApp 收集变更启停本服务；进程被杀 → 视图随进程消失（不残留）。
 */
class FloatingBallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var windowManager: WindowManager
    private var ballView: TextView? = null
    private var panelView: LinearLayout? = null

    private var ballParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var screenWidth = 0
    private var screenHeight = 0

    // 拖动状态
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var ballStartX = 0f
    private var ballStartY = 0f
    private var dragging = false

    private val touchSlop: Float by lazy {
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
        )
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        if (!addViews()) {
            // 无悬浮窗权限 / 系统拒绝：服务自毁，不留空壳通知
            stopSelf()
            return
        }
        observeCounts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_APP -> openApp(tab = null)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 视图 ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /** 添加悬浮球与面板视图（无权限/添加失败返回 false）。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun addViews(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val ball = TextView(this).apply {
            text = getString(R.string.app_name).take(1)
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            alpha = 0.85f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1B1B1F.toInt())
            }
        }
        val size = dp(BALL_SIZE_DP)
        ballParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingBallMath.snapTargetX(0f, screenWidth, size).toInt()
            y = screenHeight / 3
        }
        ball.setOnTouchListener(ballTouchListener)
        ball.setOnClickListener { togglePanel() }
        return try {
            windowManager.addView(ball, ballParams)
            ballView = ball
            true
        } catch (e: Exception) {
            com.wochatchat.liverecorder.data.AppLog.w("FloatingBall", "悬浮球添加失败: ${e.message}")
            false
        }
    }

    /** 悬浮球触摸：拖动跟随 + 松手贴边；未拖动则交给 click 展开面板。 */
    private val ballTouchListener = View.OnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                ballStartX = (ballParams?.x ?: 0).toFloat()
                ballStartY = (ballParams?.y ?: 0).toFloat()
                dragging = false
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val p = ballParams ?: return@OnTouchListener false
                val dx = event.rawX - touchDownRawX
                val dy = event.rawY - touchDownRawY
                if (!dragging && FloatingBallMath.exceededSlop(dx, dy, touchSlop)) dragging = true
                if (dragging) {
                    val size = dp(BALL_SIZE_DP)
                    p.x = FloatingBallMath.clamp(
                        ballStartX + dx, 0f, (screenWidth - size).toFloat()
                    ).toInt()
                    p.y = FloatingBallMath.clamp(
                        ballStartY + dy, 0f, (screenHeight - size).toFloat()
                    ).toInt()
                    windowManager.updateViewLayout(v, p)
                }
                dragging
            }
            MotionEvent.ACTION_UP -> {
                val p = ballParams
                if (dragging && p != null) {
                    val size = dp(BALL_SIZE_DP)
                    val targetX = FloatingBallMath.snapTargetX(p.x.toFloat(), screenWidth, size)
                    // MVP：直接落位（动画增益可后补）
                    p.x = targetX.toInt()
                    windowManager.updateViewLayout(v, p)
                }
                dragging
            }
            else -> false
        }
    }

    /** 展开/收起快捷面板（面板朝屏幕内侧展开，靠右缘时面板向左）。 */
    private fun togglePanel() {
        val ball = ballView ?: return
        if (panelView != null) {
            removePanel()
            return
        }
        val size = dp(BALL_SIZE_DP)
        val onLeftEdge = (ballParams?.x ?: 0) + size / 2 <= screenWidth / 2

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE61B1B1F.toInt())
            }
        }
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val summary = TextView(this).apply {
            id = android.R.id.text1
            setTextColor(0xFFBDBDC4.toInt())
            textSize = 12f
        }
        panel.addView(title)
        panel.addView(summary)
        panel.addView(buttonRow())

        val width = dp(PANEL_WIDTH_DP)
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (onLeftEdge) (ballParams?.x ?: 0) + size else (ballParams?.x ?: 0) - width)
                .coerceIn(0, (screenWidth - width).coerceAtLeast(0))
            y = (ballParams?.y ?: 0).coerceIn(0, (screenHeight - dp(200)).coerceAtLeast(0))
        }
        try {
            windowManager.addView(panel, panelParams)
            panelView = panel
            updateSummaryText()
        } catch (e: Exception) {
            com.wochatchat.liverecorder.data.AppLog.w("FloatingBall", "面板添加失败: ${e.message}")
        }
    }

    /** 面板快捷按钮行：记录 / 监控 / 收起。 */
    private fun buttonRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun button(labelRes: Int, onClick: () -> Unit) {
            val btn = TextView(this).apply {
                text = getString(labelRes)
                setTextColor(Color.WHITE)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0x33FFFFFF)
                }
            }
            btn.setOnClickListener { onClick() }
            row.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
        }
        button(R.string.ball_panel_records) { openApp(Destination.RECORDS) }
        button(R.string.ball_panel_monitor) { openApp(Destination.MONITOR) }
        button(R.string.ball_panel_collapse) { removePanel() }
        return row
    }

    /** 打开 App 对应 Tab（悬浮球点击场景来自后台，必须 NEW_TASK）。 */
    private fun openApp(tab: String?) {
        removePanel()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tab.isNullOrBlank()) intent.putExtra(TabRouter.EXTRA_TAB, tab)
        startActivity(intent)
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelParams = null
    }

    private fun removeViews() {
        removePanel()
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
    }

    // ---------- 数据 ----------

    /** 收集录制/监控状态，刷新面板概要（录制中 N 条 · 监控 M 项）。 */
    private fun observeCounts() {
        val app = application as RecorderApp
        scope.launch {
            app.recordController.states.collect {
                updateSummaryText()
            }
        }
        scope.launch {
            app.monitorLoop.states.collect {
                updateSummaryText()
            }
        }
    }

    private fun updateSummaryText() {
        if (panelView == null) return
        val app = application as RecorderApp
        val recording = app.recordController.states.value.count {
            it.value is RecordController.RecordState.Resolving ||
                it.value is RecordController.RecordState.Recording ||
                it.value is RecordController.RecordState.Reconnecting
        }
        val watching = app.monitorLoop.states.value.size
        panelView?.findViewById<TextView>(android.R.id.text1)?.text =
            getString(R.string.ball_panel_summary, recording, watching)
    }

    // ---------- 通知 ----------

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_ball_title))
            .setContentText(getString(R.string.notif_ball_text))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notif_ball_action_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_ball),
            NotificationManager.IMPORTANCE_MIN,
        ).apply { description = getString(R.string.notif_channel_ball_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** 面板快捷入口目标 Tab（与 AppNavigation Destination.route 对齐）。 */
    private object Destination {
        const val MONITOR = "monitor"
        const val RECORDS = "records"
    }

    companion object {
        private const val CHANNEL_ID = "floating_ball"
        private const val NOTIFICATION_ID = 1002
        private const val BALL_SIZE_DP = 46
        private const val PANEL_WIDTH_DP = 240

        const val ACTION_STOP = "com.wochatchat.liverecorder.action.BALL_STOP"
        const val ACTION_OPEN_APP = "com.wochatchat.liverecorder.action.BALL_OPEN_APP"

        /** 启动悬浮球服务（开关开启且已授权时由 RecorderApp / 设置页调用）。 */
        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, FloatingBallService::class.java)
            )
        }

        /** 停止悬浮球服务（stopService 走 onDestroy 清理视图，后台可用不受限制）。 */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, FloatingBallService::class.java))
        }
    }
}
