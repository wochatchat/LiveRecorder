package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** V3-5 R1：网页登录映射表与 Cookie 头解析纯逻辑单测。 */
class WebLoginTest {

    @Test
    fun `六大主平台支持网页登录`() {
        listOf("douyin", "kuaishou", "bilibili", "huya", "douyu", "xhs").forEach {
            assertEquals("https", webLoginUrlFor(it)?.substring(0, 5))
        }
    }

    @Test
    fun `未收录平台返回 null 回落手动粘贴`() {
        assertNull(webLoginUrlFor("unknown_platform"))
        assertNull(webLoginUrlFor(""))
    }

    @Test
    fun `cookie 头解析为键值对`() {
        assertEquals(
            mapOf("a" to "1", "b" to "2"),
            parseCookiePairs("a=1; b=2")
        )
    }

    @Test
    fun `空格与空残片容忍`() {
        assertEquals(
            mapOf("k" to "v with space"),
            parseCookiePairs("  k = v with space ; ; ;; ")
        )
    }

    @Test
    fun `无等号或空键的残片被忽略`() {
        assertEquals(emptyMap<String, String>(), parseCookiePairs("nonsense; =x; ;"))
    }

    @Test
    fun `同名键后者覆盖`() {
        assertEquals(mapOf("k" to "2"), parseCookiePairs("k=1; k=2"))
    }
}
