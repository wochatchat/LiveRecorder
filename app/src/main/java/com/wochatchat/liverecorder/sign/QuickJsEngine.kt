/*
 * QuickJsEngine — Phase 3b JNI wrapper.
 *
 * 对齐 PyExecJS 语义：
 *   eval(String jsCode): String  →  JS 求值结果字符串；出错抛 IllegalStateException
 *
 * 生产路径：System.loadLibrary("quickjs") 加载 libquickjs.so（含 quickjs-core + jni-wrapper）
 *           → native 方法 Java_com_wochatchat_liverecorder_sign_QuickJsEngine_eval
 * 测试路径（见 RhinoJsEngineTest）：testImplementation org.mozilla:rhino，
 *           RhinoJsEngine 行为与 QuickJsEngine 一致，单测不依赖 .so
 *
 * 线程模型：JS_Eval 非线程安全；当前简化为单例 JSContext。
 */
package com.wochatchat.liverecorder.sign

/**
 * 最小 JNI 封装，对齐 execjs.exec_(js_code) 语义。
 * 验收标准：JVM 单测 RhinoJsEngineTest.eval("1+1") == "2"
 */
object QuickJsEngine {
    init {
        System.loadLibrary("quickjs")
    }

    /**
     * 在 QuickJS 上下文中执行 jsCode，返回 JSON 序列化后的结果字符串。
     *
     * @throws IllegalStateException 当 JS 执行抛出异常时（异常消息即 JS 异常内容）
     */
    @Throws(IllegalStateException::class)
    external fun eval(jsCode: String): String
}