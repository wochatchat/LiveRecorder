package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3-4 R2：前台平台探测纯函数测试。 */
class FgPlatformTest {

    @Test
    fun `主流平台包名映射正确`() {
        assertEquals("douyin", FgPlatform.platformForPackage("com.ss.android.ugc.aweme"))
        assertEquals("kuaishou", FgPlatform.platformForPackage("com.smile.gifmaker"))
        assertEquals("xiaohongshu", FgPlatform.platformForPackage("com.xingin.xhs"))
        assertEquals("bilibili", FgPlatform.platformForPackage("tv.danmaku.bili"))
        assertEquals("huya", FgPlatform.platformForPackage("com.duowan.kiwi"))
        assertEquals("douyu", FgPlatform.platformForPackage("air.tv.douyu.android"))
    }

    @Test
    fun `非支持平台返回空串`() {
        assertEquals("", FgPlatform.platformForPackage("com.android.launcher"))
        assertEquals("", FgPlatform.platformForPackage("com.tencent.mm"))
    }

    @Test
    fun `空与空白包名安全返回空串`() {
        assertEquals("", FgPlatform.platformForPackage(""))
        assertEquals("", FgPlatform.platformForPackage("   "))
    }

    @Test
    fun `cookie 状态归并三态`() {
        // 未配置 → none（健康度无论是什么）
        assertEquals("none", FgPlatform.cookieStatus(configured = false, healthStatus = "ok"))
        assertEquals("none", FgPlatform.cookieStatus(configured = false, healthStatus = "expired"))
        // 已配置 + 未标记失效 → ok
        assertEquals("ok", FgPlatform.cookieStatus(configured = true, healthStatus = ""))
        assertEquals("ok", FgPlatform.cookieStatus(configured = true, healthStatus = "ok"))
        // 已配置 + 已标记失效 → expired
        assertEquals("expired", FgPlatform.cookieStatus(configured = true, healthStatus = "expired"))
    }

    @Test
    fun `支持平台键均在包名映射值域内`() {
        val values = PLATFORM_PACKAGES.values.toSet()
        assertTrue(values.containsAll(listOf("douyin", "kuaishou", "xiaohongshu", "bilibili", "huya", "douyu")))
    }
}
