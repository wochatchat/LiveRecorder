/*
 * DouyuSign — Phase 3c：斗鱼动态 JS 签名（ub98484234）。
 *
 * 对照上游 spider.py get_token_js（两段式 execjs 流程）：
 *   1. 房间页提取 vdwdae325w_64we...ub98484234 脚本块，eval...;} → strc;}（防递归 eval）
 *   2. JS 引擎执行 ub98484234() 得 res（内含 v=<digits> 与 sign IIFE 源码）
 *   3. rb = md5(rid + did + t10 + v)；把 res 中 CryptoJS.MD5(cb).toString() 换成 rb 字面量
 *   4. 再次执行 sign(rid, did, t10) 得 "v=..&did=..&tt=..&sign=.."，按 =/& 切出 4 参数
 *
 * JS 引擎注入：eval: (String) -> String（PyExecJS call 语义，返回 ToString 结果）。
 *   生产走 QuickJsEngine::eval（JNI），单测走 RhinoJsEngine::eval（纯 JVM）。
 *
 * 提取兼容性：www.douyu.com 现为 Next.js，签名脚本已不在首屏 HTML；
 * m.douyu.com/{rid} 将其内联在 <script id="crp-stript"> 块中。故先按上游原正则
 * 全文匹配（对旧版 www 页面），失配时回落 crp-stript 块内文再匹配。
 */
package com.wochatchat.liverecorder.platform.douyu

import java.security.MessageDigest

object DouyuSign {

    /** 上游签名流程使用的固定 did（spider.py get_douyu_stream_data 硬编码） */
    const val DEFAULT_DID = "10000000000000000000000000003306"

    /** m.douyu.com 内联签名脚本的标签 id */
    const val CRP_SCRIPT_ID = "crp-stript"

    /** 上游正则：vdwdae 变量声明起，至 ub98484234 函数体后的下一个 function 关键字止 */
    private val RE_SIGN_BLOCK = Regex("""(vdwdae325w_64we[\s\S]*function ub98484234[\s\S]*?)function""")

    /** eval 调用整体替换为 strc（阻断递归 eval，上游 re.sub(r'eval.*?;}', 'strc;}')） */
    private val RE_EVAL_CALL = Regex("""eval.*?;}""")

    private val RE_V = Regex("""v=(\d+)""")
    private val RE_PARAM = Regex("""=(.*?)(?=&|$)""")

    /**
     * 从房间页 HTML 提取签名脚本（上游语义，带回退）。
     * 供 getTokenJs 与单测复用；提取失败返回 null。
     */
    fun extractSignScript(roomHtml: String): String? {
        val fromFull = runCatching { RE_SIGN_BLOCK.find(roomHtml)?.groupValues?.get(1) }.getOrNull()
        if (fromFull != null) return fromFull
        val body = extractCrpScript(roomHtml) ?: return null
        return RE_SIGN_BLOCK.find(body)?.groupValues?.get(1)
    }

    /** 提取 <script id="crp-stript"> ... </script> 内文（m 站路径） */
    fun extractCrpScript(roomHtml: String): String? {
        val openTag = """<script id="$CRP_SCRIPT_ID">"""
        val start = roomHtml.indexOf(openTag)
        if (start < 0) return null
        val bodyStart = start + openTag.length
        val end = roomHtml.indexOf("</script>", bodyStart)
        if (end < 0) return null
        return roomHtml.substring(bodyStart, end)
    }

    /**
     * 两段式签名，返回上游 params_list（[v, did, tt, sign]）。
     *
     * @param jsEngine JS 求值函数（PyExecJS call 语义）；生产传 QuickJsEngine::eval
     * @param roomHtml 房间页 HTML（或直接传签名脚本块内文）
     * @param rid 房间号
     * @param did 设备 did（上游固定值）
     * @param t10 十位时间戳字符串（上游取当前时间；测试注入固定值）
     * @throws IllegalStateException 提取或 JS 执行失败
     */
    fun getTokenParams(
        eval: (String) -> String,
        roomHtml: String,
        rid: String,
        did: String = DEFAULT_DID,
        t10: String,
    ): List<String> {
        val block = extractSignScript(roomHtml)
            ?: throw IllegalStateException("douyu: sign script (ub98484234) not found in room page")
        val funcUb9 = RE_EVAL_CALL.replace(block, "strc;}")

        // PyExecJS compile+call ≡ 单次求值：定义后立即调用
        val res = eval(funcUb9 + "\n;ub98484234()")

        val v = RE_V.find(res)?.groupValues?.get(1)
            ?: throw IllegalStateException("douyu: v= not found in ub98484234 result")
        val rb = md5Hex(rid + did + t10 + v)

        val funcSign = res
            .replace(Regex("""return rt;}\);?"""), "return rt;}")
            .replace("(function (", "function sign(")
            .replace("CryptoJS.MD5(cb).toString()", "\"$rb\"")

        val params = eval("$funcSign\n;sign(\"$rid\", \"$did\", \"$t10\")")
        return RE_PARAM.findAll(params).map { it.groupValues[1] }.toList()
    }

    /** md5 小写十六进制（对齐上游 hashlib.md5(...).hexdigest()） */
    fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
