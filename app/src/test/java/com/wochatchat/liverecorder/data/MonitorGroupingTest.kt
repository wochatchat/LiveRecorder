package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** V3-3 R2：监控列表分组纯逻辑单测（置顶优先 + 平台分组 + 顺序稳定性）。 */
class MonitorGroupingTest {

    private fun keyOf(url: String): String = when {
        "douyin" in url -> "douyin"
        "huya" in url -> "huya"
        else -> "douyu"
    }

    private fun labelOf(pk: String): String = mapOf(
        "douyin" to "抖音", "huya" to "虎牙", "douyu" to "斗鱼"
    )[pk] ?: pk

    private val urls = listOf(
        "https://live.douyin.com/1",
        "https://www.huya.com/2",
        "https://live.douyin.com/3",
        "https://www.douyu.com/4",
    )

    @Test
    fun `不分组时置顶浮到最前 其余保持原序`() {
        val groups = groupMonitorUrls(
            urls, pinned = setOf("https://www.douyu.com/4"), groupByPlatform = false,
            platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
        )
        assertEquals(1, groups.size)
        assertEquals(null, groups[0].label)
        assertEquals(
            listOf(
                "https://www.douyu.com/4",
                "https://live.douyin.com/1",
                "https://www.huya.com/2",
                "https://live.douyin.com/3",
            ),
            groups[0].urls
        )
    }

    @Test
    fun `分组时置顶组在最前 平台组按首次出现序`() {
        val groups = groupMonitorUrls(
            urls, pinned = setOf("https://www.douyu.com/4"), groupByPlatform = true,
            platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
        )
        assertEquals(listOf("置顶", "抖音", "虎牙"), groups.map { it.label })
        assertEquals(listOf("https://www.douyu.com/4"), groups[0].urls)
        assertEquals(
            listOf("https://live.douyin.com/1", "https://live.douyin.com/3"),
            groups[1].urls
        )
        assertEquals(listOf("https://www.huya.com/2"), groups[2].urls)
    }

    @Test
    fun `分组且无置顶时不产生置顶组`() {
        val groups = groupMonitorUrls(
            urls, pinned = emptySet(), groupByPlatform = true,
            platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
        )
        assertEquals(listOf("抖音", "虎牙", "斗鱼"), groups.map { it.label })
    }

    @Test
    fun `空列表与置顶不存在于列表中均安全`() {
        assertEquals(
            listOf(MonitorGroup(null, emptyList())),
            groupMonitorUrls(
                emptyList(), pinned = setOf("x"), groupByPlatform = false,
                platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
            )
        )
        // 置顶了不在列表里的 URL：不影响分组结果
        val groups = groupMonitorUrls(
            urls, pinned = setOf("https://ghost.example.com"), groupByPlatform = true,
            platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
        )
        assertEquals(listOf("抖音", "虎牙", "斗鱼"), groups.map { it.label })
    }

    @Test
    fun `不分组且无置顶时保持原序单组`() {
        val groups = groupMonitorUrls(
            urls, pinned = emptySet(), groupByPlatform = false,
            platformKeyOf = ::keyOf, platformLabelOf = ::labelOf, pinnedLabel = "置顶",
        )
        assertEquals(1, groups.size)
        assertEquals(urls, groups[0].urls)
    }
}
