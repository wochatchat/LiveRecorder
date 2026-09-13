package com.wochatchat.liverecorder.sign

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1 子任务 1b 验收：GB/T 32905-2016 标准测试向量 + 上游 Python 原版对照。
 * 期望值来源：① GB/T 官方向量（abc / 空串 / abcd×16）；
 * ② /tmp/DouyinLiveRecorder/src/ab_sign.py SM3 原版输出（cjk / long1000，2026-09-13 沙箱实测）。
 */
class Sm3Test {

    @Test
    fun `GB-T 32905-2016 vector - abc`() {
        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            Sm3.digestHex("abc"),
        )
    }

    @Test
    fun `GB-T 32905-2016 vector - empty input`() {
        assertEquals(
            "1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b",
            Sm3.digestHex(""),
        )
    }

    @Test
    fun `GB-T 32905-2016 vector - abcd x16 multi-block`() {
        assertEquals(
            "debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732",
            Sm3.digestHex("abcd".repeat(16)),
        )
    }

    @Test
    fun `matches upstream Python SM3 - utf-8 cjk input`() {
        assertEquals(
            "a5165c6fb24a37e3a5bb2f6d0ff4bfcc1e87654e7ca50d7192b676c392a565cb",
            Sm3.digestHex("抖音直播"),
        )
    }

    @Test
    fun `matches upstream Python SM3 - long multi-block input`() {
        assertEquals(
            "f4bedca973227d45c5b822551d2e762d4cfb0e9af70b241452545727b5fb046f",
            Sm3.digestHex("a".repeat(1000)),
        )
    }

    @Test
    fun `digest bytes and digestHex are consistent`() {
        val hex = Sm3.digestHex("abc")
        val bytes = Sm3.digest("abc")
        assertEquals(hex, bytes.joinToString("") { "%02x".format(it) })
        assertEquals(32, bytes.size)
    }
}
