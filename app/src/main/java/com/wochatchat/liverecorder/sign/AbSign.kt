package com.wochatchat.liverecorder.sign

/**
 * 抖音 a_bogus 签名（Phase 1 子任务 1c），对齐上游 ab_sign.py 的
 * generate_random_str + generate_rc4_bb_str + result_encrypt("s4") + "="。
 *
 * 语义说明：上游全部按"字符码点"处理（ord/chr），实际值域 0-255（Latin-1），
 * Kotlin 侧用 Char.code / Char(code) 对应，不做 UTF-8 编解码。
 * 时间戳可注入（timeMs）以便固定输入对照测试。
 */
object AbSign {

    // ---------- RC4（上游 rc4_encrypt） ----------

    fun rc4(plaintext: String, key: String): String {
        val s = MutableList(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.length].code) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        var i = 0
        j = 0
        val out = StringBuilder(plaintext.length)
        for (ch in plaintext) {
            i = (i + 1) % 256
            j = (j + s[i]) % 256
            val t = s[i]; s[i] = s[j]; s[j] = t
            out.append((s[(s[i] + s[j]) % 256] xor ch.code).toChar())
        }
        return out.toString()
    }

    // ---------- 魔改 base64（上游 result_encrypt） ----------

    private val TABLES = mapOf(
        "s0" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
        "s1" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25+WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s2" to "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe=",
        "s3" to "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe",
        "s4" to "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe"
    )

    private val MASKS = intArrayOf(16515072, 258048, 4032, 63)
    private val SHIFTS = intArrayOf(18, 12, 6, 0)

    /** 上游 get_long_int：3 个字符码拼成 24 位，越界补 0。 */
    private fun getLongInt(round: Int, s: String): Long {
        val base = round * 3
        val c1 = if (base < s.length) s[base].code else 0
        val c2 = if (base + 1 < s.length) s[base + 1].code else 0
        val c3 = if (base + 2 < s.length) s[base + 2].code else 0
        return (c1.toLong() shl 16) or (c2.toLong() shl 8) or c3.toLong()
    }

    internal fun resultEncrypt(longStr: String, num: String): String {
        val table = TABLES.getValue(num)
        val totalChars = (longStr.length * 4 + 2) / 3 // == ceil(len/3*4)
        val out = StringBuilder(totalChars)
        var round = 0
        var longInt = getLongInt(round, longStr)
        for (i in 0 until totalChars) {
            if (i / 4 != round) {
                round++
                longInt = getLongInt(round, longStr)
            }
            val index = i % 4
            val charIndex = (longInt.toInt() and MASKS[index]) ushr SHIFTS[index]
            out.append(table[charIndex])
        }
        return out.toString()
    }

    // ---------- 随机前缀（上游 generate_random_str，固定伪随机值） ----------

    private fun generRandom(randomNum: Int, option: List<Int>): List<Int> {
        val b1 = randomNum and 255
        val b2 = (randomNum shr 8) and 255
        return listOf(
            (b1 and 170) or (option[0] and 85),
            (b1 and 85) or (option[0] and 170),
            (b2 and 170) or (option[1] and 85),
            (b2 and 85) or (option[1] and 170)
        )
    }

    internal fun generateRandomStr(): String {
        // 与上游一致：固定伪随机值 int(0.123456789*10000)=1234 等，非真随机
        val bytes = generRandom(1234, listOf(3, 45)) +
            generRandom(9876, listOf(1, 0)) +
            generRandom(5555, listOf(1, 5))
        return buildString { bytes.forEach { append(it.toChar()) } }
    }

    // ---------- 主体（上游 generate_rc4_bb_str + ab_sign） ----------

    /** 默认窗口环境串（上游 ab_sign 硬编码；后续做成可编辑常量，见 docs/04-risks.md）。 */
    const val DEFAULT_WINDOW_ENV = "1920|1080|1920|1040|0|30|0|0|1872|92|1920|1040|1857|92|1|24|Win32"

    private fun splitToBytes(num: Long): List<Int> = listOf(
        ((num shr 24) and 255).toInt(),
        ((num shr 16) and 255).toInt(),
        ((num shr 8) and 255).toInt(),
        (num and 255).toInt()
    )

    fun generateRc4BbStr(
        urlSearchParams: String,
        userAgent: String,
        windowEnvStr: String = DEFAULT_WINDOW_ENV,
        suffix: String = "cus",
        arguments: List<Int> = listOf(0, 1, 14),
        timeMs: Long = System.currentTimeMillis()
    ): String {
        val startTime = timeMs
        val querySum = Sm3.digest(Sm3.digest(urlSearchParams + suffix))
        val cus = Sm3.digest(Sm3.digest(suffix))
        val uaKey = String(charArrayOf(0.toChar(), 1.toChar(), 14.toChar()))
        val uaSum = Sm3.digest(resultEncrypt(rc4(userAgent, uaKey), "s3"))

        val endTime = startTime + 100

        // b 字典仅保留上游实际进入 bb/校验和的键；常量 aid=6383、pageId=110624
        val b = HashMap<Int, Int>()
        b[8] = 3
        b[10] = endTime.toInt()
        b[16] = startTime.toInt()
        b[18] = 44

        val startBytes = splitToBytes(startTime)
        b[20] = startBytes[0]; b[21] = startBytes[1]
        b[22] = startBytes[2]; b[23] = startBytes[3]
        b[24] = ((startTime / 65536 / 65536) and 255).toInt()
        b[25] = ((startTime / 65536 / 65536 / 256) and 255).toInt()

        val arg0 = splitToBytes(arguments[0].toLong())
        b[26] = arg0[0]; b[27] = arg0[1]; b[28] = arg0[2]; b[29] = arg0[3]
        b[30] = (arguments[1] / 256) and 255
        b[31] = (arguments[1] % 256) and 255
        val arg1 = splitToBytes(arguments[1].toLong())
        b[32] = arg1[0]; b[33] = arg1[1]
        val arg2 = splitToBytes(arguments[2].toLong())
        b[34] = arg2[0]; b[35] = arg2[1]; b[36] = arg2[2]; b[37] = arg2[3]

        b[38] = querySum[21].toInt() and 255
        b[39] = querySum[22].toInt() and 255
        b[40] = cus[21].toInt() and 255
        b[41] = cus[22].toInt() and 255
        b[42] = uaSum[23].toInt() and 255
        b[43] = uaSum[24].toInt() and 255

        val endBytes = splitToBytes(endTime)
        b[44] = endBytes[0]; b[45] = endBytes[1]
        b[46] = endBytes[2]; b[47] = endBytes[3]
        b[48] = b[8]!!
        b[49] = ((endTime / 65536 / 65536) and 255).toInt()
        b[50] = ((endTime / 65536 / 65536 / 256) and 255).toInt()

        val pageId = 110624
        val aid = 6383
        b[51] = pageId
        val pageIdBytes = splitToBytes(pageId.toLong())
        b[52] = pageIdBytes[0]; b[53] = pageIdBytes[1]
        b[54] = pageIdBytes[2]; b[55] = pageIdBytes[3]
        b[56] = aid
        b[57] = aid and 255
        b[58] = (aid shr 8) and 255
        b[59] = (aid shr 16) and 255
        b[60] = (aid shr 24) and 255

        val windowEnvList = windowEnvStr.map { it.code }
        b[64] = windowEnvList.size
        b[65] = b[64]!! and 255
        b[66] = (b[64]!! shr 8) and 255
        b[69] = 0; b[70] = 0; b[71] = 0

        b[72] = b[18]!! xor b[20]!! xor b[26]!! xor b[30]!! xor b[38]!! xor b[40]!! xor b[42]!! xor
            b[21]!! xor b[27]!! xor b[31]!! xor b[35]!! xor b[39]!! xor b[41]!! xor b[43]!! xor
            b[22]!! xor b[28]!! xor b[32]!! xor b[36]!! xor b[23]!! xor b[29]!! xor
            b[33]!! xor b[37]!! xor b[44]!! xor b[45]!! xor b[46]!! xor b[47]!! xor
            b[48]!! xor b[49]!! xor b[50]!! xor b[24]!! xor b[25]!! xor
            b[52]!! xor b[53]!! xor b[54]!! xor b[55]!! xor b[57]!! xor b[58]!! xor b[59]!! xor b[60]!! xor
            b[65]!! xor b[66]!! xor b[70]!! xor b[71]!!

        val bb = mutableListOf(
            b[18]!!, b[20]!!, b[52]!!, b[26]!!, b[30]!!, b[34]!!, b[58]!!, b[38]!!, b[40]!!, b[53]!!, b[42]!!, b[21]!!,
            b[27]!!, b[54]!!, b[55]!!, b[31]!!, b[35]!!, b[57]!!, b[39]!!, b[41]!!, b[43]!!, b[22]!!, b[28]!!, b[32]!!,
            b[60]!!, b[36]!!, b[23]!!, b[29]!!, b[33]!!, b[37]!!, b[44]!!, b[45]!!, b[59]!!, b[46]!!, b[47]!!, b[48]!!,
            b[49]!!, b[50]!!, b[24]!!, b[25]!!, b[65]!!, b[66]!!, b[70]!!, b[71]!!
        )
        bb.addAll(windowEnvList)
        bb.add(b[72]!!)

        val bbStr = buildString { bb.forEach { append(it.toChar()) } }
        return rc4(bbStr, "y")
    }

    /** 上游 ab_sign：完整 a_bogus 输出。 */
    fun abSign(
        urlSearchParams: String,
        userAgent: String,
        timeMs: Long = System.currentTimeMillis()
    ): String =
        resultEncrypt(
            generateRandomStr() + generateRc4BbStr(urlSearchParams, userAgent, timeMs = timeMs),
            "s4"
        ) + "="
}
