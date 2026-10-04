package com.wochatchat.liverecorder.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 6f R22：通知点击直达——EventNotifier 的开播/关播通知带 url extra，
 * MainActivity 接收后写入此路由，MonitorScreen 消费（滚动定位 + 高亮 4s）。
 */
object FocusRouter {
    val focusUrl = MutableStateFlow<String?>(null)

    /** 消费后清除，避免重复高亮。 */
    fun clear() {
        focusUrl.value = null
    }
}

/**
 * V3-4 R1：悬浮球面板快捷入口——MainActivity 收到 EXTRA_TAB 后写入此路由，
 * MainScaffold 消费（导航到对应底部 Tab）。
 */
object TabRouter {
    const val EXTRA_TAB = "focus_tab"
    val pendingTab = MutableStateFlow<String?>(null)
}
