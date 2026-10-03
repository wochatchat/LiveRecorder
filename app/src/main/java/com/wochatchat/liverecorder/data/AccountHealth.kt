/*
 * AccountHealth — Phase 5-5.2：账号健康度。
 *
 * 判定来源（无独立探测请求，复用既有信号）：
 * - 平台已配置 Cookie/账密 + 该平台某监控条目连续检查失败 ≥3 轮（MonitorLoop unhealthy）
 *   → 标记 expired（❌，大概率 Cookie 失效），并发通知提醒续期
 * - 该平台任一检查成功 → 恢复 ok（✅）
 * - 已配置但尚无检查数据 → 未标记（UI 显示 ⏳ pending）
 *
 * 状态转移与 JSON 序列化为纯函数，便于单元测试；
 * DataStore 持久化格式：{"douyin":["expired",ts], ...}。
 */
package com.wochatchat.liverecorder.data

import org.json.JSONObject

/** 单平台账号健康条目：状态（ok/expired）+ 标记时间戳。 */
data class AccountHealthEntry(val status: String, val ts: Long)

object AccountHealth {

    const val STATUS_OK = "ok"
    const val STATUS_EXPIRED = "expired"

    /** 标记平台账号状态（幂等：同状态刷新时间戳）。纯函数。 */
    fun mark(
        map: Map<String, AccountHealthEntry>,
        platform: String,
        status: String,
        ts: Long,
    ): Map<String, AccountHealthEntry> = map + (platform to AccountHealthEntry(status, ts))

    /** 读取平台状态；未标记返回空串（UI 视作 pending）。 */
    fun statusOf(map: Map<String, AccountHealthEntry>, platform: String): String =
        map[platform]?.status.orEmpty()

    /** 序列化：{"douyin":["expired",ts], ...}。 */
    fun toJson(map: Map<String, AccountHealthEntry>): String {
        val obj = JSONObject()
        map.forEach { (platform, entry) -> obj.put(platform, org.json.JSONArray().put(entry.status).put(entry.ts)) }
        return obj.toString()
    }

    /** 反序列化；损坏返回空 map。 */
    fun fromJson(json: String): Map<String, AccountHealthEntry> = try {
        val obj = JSONObject(json)
        val result = mutableMapOf<String, AccountHealthEntry>()
        obj.keys().forEach { platform ->
            val pair = obj.getJSONArray(platform)
            result[platform] = AccountHealthEntry(pair.getString(0), pair.getLong(1))
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }
}
