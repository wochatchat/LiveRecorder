/*
 * RhinoQuickJsEngine — Phase 3b 纯 JVM 实现（用于单元测试）。
 *
 * Mozilla Rhino 是纯 Java JS 引擎，行为与 QuickJS 高度兼容。
 * testImplementation org.mozilla:rhino:1.7.15，JVM 单测可跑。
 *
 * 此文件与 QuickJsEngine.kt 接口一致，单测走此文件；生产 JNI 路径见 QuickJsEngine.kt。
 */
package com.wochatchat.liverecorder.sign

import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptableObject

/** 纯 JVM JS eval 实现，供单元测试使用。行为与 QuickJsEngine（JNI）接口完全一致。 */
object RhinoJsEngine {

    private fun createContext(): Context {
        val cx = Context.enter()
        cx.optimizationLevel = -1
        return cx
    }

    @Throws(IllegalStateException::class)
    fun eval(jsCode: String): String {
        val cx = createContext()
        return try {
            val scope = cx.initStandardObjects()
            val result = cx.evaluateString(scope, jsCode, "<eval>", 1, null)
            Context.toString(result)
        } catch (e: RhinoException) {
            throw IllegalStateException(e.message ?: "unknown JS error", e)
        } finally {
            Context.exit()
        }
    }
}