package com.wochatchat.liverecorder.data

/** V3-3 R2：监控列表展示行模型（分组头 / URL 卡片），供 LazyColumn 拍平渲染。 */
sealed interface MonitorRow {
    data class Header(val label: String) : MonitorRow
    data class UrlRow(val url: String) : MonitorRow
}

/**
 * V3-3 R2：监控列表分组纯逻辑（可单测）。
 *
 * 置顶条目永远排最前；分组模式下按平台聚合，组间顺序按非置顶条目首次出现序，
 * 组内保持原相对顺序。不分组时合并为单组（置顶仍浮到最前）。
 */
data class MonitorGroup(
    /** 分组标题（null = 无标题的单组）；置顶组由调用方传入专属标签。 */
    val label: String?,
    val urls: List<String>,
)

/**
 * @param urls 监控 URL 全量列表（原顺序）
 * @param pinned 置顶 URL 集合
 * @param groupByPlatform 是否按平台分组
 * @param platformKeyOf URL → 平台键
 * @param platformLabelOf 平台键 → 显示名
 * @param pinnedLabel 置顶组标题（仅分组模式且存在置顶时出现）
 */
fun groupMonitorUrls(
    urls: List<String>,
    pinned: Set<String>,
    groupByPlatform: Boolean,
    platformKeyOf: (String) -> String,
    platformLabelOf: (String) -> String,
    pinnedLabel: String,
): List<MonitorGroup> {
    val pinnedList = urls.filter { it in pinned }
    val rest = urls.filter { it !in pinned }
    if (!groupByPlatform) {
        // 不分组：置顶条目浮到列表最前，其余保持原顺序
        return listOf(MonitorGroup(null, pinnedList + rest))
    }
    val groups = mutableListOf<MonitorGroup>()
    if (pinnedList.isNotEmpty()) {
        groups.add(MonitorGroup(pinnedLabel, pinnedList))
    }
    // 组间顺序按非置顶条目首次出现序（LinkedHashMap 保序）
    val byPlatform = LinkedHashMap<String, MutableList<String>>()
    rest.forEach { u -> byPlatform.getOrPut(platformKeyOf(u)) { mutableListOf() }.add(u) }
    byPlatform.forEach { (pk, list) ->
        groups.add(MonitorGroup(platformLabelOf(pk), list))
    }
    return groups
}
