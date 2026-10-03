package com.wochatchat.liverecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5-5.2：账号健康度纯逻辑单测
 * （状态标记幂等 / 未知状态 / JSON 往返 / 损坏容错）。
 */
class AccountHealthTest {

    @Test
    fun `标记后可读取状态`() {
        var m = AccountHealth.mark(emptyMap(), "douyin", AccountHealth.STATUS_OK, ts = 1000L)
        assertEquals(AccountHealth.STATUS_OK, AccountHealth.statusOf(m, "douyin"))

        m = AccountHealth.mark(m, "douyin", AccountHealth.STATUS_EXPIRED, ts = 2000L)
        assertEquals(AccountHealth.STATUS_EXPIRED, AccountHealth.statusOf(m, "douyin"))
        assertEquals(2000L, m["douyin"]!!.ts)
    }

    @Test
    fun `未标记平台状态为空串`() {
        assertEquals("", AccountHealth.statusOf(emptyMap(), "huya"))
    }

    @Test
    fun `平台之间互不干扰`() {
        var m = AccountHealth.mark(emptyMap(), "douyin", AccountHealth.STATUS_EXPIRED, ts = 1L)
        m = AccountHealth.mark(m, "huya", AccountHealth.STATUS_OK, ts = 2L)
        assertEquals(AccountHealth.STATUS_EXPIRED, AccountHealth.statusOf(m, "douyin"))
        assertEquals(AccountHealth.STATUS_OK, AccountHealth.statusOf(m, "huya"))
    }

    @Test
    fun `JSON往返无损`() {
        var m = AccountHealth.mark(emptyMap(), "douyin", AccountHealth.STATUS_EXPIRED, ts = 1000L)
        m = AccountHealth.mark(m, "huya", AccountHealth.STATUS_OK, ts = 2000L)
        assertEquals(m, AccountHealth.fromJson(AccountHealth.toJson(m)))
    }

    @Test
    fun `损坏JSON返回空map`() {
        assertTrue(AccountHealth.fromJson("bad{").isEmpty())
        assertTrue(AccountHealth.fromJson("").isEmpty())
    }
}
