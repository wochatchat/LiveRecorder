package com.wochatchat.liverecorder.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6e R18：RecordHistoryStore 纯函数单测（JSON 往返 + 500 条上限裁剪）。
 * DataStore 落盘路径属 Android 框架层，由真机验收覆盖。
 */
class RecordHistoryStoreTest {

    private fun entry(
        url: String = "https://live.douyin.com/123",
        platform: String = "抖音直播",
        anchor: String = "测试主播",
        title: String = "今晚打游戏",
        path: String = "/data/downloads/测试主播_20260928.flv",
        endTime: Long = 1000L,
        durationMs: Long = 60_000L,
        bytes: Long = 1024L * 1024 * 100,
        completed: Boolean = true,
    ) = RecordHistoryEntry(
        url = url, platform = platform, anchorName = anchor, title = title,
        savePath = path, endTimeMs = endTime, durationMs = durationMs,
        bytes = bytes, completed = completed,
    )

    @Test
    fun json_roundtrip_keeps_all_fields() {
        val e = entry()
        val back = RecordHistoryEntry.fromJson(e.toJson())
        assertEquals(e, back)
    }

    @Test
    fun json_roundtrip_escapes_special_chars() {
        // 主播名/标题含引号、换行、反斜杠——JSON 转义必须兜底
        val e = entry(anchor = "主\"播\\A", title = "标题，带'\n换行")
        val back = RecordHistoryEntry.fromJson(e.toJson())
        assertEquals(e, back)
    }

    @Test
    fun serialize_deserialize_list_roundtrip() {
        val list = listOf(entry(endTime = 1), entry(endTime = 2, completed = false))
        val back = RecordHistoryStore.deserialize(RecordHistoryStore.serialize(list))
        assertEquals(list, back)
    }

    @Test
    fun deserialize_garbage_returns_empty() {
        assertTrue(RecordHistoryStore.deserialize("").isEmpty())
        assertTrue(RecordHistoryStore.deserialize("not json").isEmpty())
        assertTrue(RecordHistoryStore.deserialize("{\"a\":1}").isEmpty())
    }

    @Test
    fun deserialize_unknown_fields_tolerated() {
        val arr = JSONArray().apply { put(entry().toJson().put("futureField", "x")) }
        val back = RecordHistoryStore.deserialize(arr.toString())
        assertEquals(1, back.size)
        assertEquals("测试主播", back[0].anchorName)
    }

    @Test
    fun cap_sorts_descending_by_endTime() {
        val list = listOf(entry(endTime = 3), entry(endTime = 1), entry(endTime = 2))
        val capped = RecordHistoryStore.cap(list, 10)
        assertEquals(listOf(3L, 2L, 1L), capped.map { it.endTimeMs })
    }

    @Test
    fun cap_keeps_newest_500() {
        val list = (1L..600L).map { entry(endTime = it) }
        val capped = RecordHistoryStore.cap(list, RecordHistoryStore.MAX_ENTRIES)
        assertEquals(500, capped.size)
        assertEquals(600L, capped.first().endTimeMs) // 最新在前
        assertEquals(101L, capped.last().endTimeMs)  // 最旧的 100 条被裁
    }

    @Test
    fun cap_under_limit_unchanged() {
        val list = listOf(entry(endTime = 1), entry(endTime = 2))
        assertEquals(list.size, RecordHistoryStore.cap(list, RecordHistoryStore.MAX_ENTRIES).size)
    }
}
