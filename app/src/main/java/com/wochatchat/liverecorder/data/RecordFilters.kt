package com.wochatchat.liverecorder.data

import com.wochatchat.liverecorder.ui.components.PLATFORM_LABELS
import com.wochatchat.liverecorder.ui.components.platformKeyForUrl

/**
 * V3-2：记录页筛选纯逻辑（平台 chip 推导 + 筛选组合），与 ViewModel 解耦便于单测。
 */
object RecordFilters {

    /** 条目平台键：优先历史自带中文平台名反查 [PLATFORM_LABELS]（落库值带「直播」后缀，先剥除；
     *  幽灵回收条目无 URL），回落 URL 域名推断。 */
    fun platformKeyOf(entry: RecordHistoryEntry): String {
        val p = entry.platform.trim().removeSuffix("直播")
        if (p.isNotBlank()) {
            PLATFORM_LABELS.entries.firstOrNull { it.value == p }?.let { return it.key }
        }
        return if (entry.url.isBlank()) "" else platformKeyForUrl(entry.url)
    }

    /**
     * 动态平台 chip 键列表：按记录数降序、次数相同按键名升序（稳定不跳变）。
     * 空平台键（无法识别）不参与筛选，直接排除。
     */
    fun platformKeys(entries: List<RecordHistoryEntry>): List<String> =
        entries.map { platformKeyOf(it) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /**
     * 筛选组合：时间段（rangeStartMs<=0 表示不限）+ 平台键（null 不限）+ 搜索
     * （大小写不敏感，命中主播名/标题/平台键任一）。
     */
    fun apply(
        list: List<RecordHistoryEntry>,
        rangeStartMs: Long,
        platformKey: String?,
        query: String,
    ): List<RecordHistoryEntry> = list.filter { e ->
        (rangeStartMs <= 0L || e.endTimeMs >= rangeStartMs) &&
            (platformKey == null || platformKeyOf(e) == platformKey) &&
            (query.isBlank() || run {
                val q = query.trim()
                e.anchorName.contains(q, ignoreCase = true) ||
                    e.title.contains(q, ignoreCase = true) ||
                    platformKeyOf(e).contains(q, ignoreCase = true)
            })
    }
}
