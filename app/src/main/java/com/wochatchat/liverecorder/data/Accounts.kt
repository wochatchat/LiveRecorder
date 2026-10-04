/*
 * Accounts — Phase 11-11.2：多账号管理。
 *
 * 同一平台支持多套 Cookie：原 AuthStore 单 cookie 作为「默认账号」保持不变
 * （旧数据 / ConfigExporter 导出 / 既有录制路径零改动），额外账号以 JSON 存独立
 * DataStore 键。监控条目通过 accountBindings 绑定具体账号 id，未绑定回落默认账号。
 */
package com.wochatchat.liverecorder.data

import org.json.JSONArray
import org.json.JSONObject

/** 平台的一个额外账号（默认账号不在此列，见 [Accounts.DEFAULT_ID]）。 */
data class Account(val id: String, val nickname: String, val cookie: String)

object Accounts {

    /** 默认账号 id（= AuthStore 既有单 cookie），绑定此值或空串均回落默认。 */
    const val DEFAULT_ID = "default"

    /** 默认账号在 UI 中的显示名。 */
    const val DEFAULT_LABEL = "默认"

    /** 生成账号 id（时间戳 36 进制 + 随机尾，同平台内唯一即可）。 */
    fun newId(ts: Long = System.currentTimeMillis()): String =
        "a${ts.toString(36)}${(0..999).random().toString(36)}"

    /**
     * 序列化：{"douyin":[{"id":"a1","nickname":"小号","cookie":"..."}], ...}。
     */
    fun toJson(map: Map<String, List<Account>>): String {
        val obj = JSONObject()
        map.forEach { (platform, list) ->
            if (list.isEmpty()) return@forEach
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().put("id", a.id).put("nickname", a.nickname).put("cookie", a.cookie))
            }
            obj.put(platform, arr)
        }
        return obj.toString()
    }

    /** 反序列化；损坏/空返回 emptyMap。 */
    fun fromJson(json: String): Map<String, List<Account>> = try {
        val obj = JSONObject(json)
        val result = mutableMapOf<String, List<Account>>()
        obj.keys().forEach { platform ->
            val arr = obj.getJSONArray(platform)
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isBlank()) null
                else Account(id, o.optString("nickname"), o.optString("cookie"))
            }
            if (list.isNotEmpty()) result[platform] = list
        }
        result
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * 合并额外账号与既有单 cookie：默认账号（legacy cookie 非空时）排首位，
     * 其后跟额外账号（持久化序）。纯函数，UI 账号列表 / 单测共用。
     */
    fun withDefault(
        extra: Map<String, List<Account>>,
        legacy: Map<String, String>,
    ): Map<String, List<Account>> {
        val result = mutableMapOf<String, List<Account>>()
        (legacy.filterKeys { it.isNotBlank() }.keys + extra.keys).forEach { platform ->
            val list = buildList {
                legacy[platform]?.takeIf { it.isNotBlank() }?.let {
                    add(Account(DEFAULT_ID, DEFAULT_LABEL, it))
                }
                addAll(extra[platform].orEmpty())
            }
            if (list.isNotEmpty()) result[platform] = list
        }
        return result
    }

    /**
     * ui.components platformKeyForUrl 的徽标键 → AuthStore/Router cookie 键归一化
     * （两套键历史差异，仅少数平台不同）。纯函数，录制解析 / UI 账号列表共用。
     */
    fun cookieKeyForPlatform(platform: String): String = when (platform) {
        "xiaohongshu" -> "xhs"
        "maoerfm" -> "maoer"
        "live17" -> "seventeen"
        "soop" -> "sooplive"
        "inke" -> "yingke"
        "liujianfang" -> "liujian"
        else -> platform
    }

    /** V3-8：cookie 键 → UI 徽标键反向映射（健康仪表盘徽标显示用）。 */
    fun uiKeyForCookieKey(cookieKey: String): String = when (cookieKey) {
        "xhs" -> "xiaohongshu"
        "maoer" -> "maoerfm"
        "seventeen" -> "live17"
        "sooplive" -> "soop"
        "yingke" -> "inke"
        "liujian" -> "liujianfang"
        else -> cookieKey
    }

    /**
     * 解析某平台指定账号 id 的 cookie：绑定默认/空/账号已删 → 回落 legacy cookie；
     * 命中额外账号 → 返回其 cookie。纯函数，录制链路 effectiveCookies / 单测共用。
     */
    fun resolveCookie(
        platform: String,
        accountId: String,
        extra: Map<String, List<Account>>,
        legacy: Map<String, String>,
    ): String? {
        if (accountId.isNotBlank() && accountId != DEFAULT_ID) {
            extra[platform]?.firstOrNull { it.id == accountId }?.let { return it.cookie }
        }
        return legacy[platform]?.takeIf { it.isNotBlank() }
    }
}
