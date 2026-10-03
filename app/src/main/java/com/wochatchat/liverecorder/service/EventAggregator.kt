package com.wochatchat.liverecorder.service

/**
 * Phase 6-6.4 通知聚合纯逻辑：同平台连续开播/关播事件聚合进同一条
 * InboxStyle 通知，避免刷屏。
 *
 * 规则：
 * - 每个平台（platformKey）独立一条通知，窗口 [windowMs] 内的事件逐行累积
 * - 超出窗口的旧事件被淘汰（保持通知内容新鲜）
 * - 单条目最多展示 [MAX_LINES] 行，超出折叠为一条摘要行
 * - 全部纯函数：调用方持有状态，便于单测
 */
object EventAggregator {

    /** 单条聚合事件（isLive=true 开播 / false 关播）。 */
    data class Event(
        val isLive: Boolean,
        val anchor: String,
        val title: String,
        val ts: Long,
    )

    /** 聚合通知最多展开的行数，超出部分折叠进摘要行。 */
    const val MAX_LINES = 6

    /**
     * 追加一条事件并淘汰窗口外的旧事件，返回新的列表快照。
     * [entries] 为该平台当前聚合中的事件（按时间升序）。
     */
    fun append(entries: List<Event>, event: Event, nowMs: Long, windowMs: Long): List<Event> {
        val fresh = evictByWindow(entries, nowMs, windowMs)
        return fresh + event
    }

    /**
     * 淘汰早于 (nowMs - windowMs) 的事件。
     */
    fun evictByWindow(entries: List<Event>, nowMs: Long, windowMs: Long): List<Event> =
        entries.filter { nowMs - it.ts < windowMs }

    /** 展示行：超出 MAX_LINES 折叠，返回 (lines, extraCount)。 */
    fun displayLines(entries: List<Event>): Pair<List<Event>, Int> {
        val visible = entries.takeLast(MAX_LINES)
        return visible to (entries.size - visible.size)
    }

    /** 聚合标题类型：仅开播 / 仅关播 / 混合。 */
    fun titleKind(entries: List<Event>): Int =
        when {
            entries.isEmpty() -> TITLE_MIXED
            entries.all { it.isLive } -> TITLE_LIVE
            entries.none { it.isLive } -> TITLE_OFFLINE
            else -> TITLE_MIXED
        }

    const val TITLE_LIVE = 0
    const val TITLE_OFFLINE = 1
    const val TITLE_MIXED = 2
}
