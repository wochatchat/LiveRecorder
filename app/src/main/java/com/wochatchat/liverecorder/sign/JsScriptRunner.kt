/*
 * JsScriptRunner — 8a：上游 execjs.compile(...).call('sign', ...) 的统一桥。
 *
 * 上游 JS（liveme.js / haixiu.js）在 node 下运行：依赖 require(cryptoJSPath)、
 * module.exports、console。QuickJS/Rhino 无 node 环境，用 shim 模拟：
 *   1. 先以 CommonJS 环境 eval crypto-js.min.js → module.exports = CryptoJS
 *   2. require(p) 固定返回快照 __CryptoJS（平台脚本随后会覆写 module.exports，
 *      故必须先快照再定义 require）
 *   3. console 静默垫片（上游脚本有 console.log，QuickJS 无 console）
 *
 * 调用约定（双引擎一致）：
 *   eval 的返回值 = JSON.stringify(目标函数(...))；两引擎对字符串结果都是
 *   原样 ToString 返回（QuickJS JNI 用 JS_ToString，Rhino 用 Context.toString）。
 */
package com.wochatchat.liverecorder.sign

import com.wochatchat.liverecorder.sign.scripts.JsScripts

object JsScriptRunner {

    /**
     * 拼装并执行 JS，返回 [funcExpr] 调用结果的 JSON 字符串。
     *
     * @param eval JS 求值函数（生产 QuickJsEngine::eval，单测 RhinoJsEngine::eval）
     * @param funcExpr 调用表达式，如 `sign(${argJson})`；结果会被 JSON.stringify 包裹
     * @param script 平台签名脚本（如 [JsScripts.HAIXIU_JS]）
     * @param cryptoJs crypto-js 脚本；有依赖时先加载并垫 require（liveme/haixiu 需传）
     */
    fun call(eval: (String) -> String, funcExpr: String, script: String, cryptoJs: String? = null): String {
        val sb = StringBuilder()
        sb.append("var module = { exports: {} };\n")
        sb.append("var exports = module.exports;\n")
        if (cryptoJs != null) {
            sb.append(cryptoJs).append("\n;\n")
            // crypto-js 以 CommonJS 分支导出到 module.exports，先快照再垫 require
            sb.append("var __CryptoJS = module.exports;\n")
            sb.append("var require = function (path) { return __CryptoJS; };\n")
        }
        sb.append("var console = { log: function () {}, error: function () {}, warn: function () {} };\n")
        sb.append(script).append("\n;\n")
        sb.append("JSON.stringify(").append(funcExpr).append(")")
        return eval(sb.toString())
    }
}
