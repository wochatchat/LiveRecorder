package com.wochatchat.liverecorder.sign

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1c 验收：与沙箱内运行的上游 Python ab_sign.py 固定输入真值逐字节对照。
 * 固定输入与真值见 docs/ab_sign_vectors.json（fixedTimeMs=1725000000000，
 * bb/absign 两向量已用 Python 原版复验）。
 */
class AbSignTest {

    private val query =
        "aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN&web_rid=335354047186&msToken="
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 " +
            "Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"
    private val fixedTimeMs = 1725000000000L

    @Test
    fun rc4_matchesUpstream() {
        // Python: rc4_encrypt('hello world', 'y') → [45,225,200,46,235,199,62,90,155,204,206]
        val got = AbSign.rc4("hello world", "y").map { it.code }
        assertEquals(listOf(45, 225, 200, 46, 235, 199, 62, 90, 155, 204, 206), got)
        // key 含 0x00/0x01/0x0e（ua 加密用的 key）
        assertEquals(
            listOf(185, 139, 141),
            AbSign.rc4("abc", "${0.toChar()}${1.toChar()}${14.toChar()}").map { it.code }
        )
    }

    @Test
    fun magicBase64_matchesUpstream() {
        // Python: result_encrypt('abcdefgh','s4')
        assertEquals("RIsN42Xo49E", AbSign.resultEncrypt("abcdefgh", "s4"))
        assertEquals("RIsN4D", AbSign.resultEncrypt("abcd", "s4"))
        // 输入含 Latin-1 高位字符（rc4 输出场景）
        assertEquals("BAZK", AbSign.resultEncrypt("${45.toChar()}${225.toChar()}${200.toChar()}", "s3"))
    }

    @Test
    fun randomStr_matchesUpstream() {
        // Python generate_random_str 固定伪随机前缀
        assertEquals(
            listOf(131, 82, 5, 44, 129, 20, 34, 4, 163, 17, 5, 21),
            AbSign.generateRandomStr().map { it.code }
        )
    }

    @Test
    fun bbStr_matchesUpstreamFixedTime() {
        val bb = AbSign.generateRc4BbStr(query, ua, timeMs = fixedTimeMs).map { it.code }
        val want = listOf(
            105, 38, 164, 66, 132, 231, 81, 91, 211, 161, 97, 112, 55, 116, 161, 60,
            1, 144, 59, 36, 101, 212, 68, 246, 220, 250, 0, 219, 248, 166, 53, 88,
            81, 28, 231, 48, 141, 147, 129, 91, 250, 48, 209, 145, 120, 169, 56, 131,
            204, 167, 176, 61, 53, 29, 221, 201, 96, 116, 34, 134, 32, 71, 1, 185,
            246, 138, 105, 99, 112, 105, 56, 115, 179, 70, 0, 203, 210, 47, 101, 28,
            19, 242, 247, 200, 105, 88, 222, 130, 160, 77, 211, 86, 179, 187, 141, 77,
            114, 105, 255, 109, 21, 159, 169, 65, 155, 182, 99, 78, 217, 131
        )
        assertEquals(110, bb.size)
        assertEquals(want, bb)
    }

    @Test
    fun abSign_matchesUpstreamFixedTime() {
        assertEquals(
            "E7mhBmg6mEVNgf6X5V5LfY3q6XF3YIhj0HViMD2f/nvw7g39HMYD9exo0XivZ/WjN4/kIeYjy4hbO3xprQAjM36UHWwEUdQ2mgWkKl5Q5I0j53iruyRDntmF4vj3SFlm5XNAEOk0y75rKb70Woqe-vIlO62-zo0/9R8=",
            AbSign.abSign(query, ua, timeMs = fixedTimeMs)
        )
    }
}
