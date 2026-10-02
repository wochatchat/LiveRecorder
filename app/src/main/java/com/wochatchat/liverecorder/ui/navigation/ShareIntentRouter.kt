package com.wochatchat.liverecorder.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedList

/**
 * Phase 2：分享/剪贴板路由——Pending URL 队列。
 * MainActivity 接收 URL（来自分享或剪贴板）写入队列，MonitorScreen 消费。
 * 队列支持多次分享排队依次展示。
 */
object ShareIntentRouter {

    /** 待展示确认卡片的 URL 队列（非 null 表示有待处理）。 */
    val pendingUrls: MutableStateFlow<LinkedList<String>> = MutableStateFlow(LinkedList())

    /** 追加一个 URL 到队列尾部。已在队列中的不重复追加。 */
    fun enqueue(url: String) {
        val q = pendingUrls.value
        if (url !in q) {
            q.addLast(url)
            pendingUrls.value = q
        }
    }

    /** 取出队首 URL（消费后调用）。若队列空则无操作。 */
    fun dequeue(): String? {
        val q = pendingUrls.value
        if (q.isEmpty()) return null
        val url = q.removeFirst()
        pendingUrls.value = q
        return url
    }

    /** 当前队列是否为空。 */
    val isEmpty: Boolean get() = pendingUrls.value.isEmpty()
}