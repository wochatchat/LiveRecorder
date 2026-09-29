package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 4a 代理判定语义（对齐上游 main.py:558-573 + config.ini 默认值）。
 */
class ProxySettingsTest {

    private val addr = "socks5://127.0.0.1:7890"

    @Test
    fun `disabled or blank addr always resolves to null`() {
        val off = ProxySettings(enabled = false, addr = addr)
        val noAddr = ProxySettings(enabled = true, addr = "  ")
        assertNull(off.resolveProxy("https://www.tiktok.com/@user/live"))
        assertNull(noAddr.resolveProxy("https://www.tiktok.com/@x"))
    }

    @Test
    fun `enabled addr without keyword match resolves to null`() {
        val s = ProxySettings(enabled = true, addr = addr)
        // 默认平台列表不含 douyu/douyin
        assertNull(s.resolveProxy("https://www.douyu.com/631134"))
        assertNull(s.resolveProxy("https://live.douyin.com/605556584648"))
    }

    @Test
    fun `url matching default platform keyword uses proxy`() {
        val s = ProxySettings(enabled = true, addr = addr)
        assertEquals(addr, s.resolveProxy("https://www.tiktok.com/@user/live"))
        assertEquals(addr, s.resolveProxy("https://www.twitch.tv/xxx"))
        // "youtu" 前缀关键词命中 youtube
        assertEquals(addr, s.resolveProxy("https://www.youtube.com/watch?v=x"))
    }

    @Test
    fun `custom platforms override default list`() {
        val s = ProxySettings(
            enabled = true, addr = addr,
            platforms = listOf("douyu.com"),
        )
        assertEquals(addr, s.resolveProxy("https://www.douyu.com/631134"))
        assertNull(s.resolveProxy("https://www.tiktok.com/@user"))
    }

    @Test
    fun `default platforms align upstream config ini`() {
        // 上游 config.ini「使用代理录制的平台」默认行
        assertEquals(
            listOf(
                "tiktok", "sooplive", "pandalive", "winktv", "flextv", "popkontv",
                "twitch", "liveme", "showroom", "chzzk", "shopee", "shp", "youtu",
            ),
            ProxySettings.DEFAULT_PLATFORMS,
        )
    }

    @Test
    fun `parsePlatforms tolerates blanks and chinese comma`() {
        val parsed = ProxySettings.parsePlatforms("tiktok， twitch, ,douyu")
        assertEquals(listOf("tiktok", "twitch", "douyu"), parsed)
        // 全空回落默认
        assertEquals(ProxySettings.DEFAULT_PLATFORMS, ProxySettings.parsePlatforms(" , "))
        assertEquals(listOf("a"), ProxySettings.parsePlatforms("a", fallback = listOf("fb")))
    }

    @Test
    fun `platformsCsv roundtrip`() {
        val s = ProxySettings(enabled = true, addr = addr, platforms = listOf("tiktok", " twitch "))
        assertEquals(listOf("tiktok", "twitch"), ProxySettings.parsePlatforms(s.platformsCsv()))
    }
}
