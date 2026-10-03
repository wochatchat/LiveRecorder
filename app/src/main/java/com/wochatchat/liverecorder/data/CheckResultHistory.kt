/*
 * CheckResultHistory — Phase 5-5.1：平台健康仪表盘数据层。
 *
 * MonitorLoop 每轮对每条 URL 的开播检查结果（成功/异常）按平台归集，
 * 环形缓冲上限 [MAX_PER_PLATFORM] 条/平台、只保留 [WINDOW_MS]（7 天）窗口内
 * 的记录；设置页「平台状态」页按成功率渲染绿/黄/红三色仪表。
 *
 * 追加/裁剪/统计均为纯函数，便于单元测试（不依赖 Android 环境）；
 * DataStore 持久化格式为紧凑 JSON：{"douyin":[[ts,1],[ts,0]], ...}。
 */
package com.wochatchat.liverecorder.data

import org.json.JSONObject

/** 单条平台检查结果：时间戳（毫秒）+ 是否成功（检查未抛异常即成功，与开播/未开播无关）。 */
data class CheckResultEntry(val ts: Long, val ok: Boolean)

/** 平台健康统计：窗口内检查总次数与成功次数。 */
data class PlatformHealthStats(val total: Int, val ok: Int) {
    /** 成功率 0.0~1.0；无数据时为 0。 */
    val successRate: Double get() = if (total == 0) 0.0 else ok.toDouble() / total
}

object CheckResultHistory {

    /** 每平台环形缓冲上限（规划文档 5.1：100 条/平台）。 */
    const val MAX_PER_PLATFORM = 100

    /** 统计窗口：近 7 天。 */
    const val WINDOW_MS: Long = 7L * 24 * 3600 * 1000

    /**
     * 追加一条检查结果并裁剪：先按时间窗口剪掉过期条目，再截断到环形缓冲上限。
     * 纯函数，返回新 map（不改传入对象）。
     */
    fun append(
        history: Map<String, List<CheckResultEntry>>,
        platform: String,
        ts: Long,
        ok: Boolean,
    ): Map<String, List<CheckResultEntry>> {
        val list = (history[platform] ?: emptyList()) + CheckResultEntry(ts, ok)
        val pruned = list.filter { it.ts >= ts - WINDOW_MS }
        val trimmed = if (pruned.size > MAX_PER_PLATFORM) pruned.takeLast(MAX_PER_PLATFORM) else pruned
        return history + (platform to trimmed)
    }

    /** 计算 [entries] 在 [nowMs] 起近 7 天窗口内的成功率统计。 */
    fun stats(entries: List<CheckResultEntry>, nowMs: Long): PlatformHealthStats {
        val inWindow = entries.filter { it.ts >= nowMs - WINDOW_MS }
        return PlatformHealthStats(inWindow.size, inWindow.count { it.ok })
    }

    /** 持久化格式（紧凑数组，省 DataStore 空间）：{"douyin":[[ts,1],[ts,0]], ...}。 */
    fun toJson(history: Map<String, List<CheckResultEntry>>): String {
        val obj = org.json.JSONObject()
        history.forEach { (platform, entries) ->
            if (entries.isEmpty()) return@forEach
            val arr = org.json.JSONArray()
            entries.forEach { entry ->
                arr.put(org.json.JSONArray().put(entry.ts).put(if (entry.ok) 1 else 0))
            }
            obj.put(platform, arr)
        }
        return obj.toString()
    }

    /** 反序列化；格式异常时返回空 map（不抛出，损坏即清零重来）。 */
    fun fromJson(json: String): Map<String, List<CheckResultEntry>> {
        return try {
            val obj = org.json.JSONObject(json)
            val result = mutableMapOf<String, List<CheckResultEntry>>()
            obj.keys().forEach { platform ->
                val arr = obj.getJSONArray(platform)
                val list = ArrayList<CheckResultEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val pair = arr.getJSONArray(i)
                    list.add(CheckResultEntry(pair.getLong(0), pair.getInt(1) != 0))
                }
                result[platform] = list
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
