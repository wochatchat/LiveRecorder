package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 4b：AuthStore 纯函数（cookie 行序列化 + 平台表完整性）。 */
class AuthStoreTest {

    @Test
    fun `parse splits on first equals`() {
        val map = AuthStore.parseCookieLines("douyin=ttwid=1|ab; ms=x=y\nhuya=a=b")
        assertEquals("ttwid=1|ab; ms=x=y", map["douyin"])
        assertEquals("a=b", map["huya"])
    }

    @Test
    fun `roundtrip keeps cookie values containing equals`() {
        val cookies = mapOf("douyin" to "a=b=c; d=e", "bilibili" to "SESSDATA=abc%2Fdef")
        val parsed = AuthStore.parseCookieLines(AuthStore.formatCookieLines(cookies))
        assertEquals(cookies, parsed)
    }

    @Test
    fun `parse skips blank and malformed lines`() {
        val map = AuthStore.parseCookieLines("\n=empty\nbadline\ndouyin= ok \n")
        assertEquals(1, map.size)
        assertEquals("ok", map["douyin"])
    }

    @Test
    fun `empty input parses to empty map`() {
        assertTrue(AuthStore.parseCookieLines("").isEmpty())
        assertEquals("", AuthStore.formatCookieLines(emptyMap()))
    }

    @Test
    fun `format preserves insertion order`() {
        val text = AuthStore.formatCookieLines(linkedMapOf("huya" to "a", "douyin" to "b"))
        assertEquals("huya=a\ndouyin=b", text)
    }

    @Test
    fun `platform tables cover upstream config keys`() {
        // 上游 config.ini [Cookie] 段 50 个平台 + [账号密码] 段 4 个登录平台
        assertEquals(50, AuthStore.COOKIE_PLATFORMS.size)
        assertEquals(listOf("sooplive", "flextv", "popkontv", "twitcasting"), AuthStore.LOGIN_PLATFORMS.map { it.key })
        // 登录平台也出现在 cookie 表中（登录得到的 cookie 存回同表）
        assertTrue(AuthStore.COOKIE_PLATFORMS.any { it.key == "sooplive" })
        assertEquals("抖音", AuthStore.labelOf("douyin"))
        assertEquals("unknown-key", AuthStore.labelOf("unknown-key"))
    }
}
