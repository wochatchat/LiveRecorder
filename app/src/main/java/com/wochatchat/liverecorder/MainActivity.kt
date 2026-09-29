package com.wochatchat.liverecorder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wochatchat.liverecorder.service.EventNotifier
import com.wochatchat.liverecorder.ui.navigation.AppNavigation
import com.wochatchat.liverecorder.ui.navigation.FocusRouter
import com.wochatchat.liverecorder.ui.theme.LiveRecorderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleFocusIntent(intent)
        setContent {
            LiveRecorderTheme {
                AppNavigation()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop：通知点击命中已存在实例时走这里
        handleFocusIntent(intent)
    }

    /** 6f R22：事件通知点击直达——提取 url extra 交给 FocusRouter（MonitorScreen 消费）。 */
    private fun handleFocusIntent(intent: Intent?) {
        val url = intent?.getStringExtra(EventNotifier.EXTRA_FOCUS_URL) ?: return
        FocusRouter.focusUrl.value = url
    }
}
