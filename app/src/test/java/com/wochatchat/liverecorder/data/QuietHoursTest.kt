package com.wochatchat.liverecorder.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6-6.1：通知静音时段判定单测（跨午夜窗口 23:00→07:00）。
 */
class QuietHoursTest {

    private val enabled = AppSettings(quietNotifyEnabled = true)
    private val disabled = AppSettings(quietNotifyEnabled = false)

    @Test
    fun `静音窗口内为真`() {
        // 23:00 起
        org.junit.Assert.assertTrue(AppSettings.isQuietHour(enabled, 23))
        // 午夜与凌晨
        org.junit.Assert.assertTrue(AppSettings.isQuietHour(enabled, 0))
        org.junit.Assert.assertTrue(AppSettings.isQuietHour(enabled, 3))
        org.junit.Assert.assertTrue(AppSettings.isQuietHour(enabled, 6))
    }

    @Test
    fun `静音窗口外为假`() {
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(enabled, 7))
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(enabled, 12))
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(enabled, 22))
    }

    @Test
    fun `开关关闭时永不为静音`() {
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(disabled, 23))
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(disabled, 2))
    }

    @Test
    fun `越界小时钳位不崩溃`() {
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(enabled, -1))
        org.junit.Assert.assertFalse(AppSettings.isQuietHour(enabled, 24))
        org.junit.Assert.assertTrue(AppSettings.isQuietHour(enabled, Int.MAX_VALUE))
    }
}
