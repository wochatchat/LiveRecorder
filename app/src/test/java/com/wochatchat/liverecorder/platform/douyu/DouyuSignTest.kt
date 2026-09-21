/*
 * DouyuSignTest — Phase 3c：斗鱼动态签名 JVM 对照测试。
 *
 * fixture：douyu_room_sign_raw.html（真实 m.douyu.com 房间页 crp-stript 脚本块，最小化包装）
 * 真值来源：douyu_sign_truth.json —— Python 上游流程 + node（PyExecJS 等价执行路径）固定 t10 产出
 *
 * 验收标准（phase-3.md 3c）：签名结果与 Python execjs 输出一致。
 */
package com.wochatchat.liverecorder.platform.douyu

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DouyuSignTest {

    private fun resource(name: String): String {
        val url = javaClass.classLoader!!.getResource(name)
            ?: throw IllegalStateException("test resource not found: $name")
        return File(url.toURI()).readText()
    }

    private fun truth(): JSONObject = JSONObject(resource("douyu_sign_truth.json"))

    private fun resourceHtml(): String = resource("douyu_room_sign_raw.html")

    /** 提取：合成房间页 → 上游正则 + crp-stript 回落 → 块结构与上游一致 */
    @Test
    fun extractSignScript_matchesUpstream() {
        val block = DouyuSign.extractSignScript(resourceHtml())
        assertNotNull(block)
        assertTrue(block!!.contains("function ub98484234"))
        assertTrue(block.startsWith("vdwdae325w_64we"))

        // 无签名脚本的页面 → null
        assertNull(DouyuSign.extractSignScript("<html><body>offline</body></html>"))
    }

    /** eval→strc 替换结果与 Python func_ub9 完全一致 */
    @Test
    fun evalReplacementMatchesUpstream() {
        val truth = truth()
        val block = DouyuSign.extractSignScript(resourceHtml())!!
        // 上游：re.sub(r'eval.*?;}', 'strc;}', result)
        val expected = Regex("""eval.*?;}""").replace(block, "strc;}")
        assertEquals(expected, truth.getString("func_ub9"))
    }

    /** 端到端：固定 t10 下 4 参数与 Python execjs 真值一致 */
    @Test
    fun tokenParamsMatchPythonTruth() {
        val truth = truth()
        val params = DouyuSign.getTokenParams(
            eval = { code -> RhinoJsEngine.eval(code) },
            roomHtml = resourceHtml(),
            rid = truth.getString("rid"),
            did = truth.getString("did"),
            t10 = truth.getString("t10"),
        )
        val expected = truth.getJSONArray("params_list").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        assertArrayEquals(expected.toTypedArray(), params.toTypedArray())
        assertEquals(truth.getString("v"), params[0])
        assertEquals(truth.getString("t10"), params[2])
        assertTrue(params[3].matches(Regex("[0-9a-f]{32}")))
    }

    /** md5 已知向量（含上游 rb 输入：rid+did+t10+v） */
    @Test
    fun md5HexKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DouyuSign.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", DouyuSign.md5Hex("abc"))
        assertEquals(
            "758168bd369b4b06b8de01e976351b0f",
            DouyuSign.md5Hex("631134100000000000000000000000000033061725000000250120260922")
        )
    }

    /** Rhino 与真值 res 一致：ub98484234 输出即 sign 函数源码（含 v= 与 CryptoJS 占位） */
    @Test
    fun ub98484234ResultMatchesUpstreamRes() {
        val truth = truth()
        val block = DouyuSign.extractSignScript(resourceHtml())!!
        val funcUb9 = Regex("""eval.*?;}""").replace(block, "strc;}")
        val res = RhinoJsEngine.eval(funcUb9 + "\n;ub98484234()")
        assertEquals(truth.getString("res"), res)
    }

    /** DEFAULT_DID 对齐上游硬编码 */
    @Test
    fun defaultDidMatchesUpstream() {
        assertEquals("10000000000000000000000000003306", DouyuSign.DEFAULT_DID)
    }
}
