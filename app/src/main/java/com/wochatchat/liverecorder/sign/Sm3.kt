package com.wochatchat.liverecorder.sign

/**
 * SM3 国密哈希（GB/T 32905-2016），对应上游 ab_sign.py 的 SM3 类。
 * 纯 Kotlin 重写（无 C 依赖，量级毫秒级，见 docs/03-c-components.md 3.3），
 * 供抖音 a_bogus 签名（Phase 1 子任务 1c）使用。
 */
object Sm3 {

    /** 一次性摘要，输出 32 字节。 */
    fun digest(data: ByteArray): ByteArray {
        val reg = IV.copyOf()
        val padded = pad(data)
        var off = 0
        while (off < padded.size) {
            compress(reg, padded, off)
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = reg[i]
            out[4 * i] = (v ushr 24).toByte()
            out[4 * i + 1] = (v ushr 16).toByte()
            out[4 * i + 2] = (v ushr 8).toByte()
            out[4 * i + 3] = v.toByte()
        }
        return out
    }

    fun digest(data: String): ByteArray = digest(data.toByteArray(Charsets.UTF_8))

    /** 十六进制输出（对齐上游 SM3.sum(output_format='hex') 语义）。 */
    fun digestHex(data: String): String =
        digest(data).joinToString("") { "%02x".format(it) }

    /** 标准填充：0x80 + 0 填充至 ≡56 (mod 64) + 8 字节大端比特长度。 */
    private fun pad(data: ByteArray): ByteArray {
        val bitLength = data.size.toLong() * 8L
        val paddedLen = ((data.size + 9 + 63) / 64) * 64
        val out = ByteArray(paddedLen)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            out[paddedLen - 8 + i] = ((bitLength ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        return out
    }

    /** 压缩单个 64 字节块（消息扩展 W[0..67] + W'[0..63] + 64 轮迭代）。 */
    private fun compress(reg: IntArray, block: ByteArray, off: Int) {
        val w = IntArray(132)
        for (t in 0 until 16) {
            w[t] = ((block[off + 4 * t].toInt() and 0xFF) shl 24) or
                ((block[off + 4 * t + 1].toInt() and 0xFF) shl 16) or
                ((block[off + 4 * t + 2].toInt() and 0xFF) shl 8) or
                (block[off + 4 * t + 3].toInt() and 0xFF)
        }
        for (j in 16 until 68) {
            var a = w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)
            a = a xor rotl(a, 15) xor rotl(a, 23)
            w[j] = a xor rotl(w[j - 13], 7) xor w[j - 6]
        }
        for (j in 0 until 64) {
            w[j + 68] = w[j] xor w[j + 4]
        }

        var a = reg[0]; var b = reg[1]; var c = reg[2]; var d = reg[3]
        var e = reg[4]; var f = reg[5]; var g = reg[6]; var h = reg[7]

        for (j in 0 until 64) {
            val ss1 = rotl(rotl(a, 12) + e + rotl(tj(j), j), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = ff(j, a, b, c) + d + ss2 + w[j + 68]
            val tt2 = gg(j, e, f, g) + h + ss1 + w[j]

            d = c
            c = rotl(b, 9)
            b = a
            a = tt1
            h = g
            g = rotl(f, 19)
            f = e
            e = tt2 xor rotl(tt2, 9) xor rotl(tt2, 17)
        }

        reg[0] = reg[0] xor a
        reg[1] = reg[1] xor b
        reg[2] = reg[2] xor c
        reg[3] = reg[3] xor d
        reg[4] = reg[4] xor e
        reg[5] = reg[5] xor f
        reg[6] = reg[6] xor g
        reg[7] = reg[7] xor h
    }

    private fun rotl(x: Int, n: Int): Int {
        val s = n % 32
        return (x shl s) or (x ushr (32 - s))
    }

    private fun tj(j: Int): Int = if (j < 16) 0x79CC4519 else 0x7A879D8A

    private fun ff(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x and z) or (y and z)

    private fun gg(j: Int, x: Int, y: Int, z: Int): Int =
        if (j < 16) x xor y xor z else (x and y) or (x.inv() and z)

    private val IV = intArrayOf(
        0x7380166F.toInt(), 0x4914B2B9.toInt(), 0x172442D7.toInt(), 0xDA8A0600.toInt(),
        0xA96F30BC.toInt(), 0x163138AA.toInt(), 0xE38DEE4D.toInt(), 0xB0FB0E4E.toInt(),
    )
}
