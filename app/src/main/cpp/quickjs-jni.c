/*
 * QuickJS JNI bridge — Phase 3b.
 *
 * Exposes a minimal eval() interface matching PyExecJS semantics:
 *   String eval(String jsCode)  →  JS 求值结果字符串（出错抛 IllegalStateException）
 *
 * 实现要点：
 * 1. JS_Eval 返回的 JSValue 必须 JS_FreeValue 释放；JS_ToString/JS_ToCString 同理。
 * 2. JS_EXCEPTION tag 为正数（无引用计数），JS_FreeValue 对其是 no-op，异常路径可安全释放。
 * 3. JS_Eval 非线程安全：单例 JSContext 简化处理，调用方保证串行。
 * 4. JS 抛异常时取 JS_GetException → ToString → FreeCString 全链路释放后再 ThrowNew。
 */
#include <jni.h>
#include <stdio.h>
#include "quickjs/quickjs.h"

static JSRuntime *g_rt = NULL;
static JSContext *g_ctx = NULL;

/* 懒初始化：首次 eval 时创建 Runtime + Context */
static JSContext *get_ctx(void) {
    if (g_ctx == NULL) {
        if (g_rt == NULL) {
            g_rt = JS_NewRuntime();
        }
        g_ctx = JS_NewContext(g_rt);
    }
    return g_ctx;
}

JNIEXPORT jstring JNICALL
Java_com_wochatchat_liverecorder_sign_QuickJsEngine_eval(JNIEnv *env, jobject thiz, jstring js_code) {
    JSContext *ctx = get_ctx();
    if (ctx == NULL) {
        jclass c = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (c != NULL) (*env)->ThrowNew(env, c, "QuickJS: failed to create runtime/context");
        (*env)->DeleteLocalRef(env, c);
        return NULL;
    }

    const char *input = (*env)->GetStringUTFChars(env, js_code, NULL);
    if (input == NULL) {
        return NULL; /* OOME already thrown */
    }

    JSValue eval_result = JS_Eval(ctx, input, strlen(input), "<eval>", JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, js_code, input);

    if (JS_IsException(eval_result)) {
        JSValue exc = JS_GetException(ctx);
        JSValue exc_str = JS_ToString(ctx, exc);
        const char *exc_cstr = JS_IsException(exc_str) ? NULL : JS_ToCString(ctx, exc_str);
        char msg[1024];
        snprintf(msg, sizeof(msg), "QuickJS eval error: %s",
                 exc_cstr ? exc_cstr : "unknown exception");
        if (exc_cstr) JS_FreeCString(ctx, exc_cstr);
        JS_FreeValue(ctx, exc_str);
        JS_FreeValue(ctx, exc);
        /* eval_result 是 JS_EXCEPTION 标记值，FreeValue 为 no-op，调用无害 */
        JS_FreeValue(ctx, eval_result);

        jclass c = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (c != NULL) (*env)->ThrowNew(env, c, msg);
        (*env)->DeleteLocalRef(env, c);
        return NULL;
    }

    JSValue result_str = JS_ToString(ctx, eval_result);
    JS_FreeValue(ctx, eval_result);

    if (JS_IsException(result_str)) {
        JS_FreeValue(ctx, result_str);
        jclass c = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (c != NULL) (*env)->ThrowNew(env, c, "QuickJS: result->string failed");
        (*env)->DeleteLocalRef(env, c);
        return NULL;
    }

    const char *cstr = JS_ToCString(ctx, result_str);
    JS_FreeValue(ctx, result_str);

    if (cstr == NULL) {
        jclass c = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (c != NULL) (*env)->ThrowNew(env, c, "QuickJS: JS_ToCString returned NULL");
        (*env)->DeleteLocalRef(env, c);
        return NULL;
    }

    jstring jresult = (*env)->NewStringUTF(env, cstr);
    JS_FreeCString(ctx, cstr);
    return jresult; /* NULL 时 JNI 已抛 OOME */
}