package com.wochatchat.liverecorder

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wochatchat.liverecorder.platform.UrlExtractor
import com.wochatchat.liverecorder.platform.PlatformRouter
import com.wochatchat.liverecorder.service.EventNotifier
import com.wochatchat.liverecorder.ui.navigation.AppNavigation
import com.wochatchat.liverecorder.ui.navigation.FocusRouter
import com.wochatchat.liverecorder.ui.navigation.ShareIntentRouter
import com.wochatchat.liverecorder.ui.theme.LiveRecorderTheme

class MainActivity : ComponentActivity() {

    /** Android 12+：Activity 从后台切回前台时系统会显示粘贴提示，此时 onUserLeaveHint 会被触发。 */
    private var startedFromBackground = false

    /** 上次已处理的剪贴板文本（哈希），用于去重。 */
    private var lastHandledClipboardHash: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            LiveRecorderTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：通知点击命中已存在实例时走这里
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // startedFromBackground 在 onStop 后首次 onStart 时为 true（从后台切回）
        // 注意：旋转等配置变化也会走 onStop/onStart，此时 startedFromBackground 也为 true
        // 但配置变化不会改变剪贴板内容，所以去重哈希仍然有效
    }

    override fun onResume() {
        super.onResume()
        if (startedFromBackground) {
            startedFromBackground = false
            // 从后台回到前台：检测剪贴板
            checkClipboard()
        }
    }

    override fun onUserLeaveHint() {
        // Android 12+ 用户主动离开（如切换 App、点击粘贴通知）
        super.onUserLeaveHint()
        startedFromBackground = true
    }

    // ---------- private ----------

    /**
     * 处理所有外部 Intent：
     * - ACTION_SEND (text/plain)：分享面板接收直播链接
     * - EXTRA_FOCUS_URL：事件通知点击直达
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSendIntent(intent)
                }
            }
            Intent.ACTION_MAIN -> {
                // LAUNCHER 点击：清除 startedFromBackground（用户主动打开，非后台切回）
                startedFromBackground = false
            }
        }
        // 6f R22：事件通知点击直达——提取 url extra 交给 FocusRouter（MonitorScreen 消费）。
        val focusUrl = intent.getStringExtra(EventNotifier.EXTRA_FOCUS_URL)
        if (!focusUrl.isNullOrBlank()) {
            FocusRouter.focusUrl.value = focusUrl
        }
    }

    /** 处理分享 Intent：提取 URL 入路由队列。 */
    private fun handleSendIntent(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = UrlExtractor.extractSupported(sharedText)
        if (url != null && PlatformRouter.isSupported(url)) {
            ShareIntentRouter.enqueue(url)
        }
    }

    /** 检查剪贴板：含支持的直播 URL 则入队列。已在队列/最近已处理的不重复添加。 */
    private fun checkClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        // hasPrimaryClip() 检查是否有内容；getPrimaryClip() 可能为 null（Android 12+ 无内容时）
        if (!cm.hasPrimaryClip()) return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).text?.toString() ?: return

        val hash = text.hashCode()
        if (hash == lastHandledClipboardHash) return  // 与上次相同，跳过
        lastHandledClipboardHash = hash

        val url = UrlExtractor.extractSupported(text)
        if (url != null && PlatformRouter.isSupported(url)) {
            ShareIntentRouter.enqueue(url)
        }
    }
}
