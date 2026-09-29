/*
 * QuickJsEngine 单测（Rhino 版）— Phase 3b。
 *
 * 测试 RhinoJsEngine（纯 JVM），行为与生产 JNI QuickJsEngine 接口完全一致。
 * 验收标准：eval("1+1") == "2"
 *
 * JVM 单测不依赖 libquickjs.so；JNI 路径由 NDK 构建验收（compile-check CI）。
 */
package com.wochatchat.liverecorder.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RhinoJsEngineTest {

    private fun e(js: String): String = RhinoJsEngine.eval(js)

    @Test
    fun eval_number_add() {
        assertEquals("2", e("1 + 1"))
    }

    @Test
    fun eval_string_concat() {
        assertEquals("hello world", e("\"hello \" + \"world\""))
    }

    @Test
    fun eval_object_literal() {
        // 顶层 {a:1} 会被解析为块语句；加括号才是对象字面量
        assertEquals("[object Object]", e("({a:1})"))
        assertEquals("1", e("{a:1}"))
    }

    @Test
    fun eval_array() {
        assertEquals("1,2,3", e("[1, 2, 3]"))
    }

    @Test
    fun eval_function_call() {
        assertEquals("6", e("Math.max(1, 6, 3)"))
    }

    @Test
    fun eval_boolean() {
        assertEquals("true", e("true"))
        assertEquals("false", e("false"))
    }

    @Test
    fun eval_undefined() {
        assertEquals("undefined", e("undefined"))
    }

    @Test
    fun eval_null() {
        assertEquals("null", e("null"))
    }

    @Test
    fun eval_syntax_error() {
        // Rhino 语法错误消息不含 "SyntaxError" 字样（如 "missing } in compound statement"）
        val ex = assertThrows(IllegalStateException::class.java) { e("} invalid {") }
        assertEquals(true, !ex.message.isNullOrBlank())
    }

    @Test
    fun eval_reference_error() {
        val ex = assertThrows(IllegalStateException::class.java) {
            e("nonexistent_var_12345")
        }
        assertEquals(true,
            ex.message?.contains("ReferenceError") == true ||
            ex.message?.contains("not") == true)
    }

    @Test
    fun eval_arithmetic() {
        assertEquals("10", e("2 * 5"))
        assertEquals("2", e("10 / 5"))
        assertEquals("1", e("10 % 3"))
    }

    @Test
    fun eval_template_literal() {
        // Rhino 支持模板字面量
        assertEquals("value is 42", e("`value is \${42}`"))
    }

    @Test
    fun eval_json_stringify() {
        assertEquals("{\"x\":1}", e("JSON.stringify({x:1})"))
    }

    @Test
    fun eval_longer_expression() {
        // 斗鱼签名场景近似
        assertEquals("abcdef", e("\"a\"+\"b\"+\"c\"+\"d\"+\"e\"+\"f\""))
    }

    @Test
    fun eval_nan() {
        assertEquals("NaN", e("0/0"))
    }

    @Test
    fun eval_infinity() {
        assertEquals("Infinity", e("1/0"))
    }
}