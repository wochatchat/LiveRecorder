/*
 * AccountsTest — Phase 11-11.2：多账号纯函数（序列化 / 默认合并 / cookie 解析 /
 * 平台键归一化）。
 */
package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {

    private val acc1 = Account("a1", "小号", "ck1")
    private val acc2 = Account("a2", "备用", "ck2")

    // ---- toJson / fromJson ----

    @Test
    fun `json 往返保序保值`() {
        val map = mapOf("douyin" to listOf(acc1, acc2), "huya" to listOf(acc2))
        val round = Accounts.fromJson(Accounts.toJson(map))
        assertEquals(map, round)
    }

    @Test
    fun `损坏 json 返回空 map`() {
        assertTrue(Accounts.fromJson("not json").isEmpty())
        assertTrue(Accounts.fromJson("{}").isEmpty())
        assertTrue(Accounts.fromJson("{\"douyin\":123}").isEmpty())
    }

    @Test
    fun `缺 id 的条目被丢弃`() {
        val json = "{\"douyin\":[{\"nickname\":\"x\",\"cookie\":\"c\"}]}"
        assertTrue(Accounts.fromJson(json).isEmpty())
    }

    @Test
    fun `空列表不序列化`() {
        assertEquals("{}", Accounts.toJson(mapOf("douyin" to emptyList())))
    }

    // ---- withDefault（额外账号 + 既有单 cookie 合并） ----

    @Test
    fun `legacy cookie 变默认账号排首位`() {
        val merged = Accounts.withDefault(
            extra = mapOf("douyin" to listOf(acc1)),
            legacy = mapOf("douyin" to "legacy-ck", "huya" to "hy"),
        )
        assertEquals(Accounts.DEFAULT_ID, merged["douyin"]?.first()?.id)
        assertEquals("legacy-ck", merged["douyin"]?.first()?.cookie)
        assertEquals(listOf(acc1), merged["douyin"]?.drop(1))
        // 仅 legacy 的平台也有默认账号
        assertEquals(1, merged["huya"]?.size)
    }

    @Test
    fun `仅额外账号无 legacy 时正常列出`() {
        val merged = Accounts.withDefault(mapOf("douyin" to listOf(acc1)), emptyMap())
        assertEquals(listOf(acc1), merged["douyin"])
    }

    @Test
    fun `空白 legacy cookie 不生成默认账号`() {
        val merged = Accounts.withDefault(mapOf("douyin" to listOf(acc1)), mapOf("douyin" to "  "))
        assertEquals(listOf(acc1), merged["douyin"])
    }

    // ---- resolveCookie ----

    @Test
    fun `命中额外账号返回其 cookie`() {
        val extra = mapOf("douyin" to listOf(acc1, acc2))
        val legacy = mapOf("douyin" to "legacy-ck")
        assertEquals("ck2", Accounts.resolveCookie("douyin", "a2", extra, legacy))
    }

    @Test
    fun `默认绑定或空绑定回落 legacy`() {
        val extra = mapOf("douyin" to listOf(acc1))
        val legacy = mapOf("douyin" to "legacy-ck")
        assertEquals("legacy-ck", Accounts.resolveCookie("douyin", "", extra, legacy))
        assertEquals("legacy-ck", Accounts.resolveCookie("douyin", Accounts.DEFAULT_ID, extra, legacy))
    }

    @Test
    fun `绑定的账号已删回落 legacy`() {
        val extra = mapOf("douyin" to listOf(acc1))
        val legacy = mapOf("douyin" to "legacy-ck")
        assertEquals("legacy-ck", Accounts.resolveCookie("douyin", "gone", extra, legacy))
    }

    @Test
    fun `无任何配置返回 null`() {
        assertNull(Accounts.resolveCookie("douyin", "a1", emptyMap(), emptyMap()))
        assertNull(Accounts.resolveCookie("douyin", "", emptyMap(), mapOf("douyin" to "")))
    }

    // ---- cookieKeyForPlatform（徽标键 → AuthStore/Router cookie 键） ----

    @Test
    fun `差异平台键归一化`() {
        assertEquals("xhs", Accounts.cookieKeyForPlatform("xiaohongshu"))
        assertEquals("maoer", Accounts.cookieKeyForPlatform("maoerfm"))
        assertEquals("seventeen", Accounts.cookieKeyForPlatform("live17"))
        assertEquals("sooplive", Accounts.cookieKeyForPlatform("soop"))
        assertEquals("yingke", Accounts.cookieKeyForPlatform("inke"))
        assertEquals("liujian", Accounts.cookieKeyForPlatform("liujianfang"))
    }

    @Test
    fun `相同平台键原样返回`() {
        assertEquals("douyin", Accounts.cookieKeyForPlatform("douyin"))
        assertEquals("huya", Accounts.cookieKeyForPlatform("huya"))
        assertEquals("kuaishou", Accounts.cookieKeyForPlatform("kuaishou"))
    }

    // ---- newId ----

    @Test
    fun `newId 非空且唯一`() {
        val a = Accounts.newId(1000L)
        val b = Accounts.newId(1000L)
        assertTrue(a.isNotBlank() && b.isNotBlank())
        assertTrue(a != b)
    }
}
