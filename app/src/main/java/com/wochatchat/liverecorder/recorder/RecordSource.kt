package com.wochatchat.liverecorder.recorder

import java.io.File
import java.net.URLDecoder

/**
 * 录制源选择与文件命名纯逻辑（对照上游 main.py）。
 *
 * - [selectSourceUrl]  ← main.py:534 select_source_url + is_flv_preferred_platform
 * - [getRecordHeaders] ← main.py get_record_headers
 * - [cleanName]        ← main.py clean_name（rstr 正则 + （）替换 + remove_emojis）
 * - [getQualityCode]   ← main.py get_quality_code
 *
 * 纯 JVM 函数，无 Android 依赖，单测覆盖。
 */
object RecordSource {

    /** FLV 优先平台（上游 is_flv_preferred_platform：douyin/tiktok 优先 FLV 直下）。 */
    fun isFlvPreferredPlatform(link: String): Boolean =
        link.contains("douyin") || link.contains("tiktok")

    /**
     * 选录制源 URL（上游 main.py:534 select_source_url）：
     * - FLV 优先平台：FLV 的 codec 查询参数为 h265 → 用 record_url（HLS），否则用 flv_url
     * - 其余平台：record_url
     */
    fun selectSourceUrl(link: String, flvUrl: String?, recordUrl: String?): String? {
        if (isFlvPreferredPlatform(link)) {
            val codecs = getQueryParam(flvUrl, "codec")
            if (codecs.isNotEmpty() && codecs[0] == "h265") {
                return recordUrl
            }
            return flvUrl
        }
        return recordUrl
    }

    /**
     * 读取 URL 查询参数（对照上游 utils.get_query_params → parse_qs 语义）：
     * 返回该参数所有值；无参数时为空列表。值经 URL 解码（parse_qs 的 unquote_plus）。
     */
    fun getQueryParam(url: String?, paramName: String): List<String> {
        val query = url?.substringAfter('?', "")?.substringBefore('#') ?: ""
        if (query.isEmpty()) return emptyList()
        return query.split('&')
            .mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq == -1) null
                else Pair(
                    part.substring(0, eq),
                    URLDecoder.decode(part.substring(eq + 1), "UTF-8"),
                )
            }
            .filter { it.first == paramName }
            .map { it.second }
    }

    /** 录制附加头（上游 get_record_headers；抖音直播不在表内 → null）。 */
    fun getRecordHeaders(platform: String, liveUrl: String): Pair<String, String>? {
        val liveDomain = liveUrl.split("/").take(3).joinToString("/")
        val headers = mapOf(
            "PandaTV" to "origin:https://www.pandalive.co.kr",
            "WinkTV" to "origin:https://www.winktv.co.kr",
            "PopkonTV" to "origin:https://www.popkontv.com",
            "FlexTV" to "origin:https://www.flextv.co.kr",
            "千度热播" to "referer:https://qiandurebo.com",
            "17Live" to "referer:https://17.live/en/live/6302408",
            "浪Live" to "referer:https://www.lang.live",
            "shopee" to "origin:$liveDomain",
            "Blued直播" to "referer:https://app.blued.cn",
        )
        return headers[platform]?.let { raw ->
            val idx = raw.indexOf(':')
            if (idx > 0) raw.substring(0, idx) to raw.substring(idx + 1) else null
        }
    }

    /** 文件名清洗（上游 clean_name：rstr 正则替换为 _，全角括号转半角，去 emoji，空名兜底）。
     *  [cleanEmoji] 对齐上游「是否去除名称中的表情符号」（默认是）。 */
    fun cleanName(inputText: String, cleanEmoji: Boolean = true): String {
        var cleaned = RSTR_REGEX.replace(inputText.trim(), "_").trim('_')
        cleaned = cleaned.replace("（", "(").replace("）", ")")
        if (cleanEmoji) cleaned = EMOJI_PATTERN.replace(cleaned, "_").trim('_')
        return cleaned.ifEmpty { "空白昵称" }
    }

    // ---------- 5b：文件命名规则（上游 main.py:1117-1146 路径/文件名拼接） ----------

    /**
     * 文件命名选项（对齐上游 config.ini [录制设置] 5 项 + 默认值）。
     * @param folderByAuthor  保存文件夹是否以作者区分（上游默认是）
     * @param folderByTime    保存文件夹是否以时间区分（上游默认否）
     * @param folderByTitle   保存文件夹是否以标题区分（上游默认否）
     * @param filenameByTitle 保存文件名是否包含标题（上游默认否）
     * @param cleanEmoji      是否去除名称中的表情符号（上游默认是）
     */
    data class NamingOptions(
        val folderByAuthor: Boolean = true,
        val folderByTime: Boolean = false,
        val folderByTitle: Boolean = false,
        val filenameByTitle: Boolean = false,
        val cleanEmoji: Boolean = true,
    )

    /**
     * 保存目录（上游 main.py:1124-1143 full_path 拼接顺序逐行对齐）：
     * {base}/{平台}[/{主播}] → +/{日期} → 标题开关且标题非空时
     * +/{标题}_{主播}（已按时间）或 +/{日期}_{标题}（未按时间）。
     */
    fun buildSaveDir(
        baseDir: File,
        platform: String,
        anchor: String,
        liveTitle: String,
        date: String,
        opts: NamingOptions,
    ): File {
        var dir = File(baseDir, platform)
        if (opts.folderByAuthor) dir = File(dir, anchor)
        if (opts.folderByTime) dir = File(dir, date)
        if (opts.folderByTitle && liveTitle.isNotEmpty()) {
            dir = if (opts.folderByTime) File(dir, "${liveTitle}_$anchor")
            else File(dir, "${date}_$liveTitle")
        }
        return dir
    }

    /**
     * 文件名主干（上游 `anchor_name + f'_{title_in_name}' + now`，
     * title_in_name = clean 后标题 + '_' 仅当开关开且标题非空）。
     */
    fun buildBaseName(anchor: String, liveTitle: String, timestamp: String, filenameByTitle: Boolean): String {
        val titleInName = if (filenameByTitle && liveTitle.isNotEmpty()) "${liveTitle}_" else ""
        return "${anchor}_$titleInName$timestamp"
    }

    /** 画质名 → 画质码（上游 get_quality_code，缺失返回 null）。 */
    fun getQualityCode(qualityZh: String): String? = QUALITY_CODE[qualityZh]

    /**
     * 录制地址协议处理（上游 main.py:1150-1156）：
     * - forceHttps（是否强制启用https录制）：http:// 前缀改写为 https://
     * - shopee / migu 平台例外，强制 http（上游 http_record_list，无论开关）
     */
    fun applyRecordingScheme(url: String, forceHttps: Boolean, platform: String): String {
        if (platform == "shopee" || platform == "migu") {
            return url.replace("https://", "http://")
        }
        return if (forceHttps && url.startsWith("http://")) {
            url.replace("http://", "https://")
        } else url
    }

    private val RSTR_REGEX = Regex("[/\\\\:*？?\"<>|&#.。,， ~！·]")

    // 上游 remove_emojis 的 Unicode 区间（utils.py:118），\x{...} 语法 JVM Pattern 原生支持
    private val EMOJI_PATTERN = Regex(
        "[\\x{1F1E0}-\\x{1F1FF}\\x{1F300}-\\x{1F5FF}\\x{1F600}-\\x{1F64F}" +
            "\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}" +
            "\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}" +
            "\\x{1FA70}-\\x{1FAFF}\\x{2702}-\\x{27B0}]+",
    )

    /** 质量名 → 画质码（上游 get_quality_code）。 */
    private val QUALITY_CODE = mapOf(
        "原画" to "OD",
        "蓝光" to "BD",
        "超清" to "UHD",
        "高清" to "HD",
        "标清" to "SD",
        "流畅" to "LD",
    )
}
