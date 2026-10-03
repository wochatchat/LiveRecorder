package com.wochatchat.liverecorder.ui

import androidx.compose.ui.graphics.Color
import com.wochatchat.liverecorder.ui.theme.STATUS_COLOR_PRESETS
import com.wochatchat.liverecorder.ui.theme.parseStatusColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 9-9.3：状态色字符串解析（持久化 hex → Color）。 */
class StatusColorsTest {

    @Test
    fun `空串返回null走语义默认色`() {
        assertNull(parseStatusColor(""))
        assertNull(parseStatusColor("   "))
    }

    @Test
    fun `非法内容返回null`() {
        assertNull(parseStatusColor("red"))
        assertNull(parseStatusColor("0xZZ112233"))
        assertNull(parseStatusColor("12345"))
        assertNull(parseStatusColor("D32F2"))
    }

    @Test
    fun `带alpha的hex解析`() {
        assertEquals(Color(0xFFD32F2F), parseStatusColor("0xFFD32F2F"))
        assertEquals(Color(0xFFD32F2F), parseStatusColor("FFD32F2F"))
    }

    @Test
    fun `无alpha的6位hex自动补不透明`() {
        assertEquals(Color(0xFF1976D2), parseStatusColor("1976D2"))
        assertEquals(Color(0xFF1976D2), parseStatusColor("#1976D2"))
    }

    @Test
    fun `预设色全部可解析且不透明`() {
        assertTrue(STATUS_COLOR_PRESETS.isNotEmpty())
        STATUS_COLOR_PRESETS.values.forEach { hex ->
            val color = parseStatusColor(hex)
            assertTrue("preset $hex should parse", color != null)
        }
    }

    @Test
    fun `解析带前后空白的hex`() {
        assertEquals(Color(0xFF388E3C), parseStatusColor("  0xFF388E3C  "))
    }
}
